package com.tongxie.copilotgo.data.agent

import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.UiMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AgentContractsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun oldSessionsHaveNoAgentExecutionOrConsent() {
        val session = json.decodeFromString<Session>(
            """{"id":"old","title":"old","model":"model","messages":[{"id":"u","role":"user","content":"old text"}]}"""
        )
        assertFalse(session.agentSettings.enabled)
        assertFalse(session.agentSettings.autoApprovePublicWebReads)
        assertNull(session.messages.single().agentRun)
    }

    @Test
    fun activityAndActualSourceProvenanceRoundTrip() {
        val identity = AgentToolIdentity("search", 7, "search", "definition-hash")
        val source = SourceReference("https://example.org/page", "Actual page", SourceKind.SEARCH_HIT, "S1", "c1")
        val call = AgentToolCallRecord(
            "c1", "search", identity, "https://example.org", AgentToolKind.PUBLIC_WEB_SEARCH,
            JsonObject(mapOf("query" to JsonPrimitive("fixture"))), "arguments-hash",
            AgentToolCallStatus.SUCCEEDED, AgentToolResult("actual result", sources = listOf(source)),
            startedAt = 100, finishedAt = 200
        )
        val run = AgentRunRecord(
            "run", 3, AgentRunStatus.COMPLETED, steps = listOf(AgentStepRecord(0, toolCalls = listOf(call))),
            sources = listOf(source)
        )
        val message = UiMessage("a", "assistant", "answer [S1]", agentRun = run)
        assertEquals(message, json.decodeFromString<UiMessage>(json.encodeToString(message)))
        assertTrue(run.safeToRetry)
        assertFalse(run.copy(steps = listOf(AgentStepRecord(0, toolCalls = listOf(call.copy(
            kind = AgentToolKind.MCP
        ))))).safeToRetry)
    }

    @Test
    fun modelDefinitionsDoNotContainRoutingOrIdentityFields() {
        val descriptor = AgentToolDescriptor(
            AgentToolIdentity("private-config-id", 1, "remote-name", "hash"),
            "mcp_example_read", "Untrusted description",
            JsonObject(mapOf("type" to JsonPrimitive("object"))), "https://example.org/mcp"
        )
        val encoded = json.encodeToString(descriptor.modelDefinition())
        assertTrue(encoded.contains("\"parameters\""))
        assertFalse(encoded.contains("private-config-id"))
        assertFalse(encoded.contains("https://example.org"))
        assertFalse(encoded.contains("configRevision"))
    }

    @Test
    fun approvalEqualityBindsArgumentsAccountAndConfiguration() {
        val binding = AgentApprovalBinding(
            "approval", "run", 1, "call", AgentToolIdentity("config", 1, "tool", "hash"),
            "args", "descriptor"
        )
        assertNotEquals(binding, binding.copy(accountGeneration = 2))
        assertNotEquals(binding, binding.copy(argumentsDigest = "changed"))
        assertNotEquals(binding, binding.copy(tool = binding.tool.copy(configRevision = 2)))
        assertNotEquals(binding, binding.copy(descriptorDigest = "changed"))
    }
}
