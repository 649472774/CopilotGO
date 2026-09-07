package com.tongxie.copilotgo.data.agent

import com.tongxie.copilotgo.data.chat.ModelCapabilities
import com.tongxie.copilotgo.data.chat.ModelInfo
import com.tongxie.copilotgo.data.chat.ModelSupports
import com.tongxie.copilotgo.data.chat.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

internal val agentTestModel = ModelInfo(
    "fixture-chat", capabilities = ModelCapabilities(type = "chat", supports = ModelSupports(toolCalls = true))
)

internal fun agentInput(limits: AgentLimits = AgentLimits(), autoApprove: Boolean = false) = AgentRunInput(
    "fixture-run", "fixture-session", 0, agentTestModel,
    listOf(UiMessage("user", "user", "Find the fixture")),
    AgentSessionSettings(enabled = true, autoApprovePublicWebReads = autoApprove, limits = limits)
)

internal fun proposal(id: String = "call-1", name: String = "fixture_search", arguments: String = """{"query":"fixture"}""") =
    AgentStreamEvent.Completed("tool_calls", listOf(AgentToolCall(id, AgentFunctionCall(name, arguments))))

internal class AgentTestModel : AgentModelTransport {
    val requests = CopyOnWriteArrayList<AgentChatRequest>()
    val responses = LinkedBlockingQueue<Flow<AgentStreamEvent>>()

    override fun stream(request: AgentChatRequest): Flow<AgentStreamEvent> {
        requests.add(request)
        return responses.poll() ?: error("No fixture model response")
    }

    fun enqueue(vararg events: AgentStreamEvent) { responses.add(flowOf(*events)) }
    fun answer(text: String = "Fixture answer [S1]") {
        enqueue(AgentStreamEvent.TextDelta(text), AgentStreamEvent.Completed("stop"))
    }
}

internal class AgentTestExecutor(kind: AgentToolKind = AgentToolKind.MCP) : AgentToolExecutor {
    override val revision = MutableStateFlow(0L)
    var descriptor = AgentToolDescriptor(
        AgentToolIdentity("fixture-config", 0, "search", "fixture-definition"),
        "fixture_search", "Fixture tool, not a production validator",
        Json.parseToJsonElement(
            """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"],"additionalProperties":false}"""
        ) as JsonObject,
        "https://example.org/mcp", kind
    )
    val invocations = CopyOnWriteArrayList<AgentToolInvocation>()
    var validateAction: suspend (AgentToolInvocation) -> AgentToolValidation = { call ->
        if (call.arguments.keys != setOf("query") || call.arguments["query"]?.jsonPrimitive?.isString != true) {
            throw AgentToolException("Fixture schema rejected the arguments")
        }
        AgentToolValidation(call.arguments)
    }
    var executeAction: suspend (AgentToolInvocation) -> AgentToolResult = {
        AgentToolResult(
            "Actual fixture output",
            sources = listOf(SourceReference(
                "https://example.org/actual", "Actual fixture source", SourceKind.SEARCH_HIT
            ))
        )
    }

    override suspend fun snapshot() = AgentToolSnapshot(revision.value, listOf(descriptor))
    override fun isCurrent(identity: AgentToolIdentity) =
        identity == descriptor.identity && identity.configRevision == revision.value
    override suspend fun validate(invocation: AgentToolInvocation) = validateAction(invocation)
    override suspend fun execute(invocation: AgentToolInvocation): AgentToolResult {
        if (!isCurrent(invocation.tool.identity)) throw AgentToolConfigurationChangedException()
        invocations.add(invocation)
        return executeAction(invocation)
    }

    fun invalidate() {
        descriptor = descriptor.copy(identity = descriptor.identity.copy(configRevision = revision.value + 1))
        revision.value += 1
    }
}

internal data class PublishedRun(val run: AgentRunRecord, val content: String, val durable: Boolean)

internal class AgentTestCallbacks : AgentRunCallbacks {
    val updates = CopyOnWriteArrayList<PublishedRun>()
    val approvals = CopyOnWriteArrayList<AgentApprovalRequest>()
    var active = true
    var approve: suspend (AgentApprovalRequest) -> AgentApprovalDecision = { AgentApprovalDecision.APPROVE }
    var onPublish: suspend (PublishedRun) -> Unit = {}

    override suspend fun publish(run: AgentRunRecord, content: String, durable: Boolean) {
        val update = PublishedRun(run, content, durable)
        updates.add(update)
        onPublish(update)
    }

    override suspend fun awaitApproval(request: AgentApprovalRequest): AgentApprovalDecision {
        approvals.add(request)
        return approve(request)
    }

    override fun ensureActive() {
        if (!active) throw CancellationException("Fixture account changed")
    }
}
