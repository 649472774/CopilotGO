package com.tongxie.copilotgo.data.agent

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.chat.AgentJsonGuard
import com.tongxie.copilotgo.data.chat.AgentRequestEncoder
import com.tongxie.copilotgo.data.chat.ModelUnavailableException
import com.tongxie.copilotgo.data.chat.StreamProtocolException
import com.tongxie.copilotgo.data.net.networkErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One structured operation inside the caller's existing session reservation.
 * The executor never sees account objects; callbacks are the durable admission/approval boundary.
 */
class AgentEngine(
    private val modelTransport: AgentModelTransport,
    private val executor: AgentToolExecutor,
    private val promptBuilder: AgentPromptBuilder,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val clock: () -> Long = System::currentTimeMillis
) : AgentRunner {
    override fun isApprovalCurrent(binding: AgentApprovalBinding): Boolean = executor.isCurrent(binding.tool)

    override suspend fun prepare(input: AgentRunInput): PreparedAgentRun = prepareRun(input)

    override suspend fun run(input: AgentRunInput, callbacks: AgentRunCallbacks): AgentRunRecord =
        withContext(Dispatchers.Default) {
            val operation = Operation(input, callbacks)
            operation.run()
        }

    private suspend fun prepareRun(input: AgentRunInput): PreparedRun = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        withTimeoutOrNull(input.settings.limits.maxDurationMillis) {
            require(input.settings.enabled) { "Agent mode was not enabled" }
            currentCoroutineContext().ensureActive()
            val supplied = executor.snapshot()
            val snapshot = supplied.copy(
                tools = supplied.tools.map { it.copy(inputSchema = AgentValues.detached(it.inputSchema)) },
                issues = supplied.issues.toList()
            )
            validateCatalog(snapshot, input.settings.limits)
            if (snapshot.tools.isEmpty()) {
                throw AgentToolException("没有可用工具，请在 Agent 设置中启用搜索或配置远程 MCP")
            }
            val prompt = promptBuilder.prepare(
                input.history, input.model, snapshot.tools.map { it.modelDefinition() }, limits = input.settings.limits
            )
            AgentRequestEncoder.encode(prompt.request, json)
            currentCoroutineContext().ensureActive()
            PreparedRun(input, snapshot, prompt, started).also { it.ensureCurrent() }
        } ?: throw AgentContextLimitException("准备 Agent 请求超时，草稿已保留")
    }

    private inner class PreparedRun(
        val input: AgentRunInput,
        val snapshot: AgentToolSnapshot,
        val firstPrompt: PreparedAgentPrompt,
        private val started: TimeMark
    ) : PreparedAgentRun {
        fun remainingMillis() = input.settings.limits.maxDurationMillis - started.elapsedNow().inWholeMilliseconds

        override fun ensureCurrent() {
            validateCatalog(snapshot, input.settings.limits)
            if (remainingMillis() <= 0) throw AgentContextLimitException("准备 Agent 请求超时，草稿已保留")
        }

        override suspend fun run(callbacks: AgentRunCallbacks): AgentRunRecord = withContext(Dispatchers.Default) {
            Operation(input, callbacks, this@PreparedRun).run()
        }
    }

    private fun validateCatalog(snapshot: AgentToolSnapshot, limits: AgentLimits) {
        if (snapshot.revision != executor.revision.value) throw AgentToolConfigurationChangedException()
        if (snapshot.tools.size > limits.maxTools || snapshot.tools.map { it.name }.distinct().size != snapshot.tools.size) {
            throw AgentToolException("工具数量过多或名称重复，请检查配置")
        }
        snapshot.tools.forEach { tool ->
            if (!NAME.matches(tool.name) || tool.description.length > 8192 ||
                tool.identity.configId.isBlank() || tool.identity.configId.length > 128 ||
                tool.identity.configRevision < 0 || tool.identity.definitionDigest.length !in 1..128 ||
                tool.identity.toolName.length !in 1..256 || tool.destination.length !in 1..2048 ||
                tool.destination.any { it.isISOControl() } || !executor.isCurrent(tool.identity)
            ) throw AgentToolException("工具定义或配置标识无效，请刷新工具设置")
        }
    }

    private inner class Operation(
        private val input: AgentRunInput,
        private val callbacks: AgentRunCallbacks,
        private val prepared: PreparedRun? = null
    ) {
        private val limits = input.settings.limits
        private var record = AgentRunRecord(input.runId, input.accountGeneration, startedAt = input.startedAt)
        @Volatile private var configurationChanged = false
        private val seenCalls = mutableSetOf<String>()
        private var totalResultBytes = 0
        private val historicalSources = input.history.flatMap { message ->
            message.agentRun?.steps.orEmpty().flatMap { step ->
                step.toolCalls.filter { it.status == AgentToolCallStatus.SUCCEEDED && it.result?.isError == false }
                    .flatMap { call ->
                        call.result?.sources.orEmpty().filter { source ->
                            source.toolCallId == call.id && SOURCE_ID.matches(source.id) &&
                                source.id.drop(1).toIntOrNull()?.let { it in 1..MAX_SOURCE_INDEX } == true
                        }
                    }
            }
        }
            .distinctBy { it.id }
        private var sourceNumber = historicalSources.mapNotNull {
            it.id.removePrefix("S").toIntOrNull()
        }.maxOrNull() ?: 0

        suspend fun run(): AgentRunRecord {
            try {
                require(input.settings.enabled) { "Agent mode was not enabled" }
                if (!input.model.chatCompatible || !input.model.supportsTools) {
                    throw ModelUnavailableException("所选模型不支持 Agent 工具调用")
                }
                ensureCurrent()
                val completed = withTimeoutOrNull(prepared?.remainingMillis() ?: limits.maxDurationMillis) { loop(); true }
                if (completed == null) {
                    record = record.interrupt(AgentRunStatus.LIMIT_REACHED, "已达到 Agent 总运行时间限制，现有内容已保留", clock())
                }
                publish()
            } catch (e: CancellationException) {
                val reason = if (configurationChanged) "工具配置已更改，本次运行已中断；不会自动重放工具调用"
                else "运行已停止；未完成的远端操作不会自动重放"
                record = record.interrupt(
                    if (configurationChanged) AgentRunStatus.INTERRUPTED else AgentRunStatus.CANCELLED, reason, clock()
                )
                withContext(NonCancellable) { publish() }
                throw e
            } catch (e: AgentContextLimitException) {
                record = record.interrupt(AgentRunStatus.LIMIT_REACHED, requireNotNull(e.message), clock())
                publish()
            } catch (_: AgentToolConfigurationChangedException) {
                currentCoroutineContext().ensureActive()
                record = record.interrupt(AgentRunStatus.INTERRUPTED, "工具配置或参数展示已更改，旧审批已失效", clock())
                publish()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                val reason = when (e) {
                    is AgentToolException -> e.userMessage
                    is ModelUnavailableException, is StreamProtocolException -> e.message ?: "Agent 响应无效"
                    else -> networkErrorMessage(e)
                }
                record = record.interrupt(AgentRunStatus.FAILED, reason, clock())
                publish()
            } catch (_: SerializationException) {
                currentCoroutineContext().ensureActive()
                record = record.interrupt(AgentRunStatus.FAILED, "工具调用或结果格式无效，未继续执行", clock())
                publish()
            } catch (_: IllegalArgumentException) {
                currentCoroutineContext().ensureActive()
                record = record.interrupt(AgentRunStatus.FAILED, "工具调用参数或配置无效，未继续执行", clock())
                publish()
            }
            return record.detached()
        }

        private suspend fun loop() = coroutineScope {
            val preparation = prepared ?: prepareRun(input)
            preparation.ensureCurrent()
            val snapshot = preparation.snapshot
            if (snapshot.issues.isNotEmpty()) {
                record = record.copy(notice = "部分工具不可用，请在设置中查看；本次仅使用成功加载的工具")
            }
            val operationScope = this
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                executor.revision.first { it != snapshot.revision }
                configurationChanged = true
                operationScope.cancel("Tool configuration changed")
            }
            try {
                while (!record.status.isTerminal) {
                    ensureCurrent()
                    if (record.steps.size >= limits.maxSteps) {
                        limit("已达到 Agent 模型轮次限制，现有内容已保留")
                        break
                    }
                    val prompt = if (record.steps.isEmpty()) preparation.firstPrompt else promptBuilder.prepare(
                        input.history, input.model, snapshot.tools.map { it.modelDefinition() }, record.steps, limits
                    )
                    if (prompt.truncated) {
                        record = record.copy(notice = "较早的完整轮次已从请求中省略；原会话及工具记录仍完整保留")
                    }
                    val stepIndex = record.steps.size
                    record = record.copy(
                        status = AgentRunStatus.RUNNING,
                        steps = record.steps + AgentStepRecord(stepIndex)
                    )
                    publish()
                    var terminal: AgentStreamEvent.Completed? = null
                    var lastPublish = clock()
                    modelTransport.stream(prompt.request).collect { event ->
                        ensureCurrent()
                        when (event) {
                            is AgentStreamEvent.TextDelta -> {
                                if (event.choiceIndex != 0) return@collect
                                if (terminal != null) throw StreamProtocolException("模型在结束后继续发送内容")
                                val text = record.steps.last().assistantText + event.text
                                if (text.length > Constants.MAX_RESPONSE_CHARACTERS ||
                                    record.steps.sumOf { it.assistantText.length.toLong() } + event.text.length >
                                    Constants.MAX_RESPONSE_CHARACTERS
                                ) throw AgentContextLimitException("Agent 回复超过本地安全长度限制")
                                updateStep { it.copy(assistantText = text) }
                                val now = clock()
                                publish(durable = now - lastPublish >= 800)
                                if (now - lastPublish >= 800) lastPublish = now
                            }
                            is AgentStreamEvent.Completed -> {
                                if (event.choiceIndex != 0) return@collect
                                if (terminal != null) throw StreamProtocolException("模型重复结束同一轮回复")
                                terminal = event
                            }
                        }
                    }
                    val completion = terminal ?: throw StreamProtocolException("模型回复在完成前中断")
                    updateStep { it.copy(finishReason = completion.finishReason) }
                    if (completion.toolCalls.isEmpty()) {
                        if (completion.finishReason !in setOf("stop", "length")) {
                            throw StreamProtocolException("模型返回了不受支持的结束状态")
                        }
                        if (record.steps.last().assistantText.isBlank()) {
                            throw StreamProtocolException("模型未返回最终答复，工具活动不代表回答已完成")
                        }
                        val grounded = AgentCitations.ground(
                            record.steps.last().assistantText, historicalSources + record.sources
                        )
                        if (grounded.text.isBlank()) throw StreamProtocolException("模型未返回可对应实际结果的最终答复")
                        updateStep { it.copy(assistantText = grounded.text) }
                        val citedHistory = historicalSources.filter { grounded.text.contains("[${it.id}]") }
                        record = record.copy(
                            status = if (completion.finishReason == "length") AgentRunStatus.LIMIT_REACHED
                            else AgentRunStatus.COMPLETED,
                            finishedAt = clock(),
                            sources = (record.sources + citedHistory).distinctBy { it.id },
                            notice = when {
                                grounded.removedUnsupportedReferences -> "模型返回了无法对应实际工具结果的引用，已移除这些引用"
                                completion.finishReason == "length" -> "模型回复已达到输出长度限制，现有内容已保留"
                                else -> record.notice
                            }
                        )
                        break
                    }
                    if (completion.finishReason != "tool_calls") {
                        throw StreamProtocolException("工具调用缺少完整的结束标记")
                    }
                    if (seenCalls.size + completion.toolCalls.size > limits.maxToolCalls) {
                        limit("已达到 Agent 工具调用次数限制，新提议未执行")
                        break
                    }
                    if (record.steps.size >= limits.maxSteps) {
                        limit("已达到 Agent 模型轮次限制，新工具提议未执行")
                        break
                    }
                    val proposals = validateProposals(completion.toolCalls, snapshot)
                    updateStep { it.copy(toolCalls = proposals.map { proposal -> proposal.record }) }
                    publish()
                    for (proposal in proposals) {
                        ensureCurrent()
                        execute(proposal)
                        if (record.status.isTerminal) break
                    }
                }
            } finally {
                watcher.cancelAndJoin()
            }
        }

        private suspend fun validateProposals(
            calls: List<AgentToolCall>,
            snapshot: AgentToolSnapshot
        ): List<Proposal> {
            if (calls.map { it.id }.distinct().size != calls.size || calls.any { it.id in seenCalls }) {
                throw StreamProtocolException("模型返回重复的工具调用标识，未执行重复操作")
            }
            calls.forEach { call ->
                if (!CALL_ID.matches(call.id) || call.type != "function" || !NAME.matches(call.function.name) ||
                    AgentValues.utf8Size(call.function.arguments) > limits.maxArgumentBytes
                ) throw StreamProtocolException("工具调用格式无效或参数超过安全限制")
            }
            updateStep { step ->
                step.copy(toolCalls = calls.map { call ->
                    val tool = snapshot.tools.firstOrNull { it.name == call.function.name }
                    AgentToolCallRecord(
                        call.id, call.function.name, tool?.identity, tool?.destination.orEmpty(),
                        tool?.kind ?: AgentToolKind.MCP, createdAt = clock()
                    )
                })
            }
            publish()
            return calls.map { call ->
                ensureCurrent()
                val arguments = AgentJsonGuard.objectValue(call.function.arguments, limits.maxArgumentBytes)
                val tool = snapshot.tools.firstOrNull { it.name == call.function.name }
                    ?: throw AgentToolException("模型请求了未启用的工具，未执行该操作")
                val invocation = AgentToolInvocation(input.runId, call.id, tool, AgentValues.detached(arguments))
                val digest = AgentValues.digest(AgentValues.canonical(invocation.arguments))
                val descriptorDigest = AgentValues.descriptorDigest(tool)
                if (!executor.isCurrent(tool.identity)) throw AgentToolConfigurationChangedException()
                val validated = executor.validate(invocation)
                if (AgentValues.utf8Size(validated.displayArguments.toString()) > limits.maxArgumentBytes) {
                    throw AgentToolException("工具参数展示超过安全限制")
                }
                requireUnchanged(invocation, digest, descriptorDigest)
                seenCalls.add(call.id)
                Proposal(
                    invocation, digest, descriptorDigest,
                    AgentToolCallRecord(
                        call.id, tool.name, tool.identity, tool.destination, tool.kind,
                        AgentValues.detached(validated.displayArguments), digest, createdAt = clock()
                    )
                )
            }
        }

        private suspend fun execute(proposal: Proposal) {
            val invocation = proposal.invocation
            if (limits.maxTotalResultBytes - totalResultBytes < MIN_RESULT_BYTES ||
                limits.maxResultBytes < MIN_RESULT_BYTES
            ) {
                limit("剩余工具结果空间不足，未执行新的操作")
                return
            }
            val autoApproved = input.settings.autoApprovePublicWebReads &&
                invocation.tool.kind in setOf(AgentToolKind.PUBLIC_WEB_SEARCH, AgentToolKind.PUBLIC_WEB_READ)
            requireUnchanged(invocation, proposal.argumentsDigest, proposal.descriptorDigest)
            var approvalExpiresAt: Long? = null
            if (!autoApproved) {
                val now = clock()
                val binding = AgentApprovalBinding(
                    UUID.randomUUID().toString(), input.runId, input.accountGeneration, invocation.callId,
                    invocation.tool.identity, proposal.argumentsDigest, proposal.descriptorDigest
                )
                val approval = AgentApprovalRequest(
                    binding, invocation.tool.name, invocation.tool.destination,
                    requireNotNull(proposal.record.arguments), now, now + limits.approvalTimeoutMillis
                )
                approvalExpiresAt = approval.expiresAt
                updateCall(invocation.callId) { it.copy(status = AgentToolCallStatus.AWAITING_APPROVAL) }
                record = record.copy(status = AgentRunStatus.AWAITING_APPROVAL, pendingApproval = approval)
                publish()
                val decision = withTimeoutOrNull(limits.approvalTimeoutMillis) { callbacks.awaitApproval(approval) }
                ensureCurrent()
                requireUnchanged(invocation, proposal.argumentsDigest, proposal.descriptorDigest)
                record = record.copy(status = AgentRunStatus.RUNNING, pendingApproval = null)
                if (decision != AgentApprovalDecision.APPROVE || clock() >= approval.expiresAt) {
                    updateCall(invocation.callId) {
                        it.copy(
                            status = AgentToolCallStatus.DENIED, finishedAt = clock(),
                            result = AgentToolResult(
                                if (decision == null) "Approval expired; tool was not executed"
                                else "User denied the proposal; tool was not executed",
                                isError = true
                            )
                        )
                    }
                    publish()
                    return
                }
            }
            // Revalidate after the human wait; a changed schema/projection cannot reuse old consent.
            val validation = executor.validate(invocation)
            if (validation.displayArguments != proposal.record.arguments) throw AgentToolConfigurationChangedException()
            requireUnchanged(invocation, proposal.argumentsDigest, proposal.descriptorDigest)
            if (approvalExpiresAt?.let { clock() >= it } == true) {
                denyExpired(invocation.callId)
                return
            }
            updateCall(invocation.callId) { it.copy(status = AgentToolCallStatus.RUNNING, startedAt = clock()) }
            publish()
            ensureCurrent()
            requireUnchanged(invocation, proposal.argumentsDigest, proposal.descriptorDigest)
            if (approvalExpiresAt?.let { clock() >= it } == true) {
                denyExpired(invocation.callId)
                return
            }
            val result = try {
                withTimeoutOrNull(limits.toolTimeoutMillis) { executor.execute(invocation) }
                    ?: AgentToolResult(
                        "Tool timed out; no successful result was received", isError = true,
                        outcomeUnknown = invocation.tool.kind == AgentToolKind.MCP
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgentToolException) {
                AgentToolResult(e.userMessage, isError = true, outcomeUnknown = invocation.tool.kind == AgentToolKind.MCP)
            } catch (e: IOException) {
                AgentToolResult(networkErrorMessage(e), isError = true, outcomeUnknown = invocation.tool.kind == AgentToolKind.MCP)
            }
            ensureCurrent()
            requireUnchanged(invocation, proposal.argumentsDigest, proposal.descriptorDigest)
            val bounded = boundResult(result, invocation.callId)
            updateCall(invocation.callId) {
                it.copy(
                    status = if (bounded.isError || bounded.outcomeUnknown) AgentToolCallStatus.FAILED
                    else AgentToolCallStatus.SUCCEEDED,
                    result = bounded, finishedAt = clock(), outcomeUnknown = bounded.outcomeUnknown
                )
            }
            record = record.copy(
                sources = (record.sources + bounded.sources).distinctBy { it.id },
                notice = if (bounded.truncated) "工具结果超过安全长度，已截断；仅使用已保留的内容" else record.notice
            )
            publish()
            if (totalResultBytes >= limits.maxTotalResultBytes) {
                limit("已达到 Agent 工具结果总量限制，未继续执行；现有内容已保留")
            }
        }

        private fun boundResult(result: AgentToolResult, callId: String): AgentToolResult {
            val available = minOf(limits.maxResultBytes, (limits.maxTotalResultBytes - totalResultBytes).coerceAtLeast(0))
            var sourceBytes = 0
            var retainedSources = 0
            val sources = if (result.isError || result.outcomeUnknown) emptyList() else {
                result.sources.take(MAX_SOURCES_PER_CALL).mapNotNull { source ->
                    val uri = try { URI(source.url) } catch (_: URISyntaxException) { null }
                    if (source.url.length > 8192 || uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() ||
                        uri.userInfo != null || source.url.any { it.isISOControl() }
                    ) throw AgentToolException("工具返回了无效的来源链接，未将其作为引用")
                    val safe = source.copy(
                        id = "S${sourceNumber + 1}", toolCallId = callId,
                        title = AgentValues.truncateUtf8(source.title, 512),
                        excerpt = source.excerpt?.let { AgentValues.truncateUtf8(it, 1024) }
                    )
                    val bytes = AgentValues.utf8Size(json.encodeToString(SourceReference.serializer(), safe))
                    if (sourceBytes + bytes > available / 2 ||
                        record.sources.size + retainedSources >= MAX_SOURCES_PER_RUN || sourceNumber >= MAX_SOURCE_INDEX
                    ) null else safe.also { sourceBytes += bytes; retainedSources++; sourceNumber++ }
                }
            }
            var lower = 0
            var upper = minOf(AgentValues.utf8Size(result.content), available)
            fun candidate(bytes: Int): AgentToolResult {
                val content = AgentValues.truncateUtf8(result.content, bytes)
                return AgentToolResult(
                    content, result.isError || result.outcomeUnknown, sources,
                    result.truncated || content != result.content || sources.size != result.sources.size,
                    result.outcomeUnknown
                )
            }
            fun encodedSize(value: AgentToolResult) =
                AgentValues.utf8Size(json.encodeToString(AgentToolResult.serializer(), value))
            if (encodedSize(candidate(0)) > available) {
                throw AgentContextLimitException("工具结果元数据超过剩余空间，现有内容已保留")
            }
            while (lower < upper) {
                val middle = lower + (upper - lower + 1) / 2
                if (encodedSize(candidate(middle)) <= available) lower = middle else upper = middle - 1
            }
            val bounded = candidate(lower)
            totalResultBytes += encodedSize(bounded)
            // Hitting the byte boundary is a real limit even when UTF-8 truncation leaves a small gap.
            if (bounded.truncated && available < limits.maxResultBytes) totalResultBytes = limits.maxTotalResultBytes
            return bounded
        }

        private fun limit(reason: String) {
            record = record.interrupt(AgentRunStatus.LIMIT_REACHED, reason, clock())
        }

        private suspend fun denyExpired(callId: String) {
            updateCall(callId) {
                it.copy(
                    status = AgentToolCallStatus.DENIED, startedAt = null, finishedAt = clock(),
                    result = AgentToolResult("Approval expired; tool was not executed", isError = true)
                )
            }
            publish()
        }

        private suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            callbacks.ensureActive()
        }

        private fun requireUnchanged(invocation: AgentToolInvocation, argumentsDigest: String, descriptorDigest: String) {
            callbacks.ensureActive()
            if (!executor.isCurrent(invocation.tool.identity) ||
                AgentValues.digest(AgentValues.canonical(invocation.arguments)) != argumentsDigest ||
                AgentValues.descriptorDigest(invocation.tool) != descriptorDigest
            ) throw AgentToolConfigurationChangedException()
        }

        private fun updateStep(transform: (AgentStepRecord) -> AgentStepRecord) {
            record = record.copy(steps = record.steps.dropLast(1) + transform(record.steps.last()))
        }

        private fun updateCall(id: String, transform: (AgentToolCallRecord) -> AgentToolCallRecord) {
            updateStep { step ->
                step.copy(toolCalls = step.toolCalls.map { if (it.id == id) transform(it) else it })
            }
        }

        private suspend fun publish(durable: Boolean = true) {
            val content = record.steps.map { it.assistantText }.filter { it.isNotBlank() }.joinToString("\n\n")
            callbacks.publish(record.detached(), content, durable)
        }
    }

    private data class Proposal(
        val invocation: AgentToolInvocation,
        val argumentsDigest: String,
        val descriptorDigest: String,
        val record: AgentToolCallRecord
    )

    companion object {
        private val NAME = Regex("[A-Za-z0-9_-]{1,64}")
        private val CALL_ID = Regex("[\\x21-\\x7e]{1,128}")
        private val SOURCE_ID = Regex("S[1-9][0-9]{0,5}")
        private const val MAX_SOURCES_PER_CALL = 16
        private const val MAX_SOURCES_PER_RUN = 64
        private const val MAX_SOURCE_INDEX = 100_000
        private const val MIN_RESULT_BYTES = 256
    }
}
