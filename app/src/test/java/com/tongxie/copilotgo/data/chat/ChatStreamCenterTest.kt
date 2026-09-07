package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.storage.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class ChatStreamCenterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun textOnlyAttachmentIsAcceptedAndBytesAreNotInConversationJson() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.enqueueText()
            assertTrue(fixture.center.submit("fixture-session", "", attachments = listOf("fixture file contents")) is SendResult.Accepted)
            fixture.idle()
            val session = fixture.store.getSession("fixture-session")!!
            assertEquals(1, session.messages.first().attachments.size)
            assertFalse(File(fixture.paths.sessions, "fixture-session.json").readText().contains("fixture file contents"))
            val body = fixture.requests.first { it.path == "/chat/completions" }.body.readUtf8()
            assertTrue(body.contains("fixture file contents"))
        }
    }

    @Test
    fun duplicateColdSendHasOnlyOneAdmission() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.models = { CoreFixture.modelResponse().setBodyDelay(200, TimeUnit.MILLISECONDS) }
            fixture.enqueueText()
            val first = async(Dispatchers.Default) { fixture.center.submit("fixture-session", "one") }
            val second = async(Dispatchers.Default) { fixture.center.submit("fixture-session", "two") }
            val results = listOf(first.await(), second.await())
            assertEquals(1, results.count { it is SendResult.Accepted })
            assertEquals(1, results.count { it is SendResult.Rejected })
            fixture.idle()
            assertEquals(1, fixture.store.getSession("fixture-session")!!.messages.count { it.role == "user" })
            assertEquals(1, fixture.requests.count { it.path == "/chat/completions" })
        }
    }

    @Test
    fun persistedSubmissionReceiptPreventsRetryingTheSameDraftTwice() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.enqueueText()
            val first = fixture.center.submit("fixture-session", "one", submissionId = "fixture-draft") as SendResult.Accepted
            fixture.idle()
            val again = fixture.center.submit("fixture-session", "one", submissionId = "fixture-draft")
            assertEquals(first, again)
            fixture.idle()
            assertEquals(1, fixture.requests.count { it.path == "/chat/completions" })
            assertEquals(1, fixture.store.getSession("fixture-session")!!.messages.count { it.role == "user" })
        }
    }

    @Test
    fun renamedPinnedOpenSessionSurvivesStreamingSaves() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.center.retain("fixture-session")
            val flow = fixture.center.sessionFlow("fixture-session")
            fixture.replies.add(CoreFixture.sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"reply\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            ).setBodyDelay(250, TimeUnit.MILLISECONDS))
            assertTrue(fixture.center.submit("fixture-session", "question") is SendResult.Accepted)
            fixture.store.rename("fixture-session", "renamed while streaming")
            fixture.store.setPinned("fixture-session", true)
            fixture.idle()
            assertEquals("renamed while streaming", flow.value!!.title)
            assertTrue(flow.value!!.pinned)
            assertEquals("renamed while streaming", fixture.store.getSession("fixture-session")!!.title)
            fixture.center.release("fixture-session")
        }
    }

    @Test
    fun stopIsPromptKeepsPartialTextAndDoesNotPoisonResend() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create()
            val firstChunk = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
            fixture.replies.add(CoreFixture.sse(
                firstChunk + "data: {\"choices\":[{\"delta\":{\"content\":\"late\"}}]}\n\n"
            ).throttleBody(firstChunk.toByteArray().size.toLong(), 1, TimeUnit.SECONDS))
            assertTrue(fixture.center.submit("fixture-session", "first") is SendResult.Accepted)
            withTimeout(3000) {
                fixture.center.sessionFlow("fixture-session").first { it?.messages?.lastOrNull()?.content == "partial" }
            }
            fixture.center.stop("fixture-session")
            withTimeout(750) { fixture.center.sendingFlow("fixture-session").first { !it } }
            val stopped = fixture.store.getSession("fixture-session")!!.messages.last()
            assertEquals("partial", stopped.content)
            assertFalse(stopped.isStreaming)
            assertEquals("cancelled", stopped.finishReason)
            assertNull(fixture.center.errorFlow("fixture-session").value)
            fixture.enqueueText("second reply")
            assertTrue(fixture.center.submit("fixture-session", "second") is SendResult.Accepted)
            fixture.idle()
            assertEquals("second reply", fixture.store.getSession("fixture-session")!!.messages.last().content)
            assertNull(fixture.center.errorFlow("fixture-session").value)
        }
    }

    @Test
    fun storeDeletionCancelsLiveStreamAndNoSaveResurrectsIt() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.replies.add(CoreFixture.sse("data: [DONE]\n\n").setBodyDelay(700, TimeUnit.MILLISECONDS))
            assertTrue(fixture.center.submit("fixture-session", "question") is SendResult.Accepted)
            fixture.store.delete("fixture-session")
            fixture.idle()
            assertFalse(File(fixture.paths.sessions, "fixture-session.json").exists())
            assertFalse(File(fixture.paths.sessions, "fixture-session.json.bak").exists())
            assertNull(fixture.store.getSession("fixture-session"))
            val reopened = SessionStore(fixture.paths, fixture.json)
            try {
                reopened.load()
                assertTrue(reopened.summaries.value.isEmpty())
            } finally { reopened.close() }
        }
    }

    @Test
    fun historicalImageIsRetainedForTextFollowUpAndModelChoiceIsNotOverwritten() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.paths.sessions.mkdirs()
            val image = CoreFixture.imageDataUri()
            File(fixture.paths.sessions, "fixture-session.json").writeText(
                """{"id":"fixture-session","title":"history","model":"fixture-chat","messages":[{"id":"u","role":"user","content":"image","imageUrls":["$image"]},{"id":"a","role":"assistant","content":"description"}]}"""
            )
            fixture.enqueueText("follow-up")
            assertTrue(fixture.center.submit("fixture-session", "what about it?") is SendResult.Accepted)
            fixture.idle()
            val body = fixture.requests.first { it.path == "/chat/completions" }.body.readUtf8()
            val sent = fixture.json.decodeFromString(VisionRequest.serializer(), body)
            assertEquals(image, sent.messages.first().content.first { it.type == "image_url" }.imageUrl!!.url)
            assertEquals("fixture-chat", fixture.store.getSession("fixture-session")!!.model)
            assertFalse(File(fixture.paths.sessions, "fixture-session.json").readText().contains("data:image"))
        }
    }

    @Test
    fun incompatibleVisionModelRejectsBeforeInsertingOrChangingAnything() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create(model = "fixture-text")
            val result = fixture.center.submit("fixture-session", "question", imageUrls = listOf(CoreFixture.imageDataUri()))
            assertTrue(result is SendResult.Rejected)
            fixture.idle()
            val session = fixture.store.getSession("fixture-session")!!
            assertTrue(session.messages.isEmpty())
            assertEquals("fixture-text", session.model)
            assertEquals(0, fixture.requests.count { it.path == "/chat/completions" })
        }
    }

    @Test
    fun failedInitialPersistenceRejectsWithoutDiscardingDraftOrHistory() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create()
            File(fixture.paths.sessions, "fixture-session.json.tmp").mkdir()
            val result = fixture.center.submit("fixture-session", "keep my draft")
            assertTrue(result is SendResult.Rejected)
            fixture.idle()
            assertTrue(fixture.store.getSession("fixture-session")!!.messages.isEmpty())
            assertEquals(0, fixture.requests.count { it.path == "/chat/completions" })
        }
    }

    @Test
    fun failedModelRefreshDoesNotRewriteRetiredSelectionOrInsertMessages() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create(model = "retired-fixture")
            fixture.models = { MockResponse().setResponseCode(401).setBody("{}") }
            assertTrue(fixture.center.submit("fixture-session", "keep") is SendResult.Rejected)
            fixture.idle()
            val session = fixture.store.getSession("fixture-session")!!
            assertEquals("retired-fixture", session.model)
            assertTrue(session.messages.isEmpty())
        }
    }

    @Test
    fun reusedSubmissionIdWithChangedContentIsRejected() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.enqueueText()
            assertTrue(fixture.center.submit("fixture-session", "first", submissionId = "same") is SendResult.Accepted)
            fixture.idle()
            assertTrue(fixture.center.submit("fixture-session", "changed", submissionId = "same") is SendResult.Rejected)
            fixture.idle()
            assertEquals(1, fixture.requests.count { it.path == "/chat/completions" })
            assertEquals("first", fixture.store.getSession("fixture-session")!!.messages.first().content)
        }
    }

    @Test
    fun activeStreamCannotBeEditedDeletedOrRegenerated() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.replies.add(CoreFixture.sse("data: [DONE]\n\n").setBodyDelay(1, TimeUnit.SECONDS))
            assertTrue(fixture.center.submit("fixture-session", "question") is SendResult.Accepted)
            val snapshot = fixture.store.getSession("fixture-session")!!
            assertTrue(fixture.center.deleteMessageAndAwait(snapshot.id, snapshot.messages.first().id) is OperationResult.Rejected)
            assertTrue(fixture.center.editAndResendAndAwait(snapshot.id, snapshot.messages.first().id, "edit") is OperationResult.Rejected)
            assertTrue(fixture.center.regenerateAndAwait(snapshot.id, snapshot.messages.last().id) is OperationResult.Rejected)
            fixture.center.stop(snapshot.id)
            fixture.idle()
            assertEquals(2, fixture.store.getSession(snapshot.id)!!.messages.size)
        }
    }

    @Test
    fun delayedAccountObserverDoesNotCancelANewAccountSubmission() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create()
            val dispatcher = PausedDispatcher()
            val center = ChatStreamCenter(
                fixture.store, fixture.client, fixture.catalog, CoroutineScope(SupervisorJob() + dispatcher)
            )
            try {
                val credentials = fixture.credentials.credentials
                fixture.auth.logout()
                fixture.credentials.credentials = credentials
                fixture.models = { CoreFixture.modelResponse().setBodyDelay(100, TimeUnit.MILLISECONDS) }
                fixture.enqueueText()
                val submission = async(start = CoroutineStart.UNDISPATCHED) {
                    center.submit("fixture-session", "new account request")
                }
                dispatcher.release()
                assertTrue(withTimeout(3000) { submission.await() } is SendResult.Accepted)
                withTimeout(3000) { center.sendingFlow("fixture-session").first { !it } }
                assertEquals("fixture reply", fixture.store.getSession("fixture-session")!!.messages.last().content)
            } finally { center.close() }
        }
    }

    @Test
    fun deletingOnlyAUserKeepsReplyVisibleButDoesNotSendItAsAnotherTurnsAnswer() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.store.update("fixture-session") {
                it.copy(messages = mutableListOf(
                    UiMessage("u1", "user", "first question"),
                    UiMessage("a1", "assistant", "first answer"),
                    UiMessage("u2", "user", "deleted question"),
                    UiMessage("a2", "assistant", "orphaned answer")
                ))
            }
            assertEquals(OperationResult.Accepted, fixture.center.deleteMessageAndAwait("fixture-session", "u2"))
            fixture.idle()
            val retained = fixture.store.getSession("fixture-session")!!
            assertEquals(listOf("u1", "a1", "a2"), retained.messages.map { it.id })
            assertEquals("orphaned answer", retained.messages.last().content)
            val regenerate = fixture.center.regenerateAndAwait("fixture-session", "a2") as OperationResult.Rejected
            assertTrue(regenerate.message.contains("用户消息已删除"))
            fixture.idle()
            fixture.enqueueText()
            assertTrue(fixture.center.submit("fixture-session", "new question") is SendResult.Accepted)
            fixture.idle()
            val sent = fixture.json.decodeFromString(
                ChatRequest.serializer(), fixture.requests.single { it.path == "/chat/completions" }.body.readUtf8()
            )
            assertEquals(listOf("first question", "first answer", "new question"), sent.messages.map { it.content })
        }
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val guard = Any()
        private var released = false
        private val pending = mutableListOf<Pair<CoroutineContext, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val dispatch = synchronized(guard) {
                if (released) true else {
                    pending.add(context to block)
                    false
                }
            }
            if (dispatch) Dispatchers.Default.dispatch(context, block)
        }
        fun release() {
            val tasks = synchronized(guard) {
                released = true
                pending.toList().also { pending.clear() }
            }
            tasks.forEach { (context, block) -> Dispatchers.Default.dispatch(context, block) }
        }
    }
}
