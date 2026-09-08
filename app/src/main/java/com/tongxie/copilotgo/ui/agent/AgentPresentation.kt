package com.tongxie.copilotgo.ui.agent

import androidx.annotation.StringRes
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.agent.AgentApprovalRequest
import com.tongxie.copilotgo.data.agent.AgentRunRecord
import com.tongxie.copilotgo.data.agent.AgentRunStatus
import com.tongxie.copilotgo.data.agent.AgentToolCallRecord
import com.tongxie.copilotgo.data.agent.AgentToolCallStatus
import com.tongxie.copilotgo.data.agent.AgentToolKind
import com.tongxie.copilotgo.data.chat.ModelInfo
import com.tongxie.copilotgo.data.chat.UiMessage

internal object AgentTags {
    const val MODE = "agent-mode"
    const val ACTIVITY = "agent-activity"
    const val TOOLBAR_ACTIVITY = "agent-activity-toolbar"
    const val REVIEW = "agent-review"
    const val APPROVE = "agent-approve"
    const val DENY = "agent-deny"
    const val STOP = "agent-stop"
    const val ARGUMENTS = "agent-arguments"
    const val SOURCES = "agent-sources"
    const val DETAILS = "agent-details"
}

internal const val MAX_DISPLAYED_AGENT_CALLS = 24
internal const val MAX_DISPLAYED_AGENT_SOURCES = 64
internal const val MAX_REVIEW_ARGUMENT_CHARACTERS = 96 * 1024

internal fun displayedAgentCalls(run: AgentRunRecord): List<AgentToolCallRecord> =
    run.steps.take(12).flatMap { it.toolCalls.take(MAX_DISPLAYED_AGENT_CALLS) }
        .take(MAX_DISPLAYED_AGENT_CALLS)

internal fun attentionAgentCalls(run: AgentRunRecord): List<AgentToolCallRecord> =
    displayedAgentCalls(run).filter { call ->
        call.outcomeUnknown || call.result?.outcomeUnknown == true || call.result?.isError == true ||
            call.status in setOf(
                AgentToolCallStatus.AWAITING_APPROVAL, AgentToolCallStatus.FAILED,
                AgentToolCallStatus.DENIED, AgentToolCallStatus.INVALIDATED,
                AgentToolCallStatus.INTERRUPTED, AgentToolCallStatus.CANCELLED
            )
    }

@StringRes
internal fun agentModelDisabledReason(model: ModelInfo?): Int? = when {
    model == null -> R.string.agent_model_missing
    !model.chatCompatible -> R.string.agent_model_incompatible
    !model.supportsTools -> R.string.agent_model_no_tools
    else -> null
}

@StringRes
internal fun agentRunLabel(run: AgentRunRecord): Int = when (run.status) {
    AgentRunStatus.AWAITING_APPROVAL -> R.string.agent_waiting_approval
    AgentRunStatus.COMPLETED -> R.string.agent_completed
    AgentRunStatus.CANCELLED -> R.string.agent_cancelled
    AgentRunStatus.INTERRUPTED -> R.string.agent_interrupted
    AgentRunStatus.FAILED -> R.string.agent_failed
    AgentRunStatus.LIMIT_REACHED -> R.string.agent_limit_reached
    AgentRunStatus.RUNNING -> when (
        displayedAgentCalls(run).lastOrNull { it.status == AgentToolCallStatus.RUNNING }?.kind
    ) {
        AgentToolKind.PUBLIC_WEB_SEARCH -> R.string.agent_searching
        AgentToolKind.PUBLIC_WEB_READ -> R.string.agent_reading
        AgentToolKind.MCP -> R.string.agent_calling_tool
        null -> R.string.agent_generating
    }
}

@StringRes
internal fun agentCallLabel(call: AgentToolCallRecord): Int {
    if (call.outcomeUnknown || call.result?.outcomeUnknown == true) return R.string.agent_outcome_unknown
    return when (call.status) {
        AgentToolCallStatus.PROPOSED -> R.string.agent_call_proposed
        AgentToolCallStatus.AWAITING_APPROVAL -> R.string.agent_waiting_approval
        AgentToolCallStatus.RUNNING -> when (call.kind) {
            AgentToolKind.PUBLIC_WEB_SEARCH -> R.string.agent_searching
            AgentToolKind.PUBLIC_WEB_READ -> R.string.agent_reading
            AgentToolKind.MCP -> R.string.agent_calling_tool
        }
        AgentToolCallStatus.SUCCEEDED -> if (call.result?.isError == true) R.string.agent_call_failed
        else R.string.agent_call_succeeded
        AgentToolCallStatus.FAILED -> R.string.agent_call_failed
        AgentToolCallStatus.DENIED -> R.string.agent_call_denied
        AgentToolCallStatus.INVALIDATED -> R.string.agent_call_invalidated
        AgentToolCallStatus.INTERRUPTED -> R.string.agent_interrupted
        AgentToolCallStatus.CANCELLED -> R.string.agent_cancelled
    }
}

internal fun approvalMatchesRun(request: AgentApprovalRequest, run: AgentRunRecord): Boolean =
    run.status == AgentRunStatus.AWAITING_APPROVAL &&
        request.binding.runId == run.id &&
        request.binding.accountGeneration == run.accountGeneration &&
        run.pendingApproval == request

internal fun approvalCanBeAnswered(
    request: AgentApprovalRequest,
    run: AgentRunRecord,
    nowMillis: Long
): Boolean = approvalMatchesRun(request, run) && request.expiresAt > nowMillis &&
    request.expiresAt > request.createdAt

internal fun blockedAgentReplayMessageIds(messages: List<UiMessage>): Set<String> = buildSet {
    var protectedRun = false
    for (message in messages.asReversed()) {
        if (message.agentRun?.safeToRetry == false) protectedRun = true
        if (protectedRun) add(message.id)
    }
}

internal fun agentDurationSeconds(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).toString()
    val remainder = milliseconds % 1_000
    return if (remainder == 0L) seconds
    else "$seconds.${remainder.toString().padStart(3, '0').trimEnd('0')}"
}
