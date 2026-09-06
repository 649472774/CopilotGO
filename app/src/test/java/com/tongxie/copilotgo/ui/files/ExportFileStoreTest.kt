package com.tongxie.copilotgo.ui.files

import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.UiMessage
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
}
