package com.tongxie.copilotgo.data.agent

import com.tongxie.copilotgo.data.chat.ModelInfo
import com.tongxie.copilotgo.data.chat.UiMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Defaults: 6 model rounds, 12 calls, 180 seconds overall, 30 seconds per tool,
 * 60 seconds per approval, 16 KiB arguments, 32 KiB per result/128 KiB aggregate,
 * 96 KiB complete wire context and 32 tools/32 KiB definitions.
 * Sequential execution is deliberate: one external action and one approval at a time.
 * The wire-byte context budget includes images; large images may require ordinary vision chat.
 */
@Serializable
data class AgentLimits(
    val maxSteps: Int = 6,
    val maxToolCalls: Int = 12,
    val maxDurationMillis: Long = 180_000,
    val toolTimeoutMillis: Long = 30_000,
    val approvalTimeoutMillis: Long = 60_000,
    val maxArgumentBytes: Int = 16 * 1024,
    val maxResultBytes: Int = 32 * 1024,
    val maxTotalResultBytes: Int = 128 * 1024,
    val maxContextBytes: Int = 96 * 1024,
    val maxTools: Int = 32,
    val maxToolDefinitionBytes: Int = 32 * 1024
) {
    init {
        require(maxSteps in 1..12 && maxToolCalls in 1..24)
        require(maxDurationMillis in 1..600_000 && toolTimeoutMillis in 1..60_000)
        require(approvalTimeoutMillis in 1..300_000)
        require(maxArgumentBytes in 1..64 * 1024 && maxResultBytes in 1..128 * 1024)
        require(maxTotalResultBytes in 1..512 * 1024 && maxContextBytes in 1..128_000)
        require(maxTools in 1..64 && maxToolDefinitionBytes in 1..64 * 1024)
    }
}

@Serializable
data class AgentSessionSettings(
    val enabled: Boolean = false,
    /** Explicit user opt-in; does not authorize arbitrary MCP tools. */
    val autoApprovePublicWebReads: Boolean = false,
    val limits: AgentLimits = AgentLimits()
)

@Serializable
enum class AgentRunStatus {
    RUNNING, AWAITING_APPROVAL, COMPLETED, CANCELLED, INTERRUPTED, FAILED, LIMIT_REACHED;

    val isTerminal: Boolean get() = this != RUNNING && this != AWAITING_APPROVAL
}

@Serializable
enum class AgentToolCallStatus {
    PROPOSED, AWAITING_APPROVAL, RUNNING, SUCCEEDED, FAILED, DENIED, INVALIDATED,
    INTERRUPTED, CANCELLED
}

@Serializable
data class AgentApprovalBinding(
    val approvalId: String,
    val runId: String,
    val accountGeneration: Long,
    val callId: String,
    val tool: AgentToolIdentity,
    val argumentsDigest: String,
    /** Includes safe destination, name, kind, description, schema and tool identity. */
    val descriptorDigest: String
)

@Serializable
data class AgentApprovalRequest(
    val binding: AgentApprovalBinding,
    val toolName: String,
    val destination: String,
    val arguments: JsonObject,
    val createdAt: Long,
    val expiresAt: Long
)

enum class AgentApprovalDecision { APPROVE, DENY }

sealed interface AgentApprovalResponse {
    data object Accepted : AgentApprovalResponse
    data class Rejected(val message: String) : AgentApprovalResponse
}

@Serializable
data class AgentToolCallRecord(
    val id: String,
    val name: String,
    val identity: AgentToolIdentity? = null,
    val destination: String = "",
    val kind: AgentToolKind = AgentToolKind.MCP,
    val arguments: JsonObject? = null,
    val argumentsDigest: String? = null,
    val status: AgentToolCallStatus = AgentToolCallStatus.PROPOSED,
    val result: AgentToolResult? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val outcomeUnknown: Boolean = false
)

@Serializable
data class AgentStepRecord(
    val index: Int,
    val assistantText: String = "",
    val toolCalls: List<AgentToolCallRecord> = emptyList(),
    val finishReason: String? = null
)

@Serializable
data class AgentRunRecord(
    val id: String,
    val accountGeneration: Long,
    val status: AgentRunStatus = AgentRunStatus.RUNNING,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val steps: List<AgentStepRecord> = emptyList(),
    val pendingApproval: AgentApprovalRequest? = null,
    val sources: List<SourceReference> = emptyList(),
    val notice: String? = null
) {
    /** Ordinary retry/regenerate must not repeat potentially mutating remote actions. */
    val safeToRetry: Boolean
        get() = status.isTerminal && steps.flatMap { it.toolCalls }.none {
            it.outcomeUnknown || it.result?.outcomeUnknown == true ||
                (it.kind == AgentToolKind.MCP && it.startedAt != null)
        }
}

data class AgentRunInput(
    val runId: String,
    val sessionId: String,
    val accountGeneration: Long,
    val model: ModelInfo,
    val history: List<UiMessage>,
    val settings: AgentSessionSettings,
    val startedAt: Long = System.currentTimeMillis()
)

interface AgentRunCallbacks {
    /** Durable publishes must complete before any proposed external action can start. */
    suspend fun publish(run: AgentRunRecord, content: String, durable: Boolean = true)
    suspend fun awaitApproval(request: AgentApprovalRequest): AgentApprovalDecision
    /** Checks the existing center ticket and account generation, not a new job registry. */
    fun ensureActive()
}

fun interface AgentRunner {
    suspend fun run(input: AgentRunInput, callbacks: AgentRunCallbacks): AgentRunRecord

    /** Fail closed for adapters that cannot prove the proposal still targets the same tool. */
    fun isApprovalCurrent(binding: AgentApprovalBinding): Boolean = false
}
