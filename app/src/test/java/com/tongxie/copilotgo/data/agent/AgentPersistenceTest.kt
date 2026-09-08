package com.tongxie.copilotgo.data.agent

import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.data.storage.AppPaths
import com.tongxie.copilotgo.data.storage.SessionStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun restartInterruptsApprovalsAndUnknownRunningCallsWithoutReplay() = runBlocking {
        val paths = AppPaths(temporary.root)
        val identity = AgentToolIdentity("config", 1, "action", "digest")
        val arguments = JsonObject(mapOf("query" to JsonPrimitive("fixture")))
        val approval = AgentApprovalRequest(
            AgentApprovalBinding("approval", "run", 1, "waiting", identity, "args", "descriptor"),
            "action", "https://example.org", arguments, 1, Long.MAX_VALUE
        )
        val run = AgentRunRecord(
            "run", 1, AgentRunStatus.AWAITING_APPROVAL,
            steps = listOf(AgentStepRecord(0, "partial answer", listOf(
                AgentToolCallRecord(
                    "running", "action", identity, arguments = arguments,
                    status = AgentToolCallStatus.RUNNING, startedAt = 1
                ),
                AgentToolCallRecord(
                    "waiting", "action", identity, arguments = arguments,
                    status = AgentToolCallStatus.AWAITING_APPROVAL
                )
            ))), pendingApproval = approval
        )
        val first = SessionStore(paths, json)
        first.save(Session("saved", "title", "model", mutableListOf(
            UiMessage("u", "user", "question"),
            UiMessage("a", "assistant", "partial answer", isStreaming = true, agentRun = run)
        ), agentSettings = AgentSessionSettings(enabled = true)))
        first.close()
        val reopened = SessionStore(paths, json)
        try {
            val restored = reopened.getSession("saved")!!.messages.last()
            assertEquals("partial answer", restored.content)
            assertFalse(restored.isStreaming)
            assertEquals("interrupted", restored.finishReason)
            val interrupted = restored.agentRun!!
            assertEquals(AgentRunStatus.INTERRUPTED, interrupted.status)
            assertNull(interrupted.pendingApproval)
            assertEquals(listOf(AgentToolCallStatus.INTERRUPTED, AgentToolCallStatus.INTERRUPTED),
                interrupted.steps.single().toolCalls.map { it.status })
            assertTrue(interrupted.steps.single().toolCalls.first().outcomeUnknown)
            assertFalse(interrupted.steps.single().toolCalls.last().outcomeUnknown)
            assertFalse(interrupted.safeToRetry)
            val disk = json.decodeFromString<Session>(File(paths.sessions, "saved.json").readText())
            assertEquals(interrupted, disk.messages.last().agentRun)
        } finally { reopened.close() }
    }

    @Test
    fun completeSourceAndResultRecordsSurviveRestartUnchanged() = runBlocking {
        val paths = AppPaths(temporary.root)
        val source = SourceReference("https://example.org/actual", "Actual", SourceKind.FETCHED_PAGE, "S1", "call")
        val run = AgentRunRecord(
            "run", 1, AgentRunStatus.COMPLETED, finishedAt = 10,
            steps = listOf(AgentStepRecord(0, toolCalls = listOf(
                AgentToolCallRecord(
                    "call", "read", arguments = JsonObject(emptyMap()), status = AgentToolCallStatus.SUCCEEDED,
                    result = AgentToolResult("actual bounded result", sources = listOf(source)), startedAt = 1, finishedAt = 2
                )
            ))), sources = listOf(source)
        )
        val first = SessionStore(paths, json)
        first.save(Session("saved", "title", "model", mutableListOf(UiMessage("a", "assistant", "answer", agentRun = run))))
        first.close()
        val original = File(paths.sessions, "saved.json").readBytes()
        val reopened = SessionStore(paths, json)
        try {
            assertEquals(run, reopened.getSession("saved")!!.messages.single().agentRun)
            assertArrayEquals(original, File(paths.sessions, "saved.json").readBytes())
        } finally { reopened.close() }
    }

    @Test
    fun agentSnapshotsDoNotShareMutableArgumentMapsOrLists() = runBlocking {
        val paths = AppPaths(temporary.root)
        val mutableArguments = mutableMapOf("query" to JsonPrimitive("original"))
        val calls = mutableListOf(AgentToolCallRecord(
            "call", "read", arguments = JsonObject(mutableArguments),
            status = AgentToolCallStatus.SUCCEEDED, result = AgentToolResult("actual"), finishedAt = 1
        ))
        val run = AgentRunRecord("run", 1, AgentRunStatus.COMPLETED, steps = listOf(AgentStepRecord(0, toolCalls = calls)))
        val store = SessionStore(paths, json)
        try {
            store.save(Session("saved", "title", "model", mutableListOf(UiMessage("a", "assistant", "answer", agentRun = run))))
            mutableArguments["query"] = JsonPrimitive("caller mutation")
            calls.clear()
            val saved = store.getSession("saved")!!.messages.single().agentRun!!
            assertEquals("\"original\"", saved.steps.single().toolCalls.single().arguments!!["query"].toString())
        } finally { store.close() }
    }

    @Test
    fun oldDefaultEncodingAndPrimaryBytesRemainUnchanged() = runBlocking {
        val paths = AppPaths(temporary.root)
        paths.sessions.mkdirs()
        val original = """{"id":"old","title":"v0.2","model":"model","messages":[{"id":"u","role":"user","content":"legacy"}]}"""
        val file = File(paths.sessions, "old.json")
        file.writeText(original)
        val store = SessionStore(paths, json)
        try {
            val session = store.getSession("old")!!
            assertFalse(session.agentSettings.enabled)
            assertNull(session.messages.single().agentRun)
            assertEquals(original, file.readText())
            val encoded = json.encodeToString(Session.serializer(), session)
            assertFalse(encoded.contains("agentSettings"))
            assertFalse(encoded.contains("agentRun"))
        } finally { store.close() }
    }
}
