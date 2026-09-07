package com.tongxie.copilotgo.ui.draft

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun draftSurvivesNewStoreAndKeepsUnicode() = runTest {
        val original = ComposerDraft("\u4F60\u597D \uD83D\uDC69\u200D\uD83D\uDCBB\nunfinished", revision = 3)
        ChatDraftStore(temporary.root).save("session", original)
        assertEquals(original, ChatDraftStore(temporary.root).load("session"))
    }

    @Test fun acceptedSnapshotDoesNotClearNewerInput() {
        val submitted = ComposerDraft("first", revision = 3)
        val newer = submitted.copy(text = "second", revision = 4)
        assertEquals(newer, newer.clearedIfAccepted(submitted))
        assertTrue(submitted.clearedIfAccepted(submitted).isEmpty)
    }

    @Test fun absentDraftIsEmptyButCorruptionIsNotSilentlyDropped() = runTest {
        val store = ChatDraftStore(temporary.root)
        assertTrue(store.load("missing").isEmpty)
        val corrupt = temporary.newFile("broken.json").apply { writeText("{broken") }
        try {
            store.load("broken")
            fail("Corrupt drafts must be reported")
        } catch (_: kotlinx.serialization.SerializationException) {
            assertEquals("{broken", corrupt.readText())
        }
    }

    @Test fun manifestReadsAndTextWritesAreBounded() = runTest {
        val store = ChatDraftStore(temporary.root)
        temporary.newFile("oversized.json").outputStream().use {
            it.write(ByteArray(DraftLimits.MANIFEST_BYTES + 1))
        }
        try {
            store.load("oversized")
            fail("Oversized manifest must be rejected")
        } catch (_: java.io.IOException) {
            assertTrue(true)
        }
        try {
            store.save("too-long", ComposerDraft("x".repeat(DraftLimits.TEXT_CHARS + 1)))
            fail("Oversized draft must be rejected")
        } catch (_: IllegalArgumentException) {
            assertFalse(File(temporary.root, "too-long.json").exists())
        }
    }

    @Test fun sessionIdsCannotEscapeDraftDirectory() = runTest {
        try {
            ChatDraftStore(temporary.root).save("../outside", ComposerDraft("protected"))
            fail("Traversal must be rejected")
        } catch (_: IllegalArgumentException) {
            assertFalse(File(temporary.root.parentFile, "outside.json").exists())
        }
    }
}
