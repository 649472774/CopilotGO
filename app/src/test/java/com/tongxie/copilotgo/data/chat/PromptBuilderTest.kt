package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.storage.AppPaths
import com.tongxie.copilotgo.data.storage.AttachmentStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PromptBuilderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val model = ModelInfo("fixture", capabilities = ModelCapabilities(type = "chat"))

    @Test
    fun boundsHistoryByWholeTurnsAndKeepsTheNewestUserMessage() = runBlocking {
        val history = (0 until 70).flatMap {
            listOf(UiMessage("u$it", "user", "question $it"), UiMessage("a$it", "assistant", "answer $it"))
        } + UiMessage("current", "user", "latest")
        val prepared = PromptBuilder(AttachmentStore(AppPaths(temporary.root))).prepare(history, model)
        val messages = prepared.textRequest!!.messages
        assertTrue(prepared.truncated)
        assertEquals(79, messages.size)
        assertEquals("latest", messages.last().content)
        assertEquals("user", messages.first().role)
        assertEquals(listOf("user", "assistant"), messages.take(2).map { it.role })
        assertEquals(141, history.size)
    }

    @Test
    fun newestOversizeTurnIsRejectedInsteadOfSilentlyTruncated() = runBlocking {
        val smallModel = model.copy(capabilities = ModelCapabilities(limits = ModelLimits(maxPromptTokens = 100)))
        try {
            PromptBuilder(AttachmentStore(AppPaths(temporary.root))).prepare(
                listOf(UiMessage("current", "user", "界".repeat(100))), smallModel
            )
            fail("The latest turn must not be cut")
        } catch (_: ModelUnavailableException) {
            assertTrue(true)
        }
    }

    @Test
    fun skipsOrphanedAssistantAndFailedOldTurns() = runBlocking {
        val history = listOf(
            UiMessage("orphan", "assistant", "orphan"),
            UiMessage("old-user", "user", "failed question"),
            UiMessage("old-error", "assistant", "[request failed]", finishReason = "error"),
            UiMessage("new-user", "user", "latest")
        )
        val prepared = PromptBuilder(AttachmentStore(AppPaths(temporary.root))).prepare(history, model)
        assertEquals(listOf(ChatMessage("user", "latest")), prepared.textRequest!!.messages)
    }
}
