package com.tongxie.copilotgo.data.chat

import android.content.ContentResolver
import android.net.Uri
import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.agent.AgentApprovalBinding
import com.tongxie.copilotgo.data.agent.AgentApprovalDecision
import com.tongxie.copilotgo.data.agent.AgentApprovalRequest
import com.tongxie.copilotgo.data.agent.AgentApprovalResponse
import com.tongxie.copilotgo.data.agent.AgentContextLimitException
import com.tongxie.copilotgo.data.agent.AgentRunCallbacks
import com.tongxie.copilotgo.data.agent.AgentRunInput
import com.tongxie.copilotgo.data.agent.AgentRunRecord
import com.tongxie.copilotgo.data.agent.AgentRunStatus
import com.tongxie.copilotgo.data.agent.AgentRunner
import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import com.tongxie.copilotgo.data.agent.AgentToolConfigurationChangedException
import com.tongxie.copilotgo.data.agent.AgentToolException
import com.tongxie.copilotgo.data.agent.detached
import com.tongxie.copilotgo.data.agent.interrupt
import com.tongxie.copilotgo.data.net.networkErrorMessage
import com.tongxie.copilotgo.data.storage.AttachmentImportException
import com.tongxie.copilotgo.data.storage.AttachmentStore
import com.tongxie.copilotgo.data.storage.SessionDeletedException
import com.tongxie.copilotgo.data.storage.SessionStorageException
import com.tongxie.copilotgo.data.storage.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Application-owned operations. SessionStore owns all conversation state and metadata. */
class ChatStreamCenter(
    private val store: SessionStore,
    private val chatClient: CopilotChatClient,
    private val catalog: ModelCatalog = chatClient.modelCatalog,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val maxInactiveSessions: Int = 8,
    private val agentRunner: AgentRunner? = null
) {
    private val guard = Any()
    private val slots = LinkedHashMap<String, Slot>(16, 0.75f, true)
    private val promptBuilder = PromptBuilder(store.attachments)
    private val deletionListener: (String) -> Unit = { stop(it) }
    private var accountGeneration = chatClient.accountGeneration.value

    private class Slot {
        val sending = MutableStateFlow(false)
        val error = MutableStateFlow<String?>(null)
        val notice = MutableStateFlow<String?>(null)
        var ticket: Ticket? = null
        var owners = 0
    }

    private class Ticket(val accountGeneration: Long) {
        var job: Job? = null
        var stopRequested = false
        var agentRunId: String? = null
        var pendingApproval: PendingApproval? = null
    }

    private class PendingApproval(val request: AgentApprovalRequest) {
        val decision = CompletableDeferred<AgentApprovalDecision>()
    }

    private sealed interface Request {
        data class New(
            val text: String,
            val attachments: List<String>,
            val imageUrls: List<String>,
            val refs: List<AttachmentRef>,
            val submissionId: String?,
            val agentSettings: AgentSessionSettings?
        ) : Request
        data class Regenerate(val assistantId: String) : Request
        data class Edit(val userId: String, val text: String) : Request
        data object Retry : Request
    }

    init {
        store.addDeletionListener(deletionListener)
        scope.launch {
            chatClient.accountGeneration.collect { generation ->
                if (generation != accountGeneration) {
                    accountGeneration = generation
                    synchronized(guard) {
                        slots.values.forEach { slot ->
                            slot.ticket?.takeIf { it.accountGeneration != generation }?.let {
                                it.stopRequested = true
                                it.job?.cancel()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun slot(id: String): Slot = synchronized(guard) { slots.getOrPut(id) { Slot() } }
    fun sessionFlow(id: String): StateFlow<Session?> = store.sessionFlow(id)
    fun loadState(id: String): StateFlow<SessionLoadState> = store.loadState(id)
    fun sendingFlow(id: String): StateFlow<Boolean> = slot(id).sending.asStateFlow()
    fun errorFlow(id: String): StateFlow<String?> = slot(id).error.asStateFlow()
    fun noticeFlow(id: String): StateFlow<String?> = slot(id).notice.asStateFlow()
    fun clearError(id: String) { slot(id).error.value = null }

    fun retain(id: String) {
        store.retain(id)
        synchronized(guard) { slot(id).owners++ }
    }

    fun release(id: String) {
        store.release(id)
        synchronized(guard) {
            slots[id]?.let { it.owners = (it.owners - 1).coerceAtLeast(0) }
            trimSlots()
        }
    }

    fun reload(id: String) {
        scope.launch {
            try {
                store.getSession(id, reload = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                slot(id).error.value = friendlyError(e)
            }
        }
    }

    suspend fun importAttachment(resolver: ContentResolver, uri: Uri): AttachmentRef =
        store.attachments.importAttachment(resolver, uri)

    fun attachmentFile(ref: AttachmentRef): File = store.attachments.attachmentFile(ref)

    suspend fun submit(
        id: String,
        text: String,
        attachments: List<String> = emptyList(),
        imageUrls: List<String> = emptyList(),
        attachmentRefs: List<AttachmentRef> = emptyList(),
        submissionId: String? = null,
        agentSettings: AgentSessionSettings? = null
    ): SendResult = launchRequest(id, Request.New(
        text, attachments.toList(), imageUrls.toList(), attachmentRefs.toList(), submissionId, agentSettings
    ))

    fun send(id: String, text: String, attachments: List<String> = emptyList(), imageUrls: List<String> = emptyList()) {
        scope.launch { submit(id, text, attachments, imageUrls) }
    }

    private fun reserve(id: String): Pair<Slot, Ticket>? = synchronized(guard) {
        val slot = slot(id)
        if (slot.ticket != null) return@synchronized null
        store.retain(id)
        val ticket = Ticket(chatClient.accountGeneration.value)
        slot.ticket = ticket
        slot.sending.value = true
        slot.error.value = null
        slot.notice.value = null
        slot to ticket
    }

    private suspend fun launchRequest(id: String, request: Request): SendResult {
        val (slot, ticket) = reserve(id) ?: return SendResult.Rejected("此会话正在处理请求，请先停止或稍后重试")
        val receipt = CompletableDeferred<SendResult>()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var assistantId: String? = null
            var committed = false
            var failure: String? = null
            var finishReason: String? = null
            var stopped = false
            try {
                val original = store.getSession(id) ?: throw SessionDeletedException()
                val history = prepareHistory(original, request)
                val user = history.last()
                if (request is Request.New && request.submissionId != null) {
                    original.messages.firstOrNull {
                        it.role == "user" && it.submissionId == request.submissionId
                    }?.let {
                        if (it.content != user.content || it.imageUrls != user.imageUrls || it.attachments != user.attachments) {
                            throw ModelUnavailableException("此草稿标识已用于不同内容，请修改草稿后重试")
                        }
                        receipt.complete(SendResult.Accepted(it.id))
                        return@launch
                    }
                }
                val needsVision = history.any { message ->
                    message.imageUrls.isNotEmpty() || message.attachments.any { it.kind == AttachmentKind.IMAGE }
                }
                if (request is Request.New && request.agentSettings != null &&
                    request.agentSettings != original.agentSettings
                ) throw ModelUnavailableException("Agent 设置已更新，请刷新后重新发送；草稿已保留")
                val settings = original.agentSettings
                if (settings.enabled && agentRunner == null) {
                    throw ModelUnavailableException("当前安装尚未配置 Agent 工具运行时，请关闭 Agent 模式后聊天")
                }
                val model = catalog.requireModel(
                    original.model, original.model.isBlank() && needsVision, needsTools = settings.enabled
                )
                val responseId = UUID.randomUUID().toString()
                val agentRun = if (settings.enabled) {
                    AgentRunRecord(UUID.randomUUID().toString(), ticket.accountGeneration)
                } else null
                val preparedAgent = agentRun?.let {
                    requireNotNull(agentRunner).prepare(AgentRunInput(
                        it.id, id, ticket.accountGeneration, model, history, settings, it.startedAt
                    ))
                }
                val prompt = if (preparedAgent == null) promptBuilder.prepare(history, model) else null
                currentCoroutineContext().ensureActive()
                synchronized(guard) { ticket.agentRunId = agentRun?.id }
                assistantId = responseId
                withContext(NonCancellable) {
                    if (synchronized(guard) { ticket.stopRequested } ||
                        ticket.accountGeneration != chatClient.accountGeneration.value
                    ) throw CancellationException("Stopped or account changed")
                    store.update(id) { latest ->
                        preparedAgent?.ensureCurrent()
                        if (latest.messages != original.messages || latest.model != original.model ||
                            latest.agentSettings != original.agentSettings
                        ) {
                            throw com.tongxie.copilotgo.data.storage.SessionConflictException()
                        }
                        latest.copy(
                            model = if (latest.model.isBlank()) model.id else latest.model,
                            title = if (latest.title == "新会话" && latest.messages.isEmpty()) {
                                safeTitle(user.content.ifBlank { if (needsVision) "图片对话" else "附件对话" })
                            } else latest.title,
                            messages = (history + UiMessage(
                                responseId, "assistant", "", isStreaming = true, agentRun = agentRun
                            )).toMutableList()
                        )
                    }
                    committed = true
                    receipt.complete(SendResult.Accepted(user.id))
                }
                if (prompt?.truncated == true) {
                    slot.notice.value = "较早的完整轮次已从本次请求中省略，原会话仍完整保留"
                }
                finishReason = if (agentRun != null) {
                    val result = chatClient.withAccount {
                        requireNotNull(preparedAgent).run(agentCallbacks(id, responseId, slot, ticket))
                    }
                    if (!result.status.isTerminal) throw StreamProtocolException("Agent 未完成清理便结束运行")
                    if (result.status == AgentRunStatus.FAILED) slot.error.value = result.notice ?: "Agent 运行失败"
                    result.status.finishReason()
                } else stream(id, responseId, requireNotNull(prompt))
                if (agentRun == null && finishReason == "length") slot.notice.value = "本次回复已达到模型输出长度限制"
            } catch (e: CancellationException) {
                stopped = true
                receipt.complete(SendResult.Rejected("已取消发送，草稿已保留"))
                throw e
            } catch (e: Exception) {
                failure = friendlyError(e)
                slot.error.value = failure
                receipt.complete(SendResult.Rejected(failure))
            } finally {
                if (committed && assistantId != null) {
                    withContext(NonCancellable) {
                        try {
                            val messageId = assistantId
                            store.update(id, persist = false) { latest ->
                                latest.copy(messages = latest.messages.map { message ->
                                    if (message.id != messageId) message else {
                                        val run = message.agentRun?.let {
                                            if (it.status.isTerminal) it else it.interrupt(
                                                if (stopped) AgentRunStatus.CANCELLED else AgentRunStatus.FAILED,
                                                if (stopped) "运行已停止，不会自动重放工具调用"
                                                else failure ?: "Agent 未返回完整的最终答复"
                                            )
                                        }
                                        message.copy(
                                            content = message.content.ifEmpty {
                                                when {
                                                    stopped -> "[已停止]"
                                                    failure != null -> "[请求失败] $failure"
                                                    run != null -> run.notice ?: "[Agent 未返回最终答复]"
                                                    else -> "[模型未返回内容]"
                                                }
                                            },
                                            isStreaming = false,
                                            agentRun = run,
                                            finishReason = run?.status?.finishReason() ?: when {
                                                stopped -> "cancelled"
                                                failure != null -> "error"
                                                else -> finishReason ?: "stop"
                                            }
                                        )
                                    }
                                }.toMutableList())
                            }
                            store.persist(id)
                        } catch (_: SessionDeletedException) {
                            // Deletion is authoritative; a stopped stream must never recreate it.
                        } catch (e: Exception) {
                            slot.error.value = friendlyError(e)
                        }
                    }
                }
            }
        }
        startJob(id, slot, ticket, job) {
            receipt.complete(SendResult.Rejected("请求已取消，草稿已保留"))
        }
        return receipt.await()
    }

    private suspend fun prepareHistory(session: Session, request: Request): List<UiMessage> {
        return when (request) {
            is Request.New -> {
                if (request.text.length > Constants.MAX_PROMPT_CHARACTERS) {
                    throw ModelUnavailableException("输入文字过长，请缩短后重试")
                }
                val text = request.text.trim()
                if (text.isEmpty() && request.attachments.isEmpty() && request.imageUrls.isEmpty() && request.refs.isEmpty()) {
                    throw ModelUnavailableException("请输入消息或选择附件")
                }
                if (request.submissionId != null && request.submissionId.length !in 1..128) {
                    throw ModelUnavailableException("草稿标识无效，请重新打开会话")
                }
                if (request.attachments.size + request.imageUrls.size + request.refs.size > AttachmentStore.MAX_ATTACHMENTS) {
                    throw ModelUnavailableException("每轮最多发送 8 个附件")
                }
                val refs = request.refs.toMutableList()
                for ((index, textFile) in request.attachments.withIndex()) {
                    refs.add(store.attachments.importText(textFile, "附件${index + 1}.txt"))
                }
                val remoteImages = mutableListOf<String>()
                for (url in request.imageUrls) {
                    if (url.startsWith("data:")) refs.add(store.attachments.importDataUri(url))
                    else {
                        PromptBuilder.validateImageUrl(url)
                        remoteImages.add(url)
                    }
                }
                if (refs.sumOf { it.sizeBytes } > AttachmentStore.MAX_TURN_BYTES) {
                    throw ModelUnavailableException("每轮附件总大小不能超过 16 MiB")
                }
                refs.forEach { store.attachments.validate(it) }
                session.messages + UiMessage(
                    UUID.randomUUID().toString(), "user",
                    text.ifEmpty { if (remoteImages.isNotEmpty() || refs.any { it.kind == AttachmentKind.IMAGE }) "请看图。" else "请阅读附件。" },
                    imageUrls = remoteImages.toList(), attachments = refs.toList(), submissionId = request.submissionId
                )
            }
            is Request.Regenerate -> {
                val assistantIndex = session.messages.indexOfFirst { it.id == request.assistantId && it.role == "assistant" }
                if (assistantIndex < 0) throw ModelUnavailableException("要重新生成的回复已不存在")
                if (session.messages[assistantIndex].finishReason == "orphaned") {
                    throw ModelUnavailableException("此回复对应的用户消息已删除，无法重新生成")
                }
                val userIndex = (assistantIndex - 1 downTo 0).firstOrNull { session.messages[it].role == "user" }
                    ?: throw ModelUnavailableException("此回复没有对应的用户消息")
                requireSafeReplay(session.messages.drop(userIndex + 1))
                session.messages.take(userIndex + 1)
            }
            is Request.Edit -> {
                val index = session.messages.indexOfFirst { it.id == request.userId && it.role == "user" }
                if (index < 0) throw ModelUnavailableException("要编辑的消息已不存在")
                val text = request.text.trim()
                if (text.isEmpty() || text.length > Constants.MAX_PROMPT_CHARACTERS) {
                    throw ModelUnavailableException("编辑内容不能为空或超过长度限制")
                }
                requireSafeReplay(session.messages.drop(index + 1))
                session.messages.take(index) + session.messages[index].copy(content = text)
            }
            Request.Retry -> {
                val index = session.messages.indexOfLast { it.role == "user" }
                if (index < 0) throw ModelUnavailableException("没有可重试的用户消息")
                requireSafeReplay(session.messages.drop(index + 1))
                session.messages.take(index + 1)
            }
        }
    }

    private suspend fun stream(id: String, assistantId: String, prompt: PreparedPrompt): String? =
        withContext(Dispatchers.Default) {
            val flow = prompt.visionRequest?.let { chatClient.streamVisionChat(it) }
                ?: chatClient.streamChat(requireNotNull(prompt.textRequest))
            val buffer = StringBuilder()
            var lastSave = System.currentTimeMillis()
            var finishReason: String? = null
            flow.collect { delta ->
                if (delta.text.isNotEmpty()) {
                    buffer.append(delta.text)
                    val text = buffer.toString()
                    store.update(id, persist = false) { latest ->
                        latest.copy(messages = latest.messages.map { message ->
                            if (message.id == assistantId) message.copy(content = text) else message
                        }.toMutableList())
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastSave >= 800) {
                        store.persist(id)
                        lastSave = now
                    }
                }
                if (delta.isFinal) finishReason = delta.finishReason
            }
            if (buffer.isBlank()) throw StreamProtocolException("模型未返回可显示的内容，请重试")
            finishReason
        }

    private fun startJob(id: String, slot: Slot, ticket: Ticket, job: Job, completeIfNotStarted: () -> Unit) {
        job.invokeOnCompletion {
            completeIfNotStarted()
            synchronized(guard) {
                if (slot.ticket === ticket) {
                    ticket.pendingApproval?.decision?.cancel()
                    ticket.pendingApproval = null
                    slot.ticket = null
                    slot.sending.value = false
                }
                trimSlots()
            }
            store.release(id)
        }
        synchronized(guard) { ticket.job = job }
        job.start()
        if (synchronized(guard) { ticket.stopRequested }) job.cancel()
    }

    fun stop(id: String) {
        synchronized(guard) {
            slots[id]?.ticket?.let { it.stopRequested = true; it.job?.cancel() }
        }
    }

    fun purge(id: String) = store.markDeleting(id)

    suspend fun regenerateAndAwait(id: String, assistantMsgId: String): OperationResult =
        launchRequest(id, Request.Regenerate(assistantMsgId)).toOperation()

    suspend fun retryLastAndAwait(id: String): OperationResult =
        launchRequest(id, Request.Retry).toOperation()

    suspend fun editAndResendAndAwait(id: String, msgId: String, text: String): OperationResult =
        launchRequest(id, Request.Edit(msgId, text)).toOperation()

    fun regenerate(id: String, assistantMsgId: String) { scope.launch { regenerateAndAwait(id, assistantMsgId) } }
    fun retryLast(id: String) { scope.launch { retryLastAndAwait(id) } }
    fun editAndResend(id: String, msgId: String, text: String) { scope.launch { editAndResendAndAwait(id, msgId, text) } }

    suspend fun deleteMessageAndAwait(id: String, msgId: String): OperationResult = mutate(id) {
        store.update(id) { latest ->
            val index = latest.messages.indexOfFirst { it.id == msgId }
            if (index < 0) throw ModelUnavailableException("要删除的消息已不存在")
            val nextUser = (index + 1 until latest.messages.size).firstOrNull {
                latest.messages[it].role == "user"
            } ?: latest.messages.size
            val turnStart = (index downTo 0).firstOrNull { latest.messages[it].role == "user" } ?: index
            if (latest.messages.subList(turnStart, nextUser).any { it.agentRun?.safeToRetry == false }) {
                throw ModelUnavailableException("此轮包含已执行或结果未知的远端操作，不能单独删除执行记录；可删除整个会话")
            }
            val orphaned = if (latest.messages[index].role == "user") {
                latest.messages.subList(index + 1, nextUser).filter { it.role == "assistant" }.map { it.id }.toSet()
            } else emptySet()
            latest.copy(messages = latest.messages.filterNot { it.id == msgId }.map { message ->
                // Keep the visible reply, but never attach it to an unrelated earlier question.
                if (message.id in orphaned) message.copy(finishReason = "orphaned") else message
            }.toMutableList())
        }
    }

    fun deleteMessage(id: String, msgId: String) { scope.launch { deleteMessageAndAwait(id, msgId) } }

    suspend fun setModelAndAwait(id: String, model: String): OperationResult = mutate(id) {
        if (model.isBlank()) throw ModelUnavailableException("请选择一个可用模型")
        val session = store.getSession(id) ?: throw SessionDeletedException()
        catalog.requireModel(model, needsVision = false, needsTools = session.agentSettings.enabled)
        store.update(id) { it.copy(model = model) }
    }

    fun setModel(id: String, model: String) { scope.launch { setModelAndAwait(id, model) } }

    suspend fun setAgentSettingsAndAwait(
        id: String,
        settings: AgentSessionSettings,
        expectedSettings: AgentSessionSettings? = null
    ): OperationResult = mutate(id) {
        val session = store.getSession(id) ?: throw SessionDeletedException()
        if (settings.enabled) {
            if (agentRunner == null) throw ModelUnavailableException("当前安装尚未配置 Agent 工具运行时")
            catalog.requireModel(session.model, needsVision = false, needsTools = true)
        }
        store.update(id) { latest ->
            if (expectedSettings != null && latest.agentSettings != expectedSettings) {
                throw com.tongxie.copilotgo.data.storage.SessionConflictException()
            }
            latest.copy(agentSettings = settings)
        }
    }

    fun respondToApproval(
        id: String,
        binding: AgentApprovalBinding,
        decision: AgentApprovalDecision
    ): AgentApprovalResponse = synchronized(guard) {
        val ticket = slots[id]?.ticket
        val pending = ticket?.pendingApproval
        when {
            ticket == null || pending == null || ticket.stopRequested ||
                binding != pending.request.binding || ticket.agentRunId != binding.runId ->
                AgentApprovalResponse.Rejected("此审批已失效，请查看当前工具请求")
            ticket.accountGeneration != chatClient.accountGeneration.value ||
                binding.accountGeneration != ticket.accountGeneration ->
                AgentApprovalResponse.Rejected("登录状态已更改，此审批已失效")
            System.currentTimeMillis() >= pending.request.expiresAt ->
                AgentApprovalResponse.Rejected("此审批已过期，工具不会执行")
            agentRunner?.isApprovalCurrent(binding) != true ->
                AgentApprovalResponse.Rejected("工具配置已更改，请重新发起请求")
            !pending.decision.complete(decision) ->
                AgentApprovalResponse.Rejected("此审批已处理，不能重复提交")
            else -> AgentApprovalResponse.Accepted
        }
    }

    private fun agentCallbacks(id: String, assistantId: String, slot: Slot, ticket: Ticket) =
        object : AgentRunCallbacks {
            override fun ensureActive() {
                synchronized(guard) {
                    if (slot.ticket !== ticket || ticket.stopRequested ||
                        ticket.accountGeneration != chatClient.accountGeneration.value
                    ) throw CancellationException("Agent session operation superseded")
                }
            }

            override suspend fun publish(run: AgentRunRecord, content: String, durable: Boolean) {
                if (run.id != ticket.agentRunId || run.accountGeneration != ticket.accountGeneration) {
                    throw StreamProtocolException("Agent 运行标识不一致")
                }
                if (!run.status.isTerminal) ensureActive()
                synchronized(guard) {
                    val pending = ticket.pendingApproval
                    val next = run.pendingApproval
                    if (next?.binding != pending?.request?.binding) {
                        pending?.decision?.cancel()
                        ticket.pendingApproval = next?.let(::PendingApproval)
                    }
                }
                store.update(id, persist = durable) { latest ->
                    if (latest.messages.none { it.id == assistantId && it.agentRun?.id == run.id }) {
                        throw com.tongxie.copilotgo.data.storage.SessionConflictException()
                    }
                    latest.copy(messages = latest.messages.map {
                        if (it.id == assistantId) it.copy(content = content, agentRun = run.detached()) else it
                    }.toMutableList())
                }
                slot.notice.value = run.notice
            }

            override suspend fun awaitApproval(request: AgentApprovalRequest): AgentApprovalDecision {
                ensureActive()
                val pending = synchronized(guard) {
                    ticket.pendingApproval?.takeIf { it.request.binding == request.binding }
                } ?: throw CancellationException("Approval is no longer active")
                return pending.decision.await()
            }
        }

    private fun requireSafeReplay(messages: List<UiMessage>) {
        if (messages.any { it.agentRun?.safeToRetry == false }) {
            throw ModelUnavailableException("此轮包含已执行或结果未知的远端操作，不能自动重放；请发送新消息并逐项确认")
        }
    }

    private fun AgentRunStatus.finishReason(): String = when (this) {
        AgentRunStatus.COMPLETED -> "stop"
        AgentRunStatus.LIMIT_REACHED -> "length"
        AgentRunStatus.CANCELLED -> "cancelled"
        AgentRunStatus.INTERRUPTED -> "interrupted"
        else -> "error"
    }

    private suspend fun mutate(id: String, action: suspend () -> Unit): OperationResult {
        val (slot, ticket) = reserve(id) ?: return OperationResult.Rejected("此会话正在处理请求，请先停止")
        val receipt = CompletableDeferred<OperationResult>()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                action()
                receipt.complete(OperationResult.Accepted)
            } catch (e: CancellationException) {
                receipt.complete(OperationResult.Rejected("操作已取消"))
                throw e
            } catch (e: Exception) {
                val message = friendlyError(e)
                slot.error.value = message
                receipt.complete(OperationResult.Rejected(message))
            }
        }
        startJob(id, slot, ticket, job) { receipt.complete(OperationResult.Rejected("操作已取消")) }
        return receipt.await()
    }

    private fun SendResult.toOperation(): OperationResult = when (this) {
        is SendResult.Accepted -> OperationResult.Accepted
        is SendResult.Rejected -> OperationResult.Rejected(message)
    }

    private fun trimSlots() {
        val inactive = slots.filterValues {
            it.ticket == null && it.owners == 0 && it.sending.subscriptionCount.value == 0 &&
                it.error.subscriptionCount.value == 0 && it.notice.subscriptionCount.value == 0
        }
        inactive.keys.take((inactive.size - maxInactiveSessions).coerceAtLeast(0)).forEach { slots.remove(it) }
    }

    private fun safeTitle(text: String): String {
        val prefix = text.take(30)
        return if (prefix.lastOrNull()?.isHighSurrogate() == true) prefix.dropLast(1) else prefix
    }

    private fun friendlyError(error: Exception): String = when (error) {
        is AttachmentImportException -> error.userMessage
        is SessionStorageException -> error.userMessage
        is ModelUnavailableException -> error.message ?: "模型暂不可用"
        is AgentContextLimitException -> error.message ?: "Agent 上下文超过限制"
        is AgentToolConfigurationChangedException -> "工具配置已更改，请重新发送；草稿已保留"
        is AgentToolException -> error.userMessage
        is StreamProtocolException -> error.message ?: "响应中断，请重试"
        else -> networkErrorMessage(error)
    }

    internal val cachedSlotCount: Int get() = synchronized(guard) { slots.size }

    fun close() {
        store.removeDeletionListener(deletionListener)
        scope.cancel()
    }
}
