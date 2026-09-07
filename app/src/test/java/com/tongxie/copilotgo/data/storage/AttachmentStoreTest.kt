package com.tongxie.copilotgo.data.storage

import com.tongxie.copilotgo.data.chat.AttachmentKind
import com.tongxie.copilotgo.data.chat.AttachmentRef
import com.tongxie.copilotgo.data.chat.CoreFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun identicalImagesReuseOneContentAddressedBlob() = runBlocking {
        val paths = AppPaths(temporary.root)
        val store = AttachmentStore(paths)
        val first = store.importDataUri(CoreFixture.imageDataUri())
        val second = store.importDataUri(CoreFixture.imageDataUri())
        assertEquals(first.id, second.id)
        assertEquals(1, paths.attachments.listFiles()!!.count { it.extension == "blob" })
        assertEquals(CoreFixture.imageDataUri(), store.imageDataUri(first))
    }

    @Test
    fun textLimitIsCheckedBeforeWritingAnyBlob() = runBlocking {
        val paths = AppPaths(temporary.root)
        try {
            AttachmentStore(paths).importText("x".repeat(AttachmentStore.MAX_TEXT_BYTES + 1))
            fail("Oversize text accepted")
        } catch (expected: AttachmentImportException) {
            assertEquals(AttachmentImportFailure.TOO_LARGE, expected.reason)
            assertFalse(paths.attachments.exists())
        }
    }

    @Test
    fun utf8ByteSizeNotJustCharacterCountIsBounded() = runBlocking {
        val paths = AppPaths(temporary.root)
        try {
            AttachmentStore(paths).importText("界".repeat(100_000))
            fail("Multibyte text exceeded byte limit")
        } catch (expected: AttachmentImportException) {
            assertEquals(AttachmentImportFailure.TOO_LARGE, expected.reason)
            assertFalse(paths.attachments.exists())
        }
    }

    @Test
    fun binaryTextAndCorruptBase64AreTypedErrors() = runBlocking {
        val store = AttachmentStore(AppPaths(temporary.root))
        try {
            store.importText("binary\u0000payload")
            fail("Binary text accepted")
        } catch (expected: AttachmentImportException) {
            assertEquals(AttachmentImportFailure.BINARY_TEXT, expected.reason)
        }
        try {
            store.importDataUri("data:image/png;base64,%%%")
            fail("Corrupt image accepted")
        } catch (expected: AttachmentImportException) {
            assertEquals(AttachmentImportFailure.UNREADABLE, expected.reason)
        }
    }

    @Test
    fun invalidReferencesAndModifiedBlobsCannotBeRead() = runBlocking {
        val store = AttachmentStore(AppPaths(temporary.root))
        try {
            store.attachmentFile(AttachmentRef("../outside", "bad", "text/plain", 1, AttachmentKind.TEXT))
            fail("Traversal reference accepted")
        } catch (expected: AttachmentImportException) {
            assertEquals(AttachmentImportFailure.INVALID_REFERENCE, expected.reason)
        }
        val reference = store.importText("original")
        store.attachmentFile(reference).writeText("modified")
        try {
            store.readText(reference)
            fail("Modified attachment accepted")
        } catch (expected: AttachmentImportException) {
            assertEquals(AttachmentImportFailure.INVALID_REFERENCE, expected.reason)
        }
    }
}
