package com.tongxie.copilotgo.ui.files

import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.data.agent.AgentRunRecord
import com.tongxie.copilotgo.data.agent.AgentRunStatus
import com.tongxie.copilotgo.data.agent.AgentStepRecord
import com.tongxie.copilotgo.data.agent.AgentToolCallRecord
import com.tongxie.copilotgo.data.agent.AgentToolCallStatus
import com.tongxie.copilotgo.data.agent.AgentToolResult
import com.tongxie.copilotgo.data.agent.SourceKind
import com.tongxie.copilotgo.data.agent.SourceReference
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun longConversationIsSharedAsAFileAndPreviewIsBounded() = runTest {
        val store = ExportFileStore(temporary.root)
        val content = "controlled fixture ".repeat(30_000)
        val session = Session("fixture", "Controlled export", "fixture-model").apply {
            messages.add(UiMessage("message", "assistant", content))
        }
        val exported = store.writeSession(session)

        assertTrue(exported.sizeBytes > 500_000)
        assertTrue(store.file(exported.name).readText().contains(content))
        val preview = store.preview(exported.name)
        assertTrue(preview.truncated)
        assertEquals(ExportFileStore.PREVIEW_CHAR_LIMIT, preview.text.length)
        assertEquals(listOf(exported.name), store.list().map { it.name })
    }

    @Test fun deleteCannotEscapeExportsOrDeleteDirectories() = runTest {
        val store = ExportFileStore(temporary.root)
        val protected = temporary.newFile("session.md").apply { writeText("protected") }
        try {
            store.delete("../session.md")
            fail("Path traversal must be rejected")
        } catch (_: IllegalArgumentException) {
            assertTrue(protected.exists())
        }
        val exportDir = File(temporary.root, "exports").apply { mkdirs() }
        val directory = File(exportDir, "folder.md").apply { mkdirs() }
        try {
            store.delete("folder.md")
            fail("A directory must never be deleted")
        } catch (_: java.io.IOException) {
            assertTrue(directory.exists())
        }
    }

    @Test fun deleteOnlyRemovesTheChosenExport() = runTest {
        val store = ExportFileStore(temporary.root)
        val first = store.writeMessage(UiMessage("1", "user", "first"))
        val second = store.writeMessage(UiMessage("2", "user", "second"))
        store.delete(first.name)
        assertEquals(listOf(second.name), store.list().map { it.name })
        assertFalse(File(File(temporary.root, "exports"), first.name).exists())
    }

    @Test fun byteLimitRejectsBeforeWritingExcess() {
        val output = ByteArrayOutputStream()
        val limited = SizeLimitedOutputStream(output, 4)
        limited.write(byteArrayOf(1, 2, 3))
        try {
            limited.write(byteArrayOf(4, 5))
            fail("Excess bytes must not be written")
        } catch (_: ExportTooLargeException) {
            assertEquals(3, output.size())
        }
        limited.write(4)
        assertEquals(4, output.size())
    }

    @Test fun emptyAndEmojiMessagesRemainIntact() = runTest {
        val store = ExportFileStore(temporary.root)
        val content = "\u4F60\u597D \uD83D\uDC69\u200D\uD83D\uDCBB"
        val result = store.writeMessage(UiMessage("emoji", "user", content))
        assertTrue(store.preview(result.name).text.contains(content))
        assertFalse(store.preview(result.name).truncated)
    }

    @Test fun agentExportPreservesActualOutcomesAndSourceProvenanceWithoutApprovalInternals() = runTest {
        val store = ExportFileStore(temporary.root)
        val run = AgentRunRecord(
            id = "private-runtime-id", accountGeneration = 314, status = AgentRunStatus.INTERRUPTED,
            steps = listOf(AgentStepRecord(0, toolCalls = listOf(AgentToolCallRecord(
                id = "call", name = "controlled_tool", destination = "https://example.com/mcp",
                arguments = buildJsonObject { put("query", "fixture only") },
                status = AgentToolCallStatus.INTERRUPTED, outcomeUnknown = true,
                result = AgentToolResult("partial actual result", truncated = true, outcomeUnknown = true)
            )))),
            sources = listOf(
                SourceReference("https://example.com/search", "Search fixture", SourceKind.SEARCH_HIT, "S1"),
                SourceReference("https://example.com/page", "Read fixture", SourceKind.FETCHED_PAGE, "S2")
            )
        )
        val exported = store.writeMessage(UiMessage("message", "assistant", "partial answer", agentRun = run))
        val text = store.file(exported.name).readText()
        assertTrue(text.contains("partial answer"))
        assertTrue(text.contains("Remote outcome unknown"))
        assertTrue(text.contains("    partial actual result"))
        assertTrue(text.contains("SEARCH_HIT S1"))
        assertTrue(text.contains("FETCHED_PAGE S2"))
        assertTrue(text.contains("https://example.com/page"))
        assertTrue(text.contains("[S2]: <https://example.com/page>"))
        assertFalse(text.contains("private-runtime-id"))
        assertFalse(text.contains("accountGeneration"))
        assertFalse(text.contains("approvalId"))
    }

    @Test fun ordinaryMessageExportShapeDoesNotChange() = runTest {
        val store = ExportFileStore(temporary.root)
        val exported = store.writeMessage(UiMessage("message", "assistant", "ordinary reply"))
        assertEquals("## Copilot\n\nordinary reply\n\n", store.file(exported.name).readText())
    }
}
