package com.tongxie.copilotgo.data.agent

import com.tongxie.copilotgo.data.chat.ModelLimits
import com.tongxie.copilotgo.data.chat.PromptBuilder
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.data.storage.AppPaths
import com.tongxie.copilotgo.data.storage.AttachmentStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AgentPromptBuilderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val args = Json.parseToJsonElement("""{"query":"fixture"}""") as JsonObject
    private fun completedStep(index: Int = 0, result: String = "actual result") = AgentStepRecord(
        index, "Looking up",
        listOf("first-$index", "second-$index").map {
            AgentToolCallRecord(
                it, "fixture_search", arguments = args, status = AgentToolCallStatus.SUCCEEDED,
                result = AgentToolResult(result), startedAt = 1, finishedAt = 2
            )
        }, "tool_calls"
    )
    private fun builder() = AgentPromptBuilder(AttachmentStore(AppPaths(temporary.root)))

    @Test
    fun responsesHistoryPreservesWholeToolResultsWithoutReplayingMissingOpaqueState() = runBlocking {
        val oldRun = AgentRunRecord(
            "old-run", 0, AgentRunStatus.COMPLETED,
            steps = listOf(completedStep(result = "retained historical result"), AgentStepRecord(1, "old final"))
        )
        val prompt = builder().prepare(
            listOf(
                UiMessage("old-user", "user", "old question"),
                UiMessage("old-answer", "assistant", "old final", agentRun = oldRun),
                UiMessage("current", "user", "new question")
            ),
            agentTestModel.copy(supportedEndpoints = listOf("/responses")),
            emptyList(), listOf(completedStep(2))
        )
        val historical = prompt.request.messages.filter { it.content.toString().contains("retained historical result") }
        assertEquals(1, historical.size)
        assertEquals("assistant", historical.single().role)
        assertTrue(historical.single().content.toString().contains("untrusted records"))
        assertTrue(historical.single().content.toString().contains("SUCCEEDED"))
        assertNull(historical.single().toolCalls)
        assertTrue(historical.single().responsesOutput.isEmpty())
        assertEquals(
            listOf("first-2", "second-2"),
            prompt.request.messages.filter { it.role == "tool" }.map { it.toolCallId }
        )
        assertTrue(prompt.request.messages.any { it.content.toString().contains("old final") })
    }

    @Test
    fun responsesOriginalArgumentsCannotReuseAnotherApprovalDigest() = runBlocking {
        val step = completedStep().let {
            it.copy(toolCalls = listOf(it.toolCalls.first().copy(
                argumentsDigest = AgentValues.digest(AgentValues.canonical(args))
            )))
        }
        val changed = Json.parseToJsonElement(
            """{"type":"function_call","id":"fc_changed","call_id":"first-0","name":"fixture_search","arguments":"{\"query\":\"changed\"}","status":"completed"}"""
        ) as JsonObject
        try {
            builder().prepare(
                listOf(UiMessage("u", "user", "question")),
                agentTestModel.copy(supportedEndpoints = listOf("/responses")),
                emptyList(), listOf(step), responsesOutput = mapOf(0 to listOf(changed))
            )
            fail("Changed original arguments cannot be substituted for the approved call")
        } catch (_: AgentContextLimitException) { }
    }

    @Test
    fun liveQuestionsReceiveDeviceDateAndRequireSearchRatherThanTrainingMemory() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-09-18T06:00:00Z"), ZoneId.of("Asia/Shanghai"))
        val prompt = AgentPromptBuilder(AttachmentStore(AppPaths(temporary.root)), clock = clock).prepare(
            listOf(UiMessage("u", "user", "今天微软股价多少")),
            agentTestModel, listOf(AgentTestExecutor().descriptor.modelDefinition()), requireWebSearch = true
        )
        val policy = prompt.request.messages.first().content.toString()
        assertTrue(policy.contains("2026-09-18T14:00+08:00[Asia/Shanghai]"))
        assertTrue(policy.contains("MUST call"))
        assertTrue(policy.contains("NOT a quote timestamp"))
        assertTrue(policy.contains("not a local/offline model"))
        assertEquals("required", prompt.request.toolChoice)
    }

    @Test
    fun assistantBatchAlwaysHasEveryMatchingToolResultInOrder() = runBlocking {
        val prompt = builder().prepare(
            listOf(UiMessage("u", "user", "question")), agentTestModel, emptyList(), listOf(completedStep())
        )
        val messages = prompt.request.messages
        assertEquals(listOf("system", "user", "assistant", "tool", "tool"), messages.map { it.role })
        assertEquals(messages[2].toolCalls!!.map { it.id }, messages.takeLast(2).map { it.toolCallId })
        assertTrue(messages.takeLast(2).all { it.content.toString().contains("actual result") })
    }

    @Test
    fun definitionBytesAndToolResultsCountAgainstTheSameBudget() = runBlocking {
        val tools = listOf(AgentTestExecutor().descriptor.modelDefinition())
        val model = agentTestModel.copy(capabilities = agentTestModel.capabilities!!.copy(
            limits = ModelLimits(maxPromptTokens = 2500)
        ))
        try {
            builder().prepare(
                listOf(UiMessage("u", "user", "question")), model, tools,
                listOf(completedStep(result = "large".repeat(500)))
            )
            fail("Partial tool-result trimming must not be used to fit the current turn")
        } catch (_: AgentContextLimitException) { }
    }

    @Test
    fun droppingOlderHistoryNeverOrphansOneHalfOfAToolBatch() = runBlocking {
        val oldRun = AgentRunRecord(
            "old-run", 0, AgentRunStatus.COMPLETED,
            steps = listOf(completedStep(result = "large".repeat(5000)), AgentStepRecord(1, "old final", finishReason = "stop"))
        )
        val prompt = builder().prepare(
            listOf(
                UiMessage("old-user", "user", "old question"),
                UiMessage("old-answer", "assistant", "old final", agentRun = oldRun),
                UiMessage("current", "user", "new question")
            ), agentTestModel, emptyList(), listOf(completedStep(2)),
            AgentLimits(maxContextBytes = 6000)
        )
        assertTrue(prompt.truncated)
        assertEquals(listOf("first-2", "second-2"), prompt.request.messages.filter { it.role == "tool" }.map { it.toolCallId })
        assertFalse(prompt.request.messages.any { it.content.toString().contains("old question") })
        assertEquals(1, prompt.request.messages.count { it.toolCalls != null })
    }

    @Test
    fun aPendingOrMissingResultCannotBePresentedAsCompleteHistory() = runBlocking {
        val pending = completedStep().let { step ->
            step.copy(toolCalls = step.toolCalls.map {
                it.copy(status = AgentToolCallStatus.AWAITING_APPROVAL, result = null)
            })
        }
        try {
            builder().prepare(listOf(UiMessage("u", "user", "question")), agentTestModel, emptyList(), listOf(pending))
            fail("Pending results must not be synthesized as success")
        } catch (_: AgentContextLimitException) { }
    }

    @Test
    fun reusedProviderCallIdsInLaterRunsHaveDistinctButCoherentWireIdentities() = runBlocking {
        val oldRun = AgentRunRecord(
            "old-run", 0, AgentRunStatus.COMPLETED,
            steps = listOf(completedStep(), AgentStepRecord(1, "old answer", finishReason = "stop"))
        )
        val prompt = builder().prepare(
            listOf(
                UiMessage("u1", "user", "old question"),
                UiMessage("a1", "assistant", "old answer", agentRun = oldRun),
                UiMessage("u2", "user", "new question")
            ), agentTestModel, emptyList(), listOf(completedStep())
        )
        val ids = prompt.request.messages.flatMap { it.toolCalls.orEmpty() }.map { it.id }
        assertEquals(4, ids.distinct().size)
        assertEquals(ids, prompt.request.messages.filter { it.role == "tool" }.map { it.toolCallId })
        assertEquals(listOf("first-0", "second-0"), oldRun.steps.first().toolCalls.map { it.id })
        assertTrue(com.tongxie.copilotgo.data.chat.AgentRequestEncoder.encode(prompt.request, Json).body.isNotBlank())
    }

    @Test
    fun ordinaryPromptRemainsToolBlindEvenWhenHistoryContainsAgentActivity() = runBlocking {
        val run = AgentRunRecord("run", 0, AgentRunStatus.COMPLETED, steps = listOf(completedStep()))
        val prompt = PromptBuilder(AttachmentStore(AppPaths(temporary.root))).prepare(
            listOf(
                UiMessage("u1", "user", "old question"),
                UiMessage("a1", "assistant", "visible answer", agentRun = run),
                UiMessage("u2", "user", "ordinary follow up")
            ), agentTestModel
        )
        assertEquals(listOf("old question", "visible answer", "ordinary follow up"), prompt.textRequest!!.messages.map { it.content })
        assertFalse(prompt.textRequest.messages.any { it.content.contains("actual result") })
    }

    @Test
    fun canonicalArgumentDigestIsIndependentOfObjectKeyOrderButNotValues() {
        val left = Json.parseToJsonElement("""{"b":[{"z":2,"a":1}],"a":"x"}""")
        val right = Json.parseToJsonElement("""{"a":"x","b":[{"a":1,"z":2}]}""")
        assertEquals(AgentValues.canonical(left), AgentValues.canonical(right))
        assertNotEquals(AgentValues.digest(AgentValues.canonical(left)), AgentValues.digest("""{"a":"changed"}"""))
        assertEquals("", AgentValues.truncateUtf8("😀", 3))
        assertEquals("😀", AgentValues.truncateUtf8("😀x", 4))
    }

    @Test
    fun citationGroundingPreservesCodeAndOnlyLinksActualProvenance() {
        val text = "Unknown [S999], actual [S1]. `literal [S999]`\n" +
            "```kotlin\nval s = \"[S999](https://invented.invalid)\"\n```\n" +
            "    [S999] indented code\n[bad](https://invented.invalid)"
        val source = SourceReference("https://example.org", "Actual", SourceKind.SEARCH_HIT, "S1")
        val result = AgentCitations.ground(text, listOf(source))
        assertTrue(result.removedUnsupportedReferences)
        assertTrue(result.text.startsWith("Unknown , actual [S1]. `literal [S999]`"))
        assertTrue(result.text.contains("val s = \"[S999](https://invented.invalid)\""))
        assertTrue(result.text.contains("    [S999] indented code"))
        assertTrue(result.text.endsWith("\nbad"))
    }

    @Test
    fun citationGroundingPreservesMultilineCodeSpansAndEscapedLiteralMarkers() {
        val code = "``a multiline\n[S999] [literal](https://example.invalid)\ncode span``"
        val literals = "\\[S999] and \\[literal](https://example.invalid)"
        val grounded = AgentCitations.ground("$code\n$literals\nUnsupported [S999]", emptyList())
        assertTrue(grounded.text.startsWith("$code\n$literals\n"))
        assertTrue(grounded.text.endsWith("Unsupported "))
        assertTrue(grounded.removedUnsupportedReferences)
    }
}
