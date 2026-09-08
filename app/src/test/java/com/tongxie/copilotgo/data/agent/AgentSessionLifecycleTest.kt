package com.tongxie.copilotgo.data.agent

import com.tongxie.copilotgo.data.auth.withResponse
import com.tongxie.copilotgo.data.chat.ChatStreamCenter
import com.tongxie.copilotgo.data.chat.CoreFixture
import com.tongxie.copilotgo.data.chat.OperationResult
import com.tongxie.copilotgo.data.chat.SendResult
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.data.storage.AppPaths
import com.tongxie.copilotgo.data.storage.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class AgentSessionLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "fixture-session"

    @Test
    fun realModelProposalApprovalNetworkToolAndContinuationUseOneDurableAdmission() = runBlocking {
        Fixture(temporary.root, realModel = true).use { fixture ->
            fixture.create()
            val toolServer = MockWebServer()
            val http = OkHttpClient()
            toolServer.start()
            try {
                toolServer.enqueue(MockResponse().setBody("actual external answer"))
                fixture.tools.executeAction = { invocation ->
                    val request = Request.Builder().url(toolServer.url("/read"))
                        .post(invocation.arguments.toString().toRequestBody("application/json".toMediaType())).build()
                    val result = http.newCall(request).withResponse { it.body!!.string() }
                    AgentToolResult(result, sources = listOf(
                        SourceReference("https://example.org/actual", "Actual source", SourceKind.FETCHED_PAGE)
                    ))
                }
                fixture.core.replies.add(CoreFixture.sse(
                    """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"fixture_search","arguments":"{\"query\":\"fix"}}]}}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ture\"}"}}]}}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

"""
                ))
                fixture.core.enqueueText("The actual external answer [S1].")
                val accepted = fixture.center.submit(id, "question", submissionId = "draft") as SendResult.Accepted
                val approval = fixture.approval()
                assertTrue(fixture.center.sendingFlow(id).value)
                assertTrue(fixture.tools.invocations.isEmpty())
                val durable = fixture.core.json.decodeFromString<Session>(fixture.file().readText())
                assertEquals(accepted.userMessageId, durable.messages.first().id)
                assertEquals("draft", durable.messages.first().submissionId)
                assertNotNull(durable.messages.last().agentRun?.pendingApproval)
                assertTrue(fixture.center.submit(id, "another request") is SendResult.Rejected)
                assertEquals(AgentApprovalResponse.Accepted,
                    fixture.center.respondToApproval(id, approval.binding, AgentApprovalDecision.APPROVE))
                fixture.idle()
                val session = fixture.session()
                assertEquals(AgentRunStatus.COMPLETED, session.messages.last().agentRun!!.status)
                assertEquals(1, fixture.tools.invocations.size)
                val modelRequests = fixture.core.requests.filter { it.path == "/chat/completions" }
                assertEquals(2, modelRequests.size)
                val continuation = fixture.core.json.decodeFromString(
                    AgentChatRequest.serializer(), modelRequests.last().body.readUtf8()
                )
                assertTrue(continuation.messages.single { it.role == "tool" }.content.toString().contains("actual external answer"))
                assertNull(toolServer.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
                assertEquals(accepted, fixture.center.submit(id, "question", submissionId = "draft"))
                fixture.idle()
                assertEquals(2, fixture.core.requests.count { it.path == "/chat/completions" })
                assertEquals(1, fixture.session().messages.count { it.role == "user" })
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
                toolServer.shutdown()
            }
        }
    }

    @Test
    fun exactBindingAndOneShotApprovalAreAuthoritative() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.model.enqueue(proposal())
            fixture.model.answer()
            fixture.center.submit(id, "question")
            val request = fixture.approval()
            val variants = listOf(
                request.binding.copy(runId = "another-run"),
                request.binding.copy(callId = "another-call"),
                request.binding.copy(accountGeneration = 100),
                request.binding.copy(argumentsDigest = "edited"),
                request.binding.copy(descriptorDigest = "edited"),
                request.binding.copy(tool = request.binding.tool.copy(configRevision = 7))
            )
            variants.forEach {
                assertTrue(fixture.center.respondToApproval(id, it, AgentApprovalDecision.APPROVE) is AgentApprovalResponse.Rejected)
            }
            assertTrue(fixture.tools.invocations.isEmpty())
            assertEquals(AgentApprovalResponse.Accepted,
                fixture.center.respondToApproval(id, request.binding, AgentApprovalDecision.APPROVE))
            assertTrue(fixture.center.respondToApproval(id, request.binding, AgentApprovalDecision.APPROVE) is AgentApprovalResponse.Rejected)
            fixture.idle()
            assertEquals(1, fixture.tools.invocations.size)
        }
    }

    @Test
    fun configurationChangeRejectsStaleButtonsAndCancelsTheSameSessionOperation() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.model.enqueue(proposal())
            fixture.center.submit(id, "question")
            val request = fixture.approval()
            fixture.tools.invalidate()
            assertTrue(fixture.center.respondToApproval(id, request.binding, AgentApprovalDecision.APPROVE) is AgentApprovalResponse.Rejected)
            fixture.idle()
            assertEquals(AgentRunStatus.INTERRUPTED, fixture.session().messages.last().agentRun!!.status)
            assertTrue(fixture.tools.invocations.isEmpty())
            assertNull(fixture.session().messages.last().agentRun!!.pendingApproval)
        }
    }

    @Test
    fun stopDuringApprovalKeepsHistoryAndRejectsTheOldButton() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.model.enqueue(proposal())
            fixture.center.submit(id, "question")
            val request = fixture.approval()
            fixture.center.stop(id)
            fixture.idle()
            assertEquals("cancelled", fixture.session().messages.last().finishReason)
            assertEquals(AgentRunStatus.CANCELLED, fixture.session().messages.last().agentRun!!.status)
            assertNull(fixture.center.errorFlow(id).value)
            assertTrue(fixture.center.respondToApproval(id, request.binding, AgentApprovalDecision.APPROVE) is AgentApprovalResponse.Rejected)
            assertTrue(fixture.tools.invocations.isEmpty())
        }
    }

    @Test
    fun logoutCancelsToolWorkBeforeSendingBecomesFalse() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create(autoApprove = true, kind = AgentToolKind.PUBLIC_WEB_READ)
            val entered = CompletableDeferred<Unit>()
            var cleaned = false
            fixture.tools.executeAction = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            }
            fixture.model.enqueue(proposal())
            fixture.center.submit(id, "question")
            withTimeout(3000) { entered.await() }
            fixture.core.auth.logout()
            fixture.idle()
            assertTrue(cleaned)
            assertEquals(AgentRunStatus.CANCELLED, fixture.session().messages.last().agentRun!!.status)
            assertNull(fixture.center.errorFlow(id).value)
        }
    }

    @Test
    fun deletionCancelsApprovalAndPendingSavesCannotRecreateTheSession() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.model.enqueue(proposal())
            fixture.center.submit(id, "question")
            val request = fixture.approval()
            fixture.core.store.delete(id)
            fixture.idle()
            assertFalse(fixture.file().exists())
            assertFalse(File(fixture.core.paths.sessions, "$id.json.bak").exists())
            assertTrue(fixture.center.respondToApproval(id, request.binding, AgentApprovalDecision.APPROVE) is AgentApprovalResponse.Rejected)
            assertTrue(fixture.tools.invocations.isEmpty())
        }
    }

    @Test
    fun metadataChangesDuringAnApprovalSurviveTheNextDurableToolResult() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.model.enqueue(proposal())
            fixture.model.answer()
            fixture.center.submit(id, "question")
            val request = fixture.approval()
            fixture.core.store.rename(id, "renamed while waiting")
            fixture.core.store.setPinned(id, true)
            fixture.center.respondToApproval(id, request.binding, AgentApprovalDecision.APPROVE)
            fixture.idle()
            assertEquals("renamed while waiting", fixture.session().title)
            assertTrue(fixture.session().pinned)
        }
    }

    @Test
    fun failedAdmissionPreservesTheDraftAndNeverStartsTheRunner() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            assertTrue(File(fixture.core.paths.sessions, "$id.json.tmp").mkdir())
            assertTrue(fixture.center.submit(id, "keep draft") is SendResult.Rejected)
            fixture.idle()
            assertTrue(fixture.session().messages.isEmpty())
            assertTrue(fixture.model.requests.isEmpty())
            assertTrue(fixture.tools.invocations.isEmpty())
        }
    }

    @Test
    fun stopDuringDefinitionPreparationRejectsAdmissionAndReleasesTheSameTicket() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            val dataUri = CoreFixture.imageDataUri()
            val attachment = fixture.core.store.attachments.importDataUri(dataUri)
            val entered = CompletableDeferred<Unit>()
            var cleaned = false
            fixture.tools.snapshotAction = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            }
            val pending = async(Dispatchers.Default) {
                fixture.center.submit(id, "keep image draft", attachmentRefs = listOf(attachment), submissionId = "draft")
            }
            withTimeout(3000) { entered.await() }
            assertTrue(fixture.center.submit(id, "duplicate") is SendResult.Rejected)
            assertTrue(fixture.session().messages.isEmpty())
            fixture.center.stop(id)
            assertTrue(withTimeout(3000) { pending.await() } is SendResult.Rejected)
            fixture.idle()
            assertTrue(cleaned)
            assertTrue(fixture.session().messages.isEmpty())
            assertTrue(fixture.model.requests.isEmpty())
            assertEquals(dataUri, fixture.core.store.attachments.imageDataUri(attachment))
            assertNull(fixture.center.errorFlow(id).value)
            fixture.tools.snapshotAction = { AgentToolSnapshot(0, listOf(fixture.tools.descriptor)) }
            fixture.model.answer("The image draft can be sent again.")
            assertTrue(fixture.center.submit(
                id, "keep image draft", attachmentRefs = listOf(attachment), submissionId = "draft"
            ) is SendResult.Accepted)
            fixture.idle()
            assertEquals(1, fixture.session().messages.count { it.role == "user" })
            assertEquals(1, fixture.model.requests.size)
        }
    }

    @Test
    fun logoutDuringDefinitionPreparationRejectsBeforePersistingTheUserMessage() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            val entered = CompletableDeferred<Unit>()
            var cleaned = false
            fixture.tools.snapshotAction = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            }
            val pending = async(Dispatchers.Default) { fixture.center.submit(id, "keep account draft") }
            withTimeout(3000) { entered.await() }
            fixture.core.auth.logout()
            assertTrue(withTimeout(3000) { pending.await() } is SendResult.Rejected)
            fixture.idle()
            assertTrue(cleaned)
            assertTrue(fixture.session().messages.isEmpty())
            assertTrue(fixture.model.requests.isEmpty())
            assertTrue(fixture.tools.invocations.isEmpty())
            assertNull(fixture.center.errorFlow(id).value)
        }
    }

    @Test
    fun changedDefinitionSnapshotRejectsBeforeDurableAdmission() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.tools.snapshotAction = {
                val snapshot = AgentToolSnapshot(fixture.tools.revision.value, listOf(fixture.tools.descriptor))
                fixture.tools.invalidate()
                snapshot
            }
            assertTrue(fixture.center.submit(id, "keep configuration draft") is SendResult.Rejected)
            fixture.idle()
            assertTrue(fixture.session().messages.isEmpty())
            assertTrue(fixture.model.requests.isEmpty())
            assertTrue(fixture.tools.invocations.isEmpty())
            assertNotNull(fixture.center.errorFlow(id).value)
        }
    }

    @Test
    fun failureToPersistTheRunningTransitionPreventsExternalExecution() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.model.enqueue(proposal())
            assertTrue(fixture.center.submit(id, "admitted once", submissionId = "saved-draft") is SendResult.Accepted)
            val request = fixture.approval()
            assertTrue(File(fixture.core.paths.sessions, "$id.json.tmp").mkdir())
            fixture.center.respondToApproval(id, request.binding, AgentApprovalDecision.APPROVE)
            fixture.idle()
            assertTrue(fixture.tools.invocations.isEmpty())
            assertNotNull(fixture.center.errorFlow(id).value)
            assertEquals("saved-draft", fixture.session().messages.first().submissionId)
        }
    }

    @Test
    fun retryAncestorEditAndIsolatedDeleteCannotReplayOrEraseProtectedMcpWork() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.model.enqueue(proposal())
            fixture.model.answer()
            fixture.center.submit(id, "question")
            fixture.center.respondToApproval(id, fixture.approval().binding, AgentApprovalDecision.APPROVE)
            fixture.idle()
            val session = fixture.session()
            val user = session.messages.first().id
            val assistant = session.messages.last().id
            assertTrue(fixture.center.retryLastAndAwait(id) is OperationResult.Rejected)
            fixture.idle()
            assertTrue(fixture.center.regenerateAndAwait(id, assistant) is OperationResult.Rejected)
            fixture.idle()
            assertTrue(fixture.center.editAndResendAndAwait(id, user, "edit") is OperationResult.Rejected)
            fixture.idle()
            assertTrue(fixture.center.deleteMessageAndAwait(id, assistant) is OperationResult.Rejected)
            fixture.idle()
            assertTrue(fixture.center.deleteMessageAndAwait(id, user) is OperationResult.Rejected)
            fixture.idle()
            assertEquals(1, fixture.tools.invocations.size)
            assertEquals(2, fixture.session().messages.size)
        }
    }

    @Test
    fun staleModeSnapshotRejectsInsteadOfOverwritingNewSettings() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            val original = fixture.session().agentSettings
            val changed = original.copy(autoApprovePublicWebReads = true)
            assertEquals(OperationResult.Accepted, fixture.center.setAgentSettingsAndAwait(id, changed, original))
            fixture.idle()
            assertTrue(fixture.center.submit(id, "draft", agentSettings = original) is SendResult.Rejected)
            fixture.idle()
            assertEquals(changed, fixture.session().agentSettings)
            assertTrue(fixture.session().messages.isEmpty())
            assertTrue(fixture.center.setAgentSettingsAndAwait(id, original, original) is OperationResult.Rejected)
            fixture.idle()
            assertEquals(changed, fixture.session().agentSettings)
        }
    }

    @Test
    fun disabledModeStillUsesOrdinaryChatAndDoesNotAskTheExecutor() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.core.create()
            fixture.core.enqueueText("ordinary reply")
            assertTrue(fixture.center.submit(id, "ordinary") is SendResult.Accepted)
            fixture.idle()
            assertEquals("ordinary reply", fixture.session().messages.last().content)
            assertNull(fixture.session().messages.last().agentRun)
            assertTrue(fixture.model.requests.isEmpty())
            assertTrue(fixture.tools.invocations.isEmpty())
        }
    }

    private class Fixture(root: File, realModel: Boolean = false) : AutoCloseable {
        val core = CoreFixture(root)
        val tools = AgentTestExecutor()
        val model = AgentTestModel()
        val center = ChatStreamCenter(
            core.store, core.client, core.catalog, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            agentRunner = AgentEngine(
                if (realModel) AgentModelTransport(core.client::streamAgentChat) else model,
                tools, AgentPromptBuilder(core.store.attachments)
            )
        )
        suspend fun create(autoApprove: Boolean = false, kind: AgentToolKind = AgentToolKind.MCP) {
            tools.descriptor = tools.descriptor.copy(kind = kind)
            core.create()
            core.store.update("fixture-session") {
                it.copy(agentSettings = AgentSessionSettings(true, autoApprove))
            }
        }
        suspend fun session() = core.store.getSession("fixture-session")!!
        suspend fun approval(): AgentApprovalRequest = withTimeout(5000) {
            center.sessionFlow("fixture-session").first {
                it?.messages?.lastOrNull()?.agentRun?.pendingApproval != null
            }!!.messages.last().agentRun!!.pendingApproval!!
        }
        suspend fun idle() = withTimeout(5000) { center.sendingFlow("fixture-session").first { !it } }
        fun file() = File(core.paths.sessions, "fixture-session.json")
        override fun close() { center.close(); core.close() }
    }
}
