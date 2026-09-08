package com.tongxie.copilotgo.ui.agent

import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.agent.AgentApprovalBinding
import com.tongxie.copilotgo.data.agent.AgentApprovalRequest
import com.tongxie.copilotgo.data.agent.AgentRunRecord
import com.tongxie.copilotgo.data.agent.AgentRunStatus
import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import com.tongxie.copilotgo.data.agent.AgentStepRecord
import com.tongxie.copilotgo.data.agent.AgentToolCallRecord
import com.tongxie.copilotgo.data.agent.AgentToolCallStatus
import com.tongxie.copilotgo.data.agent.AgentToolIdentity
import com.tongxie.copilotgo.data.agent.AgentToolKind
import com.tongxie.copilotgo.data.agent.AgentToolResult
import com.tongxie.copilotgo.data.chat.ModelCapabilities
import com.tongxie.copilotgo.data.chat.ModelInfo
import com.tongxie.copilotgo.data.chat.ModelSupports
import com.tongxie.copilotgo.data.chat.UiMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPresentationTest {
    private val tool = AgentToolIdentity("fixture-server", 2, "fixture_read", "definition-digest")
    private val binding = AgentApprovalBinding("approval-1", "run-1", 7, "call-1", tool, "arguments-digest", "descriptor-digest")
    private val request = AgentApprovalRequest(
        binding, "fixture_read", "https://example.com/mcp",
        buildJsonObject { put("query", "controlled fixture") }, 100, 1_000
    )
    private val run = AgentRunRecord("run-1", 7, AgentRunStatus.AWAITING_APPROVAL, pendingApproval = request)

    @Test fun modeStartsAsOrdinaryChatAndRequiresDeclaredCompatibleTools() {
        assertFalse(AgentSessionSettings().enabled)
        assertFalse(AgentSessionSettings().autoApprovePublicWebReads)
        assertEquals(R.string.agent_model_missing, agentModelDisabledReason(null))
        assertEquals(R.string.agent_model_no_tools, agentModelDisabledReason(ModelInfo("fixture")))
        val capable = ModelInfo("fixture", capabilities = ModelCapabilities(supports = ModelSupports(toolCalls = true)))
        assertNull(agentModelDisabledReason(capable))
        assertEquals(
            R.string.agent_model_incompatible,
            agentModelDisabledReason(capable.copy(supportedEndpoints = listOf("/responses")))
        )
    }

    @Test fun approvalsRequireTheExactRunAccountCallConfigAndArgumentsSnapshot() {
        assertTrue(approvalCanBeAnswered(request, run, 999))
        val replacements = listOf(
            request.copy(binding = binding.copy(approvalId = "approval-2")),
            request.copy(binding = binding.copy(runId = "run-2")),
            request.copy(binding = binding.copy(accountGeneration = 8)),
            request.copy(binding = binding.copy(callId = "call-2")),
            request.copy(binding = binding.copy(tool = tool.copy(configRevision = 3))),
            request.copy(binding = binding.copy(argumentsDigest = "changed")),
            request.copy(binding = binding.copy(descriptorDigest = "changed")),
            request.copy(arguments = buildJsonObject { put("query", "changed arguments") }),
            request.copy(destination = "https://different.example/mcp")
        )
        replacements.forEach { changed ->
            assertFalse(approvalCanBeAnswered(request, run.copy(pendingApproval = changed), 999))
        }
        assertFalse(approvalCanBeAnswered(request, run.copy(accountGeneration = 8), 999))
        assertFalse(approvalCanBeAnswered(request, run.copy(id = "different-run"), 999))
    }

    @Test fun expiredDeniedOrInterruptedApprovalCannotBeAnswered() {
        assertFalse(approvalCanBeAnswered(request, run, 1_000))
        assertFalse(approvalCanBeAnswered(request, run.copy(pendingApproval = null), 200))
        assertFalse(approvalCanBeAnswered(request, run.copy(status = AgentRunStatus.INTERRUPTED), 200))
        assertFalse(approvalCanBeAnswered(request, run.copy(status = AgentRunStatus.CANCELLED), 200))
        assertFalse(approvalCanBeAnswered(request, run.copy(status = AgentRunStatus.COMPLETED), 200))
    }

    @Test fun startedMcpActionsProtectTheirAncestorsButNotLaterOrdinaryMessages() {
        val action = AgentToolCallRecord(
            "call", "fixture_write", kind = AgentToolKind.MCP,
            status = AgentToolCallStatus.SUCCEEDED, startedAt = 1
        )
        val protectedRun = AgentRunRecord(
            "run", 7, AgentRunStatus.COMPLETED, steps = listOf(AgentStepRecord(0, toolCalls = listOf(action)))
        )
        val messages = listOf(
            UiMessage("earlier", "assistant", "earlier ordinary response"),
            UiMessage("user", "user", "controlled request"),
            UiMessage("agent", "assistant", "completed", agentRun = protectedRun),
            UiMessage("later", "assistant", "a later ordinary response")
        )
        assertEquals(setOf("earlier", "user", "agent"), blockedAgentReplayMessageIds(messages))
        assertTrue(blockedAgentReplayMessageIds(messages.map { it.copy(agentRun = null) }).isEmpty())
    }

    @Test fun visibleProgressDistinguishesSearchReadDenialAndUnknown() {
        val call = AgentToolCallRecord(
            "call", "fixture", kind = AgentToolKind.PUBLIC_WEB_SEARCH, status = AgentToolCallStatus.RUNNING
        )
        assertEquals(R.string.agent_searching, agentCallLabel(call))
        assertEquals(R.string.agent_reading, agentCallLabel(call.copy(kind = AgentToolKind.PUBLIC_WEB_READ)))
        assertEquals(R.string.agent_call_denied, agentCallLabel(call.copy(status = AgentToolCallStatus.DENIED)))
        assertEquals(R.string.agent_outcome_unknown, agentCallLabel(call.copy(outcomeUnknown = true)))
        assertEquals(R.string.agent_call_failed, agentCallLabel(call.copy(
            status = AgentToolCallStatus.SUCCEEDED, result = AgentToolResult("controlled error", isError = true)
        )))
        assertEquals(R.string.agent_call_denied, agentCallLabel(call.copy(
            status = AgentToolCallStatus.DENIED, result = AgentToolResult("controlled denial", isError = true)
        )))
        assertEquals("0.001", agentDurationSeconds(1))
        assertEquals("1.25", agentDurationSeconds(1_250))
        assertEquals("180", agentDurationSeconds(180_000))
    }

    @Test fun quietDisclosureNeverHidesApprovalFailureOrUnknownCalls() {
        val successful = AgentToolCallRecord(
            "ok", "fixture_read", kind = AgentToolKind.MCP, status = AgentToolCallStatus.SUCCEEDED
        )
        val pending = successful.copy(id = "pending", status = AgentToolCallStatus.AWAITING_APPROVAL)
        val denied = successful.copy(id = "denied", status = AgentToolCallStatus.DENIED)
        val failed = successful.copy(id = "failed", status = AgentToolCallStatus.FAILED)
        val invalidated = successful.copy(id = "invalidated", status = AgentToolCallStatus.INVALIDATED)
        val unknown = successful.copy(id = "unknown", outcomeUnknown = true)
        val errorResult = successful.copy(id = "error", result = AgentToolResult("fixture error", isError = true))
        val unknownResult = successful.copy(
            id = "unknown-result", result = AgentToolResult("fixture uncertain", outcomeUnknown = true)
        )
        val attention = listOf(pending, denied, failed, invalidated, unknown, errorResult, unknownResult)
        val record = run.copy(steps = listOf(AgentStepRecord(
            0, toolCalls = attention + successful + successful.copy(id = "running", status = AgentToolCallStatus.RUNNING)
        )))
        assertEquals(attention, attentionAgentCalls(record))
        assertEquals(attention.size + 2, displayedAgentCalls(record).size)
    }
}
