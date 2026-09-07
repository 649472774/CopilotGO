package com.tongxie.copilotgo.data.storage

import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.CopilotTokenClient
import com.tongxie.copilotgo.data.auth.CredentialStore
import com.tongxie.copilotgo.data.auth.DeviceFlowClient
import com.tongxie.copilotgo.data.auth.StoredCredentials
import com.tongxie.copilotgo.data.chat.AttachmentKind
import com.tongxie.copilotgo.data.chat.AttachmentRef
import com.tongxie.copilotgo.data.chat.ChatStreamCenter
import com.tongxie.copilotgo.data.chat.CopilotChatClient
import com.tongxie.copilotgo.data.chat.ModelCatalog
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.SessionLoadState
import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in only: operates on the three preserved, hash-attested integration fixtures, never seeds them. */
@RunWith(AndroidJUnit4::class)
class GoldenSessionMigrationInstrumentedTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    @Test
    fun preservedGoldenSessionsMigrateAndSurviveTwoReopens() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val optIn = arguments.getString("goldenSessions")
        assumeTrue("Preserved golden-data acceptance requires explicit runner opt-in", optIn != null)
        assertEquals("Unrecognized golden fixture opt-in", OPT_IN, optIn)
        val mode = arguments.getString("goldenSessionsMode")
        assertTrue("Mode must be migrate-first or verify-migrated", mode in setOf(MIGRATE, VERIFY))
        val suppliedRoot = requireNotNull(arguments.getString("goldenSessionsRoot")) {
            "The exact attested production data root must be provided"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Only the fixture debug package is permitted", "com.tongxie.copilotgo.debug", context.packageName)
        assertTrue("Only the reserved API 31/36 lanes are permitted", Build.VERSION.SDK_INT in setOf(31, 36))

        withContext(Dispatchers.IO) {
            val paths = AppPaths(context)
            assertTrue("An absolute fixture root is required", File(suppliedRoot).isAbsolute)
            assertEquals("Runner root must match production AppPaths", File(suppliedRoot).canonicalFile, paths.root.canonicalFile)
            val external = requireNotNull(context.getExternalFilesDir(null)) { "External fixture volume is unavailable" }
            assertEquals(File(external, "CopilotGoData").canonicalFile, paths.root.canonicalFile)
            assertTrue("Golden sessions must already exist; this test never seeds data", paths.sessions.isDirectory)

            if (mode == VERIFY) {
                // This mode performs only bounded reads; it never constructs a store or writes a cache.
                val originals = readOriginals(paths, migrated = true)
                assertMigratedDisk(paths, originals)
            } else {
                val originals = readOriginals(paths, migrated = false)
                assertTrue("The first migration must not encounter preexisting blobs",
                    !paths.attachments.exists() || fileNames(paths.attachments).isEmpty())
                var firstHashes: Map<String, String>? = null
                repeat(3) {
                    loadThroughProductionCenter(paths, originals)
                    val hashes = assertMigratedDisk(paths, originals)
                    if (firstHashes == null) firstHashes = hashes
                    else assertEquals("Reopen must not rewrite migrated primaries, backups, or image bytes", firstHashes, hashes)
                }
            }
        }
    }

    private fun readOriginals(paths: AppPaths, migrated: Boolean): Map<String, ByteArray> {
        val primaryNames = HASHES.keys.map { "$it.json" }.toSet()
        val expectedNames = if (migrated) {
            primaryNames + HASHES.keys.map { "$it.summary" } + CHANGED_IDS.map { "$it.json.bak" }
        } else primaryNames
        assertEquals("Unexpected files: refuse to touch unrecognized app data", expectedNames, fileNames(paths.sessions))
        return HASHES.mapValues { (id, expectedHash) ->
            val name = if (migrated && id in CHANGED_IDS) "$id.json.bak" else "$id.json"
            val bytes = readBounded(File(paths.sessions, name), paths.sessions)
            assertEquals("Preserved original hash mismatch for $id; do not reseed or relax this guard", expectedHash, sha256(bytes))
            assertEquals("Fixture ID must match its exact file", id, decode(bytes).id)
            bytes
        }.also { originals ->
            assertEquals(3, originals.size)
            assertEquals(6, originals.values.sumOf { decode(it).messages.size })
            assertEquals(IMAGE_HASH, sha256(originalImage(originals)))
        }
    }

    private suspend fun loadThroughProductionCenter(paths: AppPaths, originals: Map<String, ByteArray>) {
        val job = SupervisorJob()
        val ioScope = CoroutineScope(job + Dispatchers.IO)
        val accessAttempts = AtomicInteger()
        val networkAttempts = AtomicInteger()
        val credentials = object : CredentialStore {
            override suspend fun readCredentials(): StoredCredentials {
                accessAttempts.incrementAndGet()
                throw AssertionError("Golden session acceptance must not read credentials")
            }
            override suspend fun writeCredentials(credentials: StoredCredentials) {
                accessAttempts.incrementAndGet()
                throw AssertionError("Golden session acceptance must not write credentials")
            }
        }
        val provider = object : HttpClientProvider {
            override val client = OkHttpClient.Builder().addInterceptor {
                networkAttempts.incrementAndGet()
                throw AssertionError("Golden session acceptance must not make network requests")
            }.build()
        }
        val auth = AuthRepository(credentials, DeviceFlowClient(provider, json), CopilotTokenClient(provider, json))
        val client = CopilotChatClient(provider, json, auth)
        val catalog = ModelCatalog(client, json, auth, scope = ioScope)
        val store = SessionStore(paths, json, scope = ioScope)
        val center = ChatStreamCenter(store, client, catalog, CoroutineScope(job + Dispatchers.Main.immediate))
        HASHES.keys.forEach(center::retain)
        try {
            val loaded = HASHES.keys.associateWith { id ->
                val sessionFlow = center.sessionFlow(id)
                val state = withTimeout(30_000) { center.loadState(id).first { it != SessionLoadState.Loading } }
                assertEquals("Golden session failed to load: $id", SessionLoadState.Ready, state)
                val session = requireNotNull(sessionFlow.value)
                assertSession(session, requireNotNull(originals[id]))
                assertEquals("Store and stream center must agree", session, store.getSession(id))
                assertFalse(center.sendingFlow(id).value)
                assertNull(center.errorFlow(id).value)
                session
            }
            assertEquals(6, loaded.values.sumOf { it.messages.size })
            assertEquals(HASHES.keys, store.summaries.value.map { it.id }.toSet())
            assertEquals(6, store.summaries.value.sumOf { it.messageCount })
            assertEquals(IMAGE_ID, store.summaries.value.first().id)
            store.summaries.value.forEach { summary ->
                val full = loaded.getValue(summary.id)
                assertEquals(full.title, summary.title)
                assertEquals(full.model, summary.model)
                assertEquals(full.pinned, summary.pinned)
                assertEquals(full.revision, summary.revision)
                assertEquals(summary.id == IMAGE_ID, summary.hasImages)
                assertNull(summary.loadError)
            }
            assertTrue("A successful fixture migration must not hide storage issues", store.issues.value.isEmpty())
            assertFalse(store.loading.value)
        } finally {
            HASHES.keys.forEach(center::release)
            center.close()
            catalog.close()
            store.close()
            job.cancelAndJoin()
            provider.client.connectionPool.evictAll()
            provider.client.dispatcher.executorService.shutdown()
        }
        assertEquals("No credential collaborator may have been invoked", 0, accessAttempts.get())
        assertEquals("No network request may have been attempted", 0, networkAttempts.get())
    }

    private fun assertMigratedDisk(paths: AppPaths, originals: Map<String, ByteArray>): Map<String, String> {
        // Re-attest recoverable originals on every reopen and in the read-only milestone mode.
        val retained = readOriginals(paths, migrated = true)
        originals.forEach { (id, bytes) -> assertArrayEquals("Recovery source changed: $id", bytes, retained.getValue(id)) }
        val hashes = linkedMapOf<String, String>()
        HASHES.keys.forEach { id ->
            val actual = readBounded(File(paths.sessions, "$id.json"), paths.sessions)
            val original = originals.getValue(id)
            val expected = if (id in CHANGED_IDS) {
                json.encodeToString(Session.serializer(), expectedMigrated(decode(original))).toByteArray(Charsets.UTF_8)
            } else original
            assertEquals("Unexpected migrated primary content for $id", sha256(expected), sha256(actual))
            assertSession(decode(actual), original)
            hashes["$id.json"] = sha256(actual)
            if (id in CHANGED_IDS) hashes["$id.json.bak"] = sha256(retained.getValue(id))
        }
        assertEquals("Only the attested golden image blob may exist", setOf("$IMAGE_HASH.blob"), fileNames(paths.attachments))
        val bytes = readBounded(File(paths.attachments, "$IMAGE_HASH.blob"), paths.attachments)
        assertEquals(120, bytes.size)
        assertEquals(IMAGE_HASH, sha256(bytes))
        assertArrayEquals("Historical image bytes must survive migration", originalImage(originals), bytes)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Migrated PNG is not decodable" }
        try {
            assertEquals(1, bitmap.width)
            assertEquals(1, bitmap.height)
        } finally {
            bitmap.recycle()
        }
        hashes["$IMAGE_HASH.blob"] = sha256(bytes)
        return hashes
    }

    private fun assertSession(actual: Session, originalBytes: ByteArray) {
        val original = decode(originalBytes)
        val expected = expectedMigrated(original)
        assertEquals(expected.id, actual.id)
        assertEquals(expected.title, actual.title)
        assertEquals(expected.model, actual.model)
        assertEquals(expected.pinned, actual.pinned)
        assertEquals(expected.revision, actual.revision)
        assertEquals(2, actual.messages.size)
        if (actual.id == MINIMAL_ID) {
            // Omitted timestamps are runtime defaults, not persisted legacy metadata.
            assertTrue(actual.createdAt > 0 && actual.updatedAt > 0)
            assertFalse(actual.pinned)
            assertEquals(0L, actual.revision)
        } else {
            assertEquals(expected.createdAt, actual.createdAt)
            assertEquals(expected.updatedAt, actual.updatedAt)
        }
        actual.messages.zip(expected.messages).forEach { (message, wanted) ->
            assertEquals(wanted.id, message.id)
            assertEquals(wanted.role, message.role)
            assertEquals(wanted.content, message.content)
            if (actual.id == MINIMAL_ID) assertTrue(message.createdAt > 0)
            else assertEquals(wanted.createdAt, message.createdAt)
            assertFalse("Interrupted messages must not remain streaming", message.isStreaming)
            assertEquals(wanted.finishReason, message.finishReason)
            assertEquals(wanted.imageUrls, message.imageUrls)
            assertEquals(wanted.attachments, message.attachments)
            assertNull(message.submissionId)
        }
    }

    private fun expectedMigrated(original: Session): Session = original.copy(
        revision = original.revision + if (original.id in CHANGED_IDS) 1 else 0,
        messages = original.messages.map { message ->
            message.copy(
                content = if (message.isStreaming && message.content.isEmpty()) "[\u5df2\u4e2d\u65ad]" else message.content,
                isStreaming = false,
                finishReason = if (message.isStreaming) "interrupted" else null,
                imageUrls = emptyList(),
                attachments = if (message.imageUrls.isEmpty()) emptyList() else listOf(
                    AttachmentRef(IMAGE_HASH, "\u56fe\u7247", "image/png", 120L, AttachmentKind.IMAGE)
                )
            )
        }.toMutableList()
    )

    private fun originalImage(originals: Map<String, ByteArray>): ByteArray {
        val url = decode(originals.getValue(IMAGE_ID)).messages.first().imageUrls.single()
        assertTrue(url.startsWith("data:image/png;base64,"))
        return Base64.getDecoder().decode(url.substringAfter(',')).also { assertEquals(120, it.size) }
    }

    private fun fileNames(directory: File): Set<String> {
        assertTrue("Expected existing fixture directory", directory.isDirectory)
        val files = requireNotNull(directory.listFiles()) { "Fixture directory is not readable" }
        files.forEach {
            assertTrue("Unexpected non-file in fixture directory", it.isFile)
            assertEquals("Symlink outside fixture directory is forbidden", directory.canonicalFile, it.canonicalFile.parentFile)
        }
        return files.map { it.name }.toSet()
    }

    private fun readBounded(file: File, directory: File): ByteArray {
        assertEquals(directory.canonicalFile, file.canonicalFile.parentFile)
        assertTrue("Fixture is missing or oversized: ${file.name}", file.isFile && file.length() in 1..MAX_FIXTURE_BYTES)
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                assertTrue("Fixture grew beyond its fixed bound", output.size().toLong() + count <= MAX_FIXTURE_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): Session = json.decodeFromString(Session.serializer(), bytes.toString(Charsets.UTF_8))
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val OPT_IN = "preserved-v0.1.33-216ac26"
        const val MIGRATE = "migrate-first"
        const val VERIFY = "verify-migrated"
        const val MAX_FIXTURE_BYTES = 16L * 1024
        const val MINIMAL_ID = "10000000-0000-4000-8000-000000000001"
        const val IMAGE_ID = "10000000-0000-4000-8000-000000000002"
        const val EMPTY_ID = "10000000-0000-4000-8000-000000000003"
        const val IMAGE_HASH = "f98117bb99165f1974c909dc0c224f3d1fafdf3ba7dfd7b84a1278143781dec5"
        val CHANGED_IDS = setOf(IMAGE_ID, EMPTY_ID)
        val HASHES = linkedMapOf(
            MINIMAL_ID to "abfd238ec59df6fb5e446ea950a99b2371a327dcdfe5f783dd1e7b704b0e2ced",
            IMAGE_ID to "3295272e990d73c68314b540f43060b0fe323e0edbb15875747020aa1dae1ad0",
            EMPTY_ID to "6c28c5b937c36bdd5c04f0513b329e4e7fb952ecd4a5eee4afdc5024d5ba8734"
        )
    }
}
