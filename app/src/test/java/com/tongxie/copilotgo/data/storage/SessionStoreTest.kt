package com.tongxie.copilotgo.data.storage

import com.tongxie.copilotgo.data.chat.CoreFixture
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun store(limit: Int = 2) = SessionStore(AppPaths(temporary.root), json, maxInactiveSessions = limit)
    private fun file(name: String) = File(File(temporary.root, "sessions"), name)

    @Test
    fun metadataAndMessagesHaveOneAuthorityAndSnapshotsAreDetached() = runBlocking {
        val store = store()
        try {
            store.save(Session("session", "original", "fixture"))
            val snapshot = store.getSession("session")!!
            snapshot.title = "caller mutation"
            snapshot.messages.add(UiMessage("unowned", "user", "not saved"))
            store.rename("session", "renamed")
            store.setPinned("session", true)
            store.update("session") { it.copy(messages = mutableListOf(UiMessage("message", "user", "saved"))) }
            val current = store.getSession("session")!!
            assertEquals("renamed", current.title)
            assertTrue(current.pinned)
            assertEquals(listOf("message"), current.messages.map { it.id })
            assertEquals("renamed", store.summaries.value.single().title)
            assertEquals(1, store.summaries.value.single().messageCount)
        } finally { store.close() }
    }

    @Test
    fun staleSnapshotCannotRollbackMetadata() = runBlocking {
        val store = store()
        try {
            store.save(Session("session", "original", "fixture"))
            val old = store.getSession("session")!!
            store.rename("session", "renamed")
            try {
                store.save(old)
                fail("Stale snapshot overwrote live metadata")
            } catch (_: SessionConflictException) {
                assertEquals("renamed", store.getSession("session")!!.title)
            }
        } finally { store.close() }
    }

    @Test
    fun failedAtomicWriteKeepsLastGoodFileAndReportsFailure() = runBlocking {
        val store = store()
        try {
            store.save(Session("session", "original", "fixture"))
            val original = file("session.json").readBytes()
            assertTrue(file("session.json.tmp").mkdir())
            try {
                store.rename("session", "new title")
                fail("A disk failure was hidden")
            } catch (_: IOException) {
                assertArrayEquals(original, file("session.json").readBytes())
                assertEquals("original", store.getSession("session")!!.title)
                assertTrue(store.issues.value.isNotEmpty())
            }
        } finally { store.close() }
    }

    @Test
    fun corruptPrimaryRecoversBackupAndRetainsDamagedOriginal() = runBlocking {
        val first = store()
        first.save(Session("session", "backup title", "fixture"))
        first.rename("session", "latest title")
        first.close()
        file("session.json").writeText("{broken")
        val reopened = store()
        try {
            reopened.load()
            assertEquals("backup title", reopened.getSession("session")!!.title)
            assertTrue(reopened.issues.value.any { it.recovered })
            assertTrue(file("session.json").parentFile.listFiles()!!.any { it.name.startsWith("session.json.corrupt-") })
        } finally { reopened.close() }
    }

    @Test
    fun unrecoverableConversationRemainsVisibleInsteadOfDisappearing() = runBlocking {
        file("session.json").parentFile.mkdirs()
        file("session.json").writeText("{broken")
        val store = store()
        try {
            store.load()
            assertEquals(listOf("session"), store.summaries.value.map { it.id })
            assertNotNull(store.summaries.value.single().loadError)
            try {
                store.getSession("session")
                fail("Corruption should be explicit")
            } catch (_: IOException) {
                assertEquals("{broken", file("session.json").readText())
            }
        } finally { store.close() }
    }

    @Test
    fun pendingUpdateCannotRecreateDeletedFileOrBackup() = runBlocking {
        val store = store()
        try {
            store.save(Session("session", "original", "fixture"))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val save = async(Dispatchers.IO) {
                try {
                    store.update("session") {
                        entered.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                        it.copy(title = "stale save")
                    }
                    false
                } catch (_: SessionDeletedException) { true }
            }
            withContext(Dispatchers.IO) { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
            store.markDeleting("session")
            val deletion = async(Dispatchers.IO) { store.delete("session") }
            release.countDown()
            assertTrue(save.await())
            deletion.await()
            assertFalse(file("session.json").exists())
            assertFalse(file("session.json.bak").exists())
            assertTrue(file("session.deleted").isFile)
        } finally { store.close() }
        val reopened = store()
        try {
            reopened.load()
            assertTrue(reopened.summaries.value.isEmpty())
        } finally { reopened.close() }
    }

    @Test
    fun externallyRemovedSessionCannotBeRestoredByLaterSaveOrBackupScan() = runBlocking {
        val store = store()
        try {
            store.save(Session("session", "original", "fixture"))
            store.rename("session", "backup exists")
            assertTrue(file("session.json").delete())
            try {
                store.update("session") { it.copy(title = "must not return") }
                fail("External deletion resurrected a conversation")
            } catch (_: SessionDeletedException) {
                assertFalse(file("session.json").exists())
            }
        } finally { store.close() }
        val reopened = store()
        try {
            reopened.load()
            assertTrue(reopened.summaries.value.isEmpty())
        } finally { reopened.close() }
    }

    @Test
    fun legacyImageBytesMoveOutOfJsonAndInterruptedMessagesAreRepaired() = runBlocking {
        val image = CoreFixture.imageDataUri()
        file("legacy.json").parentFile.mkdirs()
        file("legacy.json").writeText(
            """{"id":"legacy","title":"历史 CJK 😀","model":"retired-fixture","pinned":true,"messages":[{"id":"u","role":"user","content":"图片","imageUrls":["$image"]},{"id":"a","role":"assistant","content":"","isStreaming":true}]}"""
        )
        val store = store()
        try {
            store.load()
            val session = store.getSession("legacy")!!
            assertEquals("历史 CJK 😀", session.title)
            assertEquals("retired-fixture", session.model)
            assertTrue(session.pinned)
            val user = session.messages.first()
            assertTrue(user.imageUrls.isEmpty())
            assertEquals(1, user.attachments.size)
            assertEquals(image, store.attachments.imageDataUri(user.attachments.single()))
            assertFalse(file("legacy.json").readText().contains("data:image"))
            assertTrue(file("legacy.json.bak").readText().contains("data:image"))
            assertEquals("[已中断]", session.messages.last().content)
            assertFalse(session.messages.last().isStreaming)
            assertTrue(store.summaries.value.single().hasImages)
        } finally { store.close() }
    }

    @Test
    fun inactiveCacheIsBoundedButRetainedSessionIsNotEvicted() = runBlocking {
        val store = store(limit = 2)
        try {
            store.save(Session("active", "active", "fixture"))
            store.retain("active")
            val active = store.sessionFlow("active")
            for (index in 1..12) {
                store.save(Session("session-$index", "title", "fixture"))
                store.getSession("session-$index")
            }
            assertTrue(store.cachedSessionCount <= 3)
            store.rename("active", "still live")
            assertEquals("still live", active.value!!.title)
            assertEquals(13, store.summaries.value.size)
            store.release("active")
            assertTrue(store.cachedSessionCount <= 2)
        } finally { store.close() }
    }

    @Test
    fun traversalIdentifiersAreRejected() = runBlocking {
        val store = store()
        try {
            try {
                store.getSession("../outside")
                fail("Unsafe identifier accepted")
            } catch (_: IllegalArgumentException) {
                assertFalse(File(temporary.root.parentFile, "outside.json").exists())
            }
        } finally { store.close() }
    }
}
