package com.tongxie.copilotgo.data.update

import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UpdateNetworkTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val clients = mutableListOf<OkHttpClient>()
    private val payload = "controlled APK fixture bytes".toByteArray()
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val policy = object : UpdateUrlPolicy {
        override fun requireTrusted(url: HttpUrl) {
            if (url.host != server.hostName || url.port != server.port) throw UpdateException("Untrusted test endpoint")
        }
    }

    @Before fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After fun closeServer() {
        clients.forEach {
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
        server.close()
    }

    private fun checker(
        client: OkHttpClient = OkHttpClient(),
        limits: UpdateLimits = UpdateLimits(),
        readiness: CompletableDeferred<Unit>? = null,
        debugPackage: Boolean = true
    ): UpdateChecker {
        clients += client
        val provider = object : HttpClientProvider {
            override val client = client
            override suspend fun awaitReady() { readiness?.await() }
        }
        return UpdateChecker(
            provider, Json { ignoreUnknownKeys = true }, "0.1.33-debug",
            latestReleaseApi = server.url("/latest").toString(),
            limits = limits, urlPolicy = policy, debugPackage = debugPackage
        )
    }

    private fun info() = UpdateInfo(
        "0.2.0", "v0.2.0", "Version 0.2.0", "Controlled notes",
        server.url("/app.apk").toString(), payload.size.toLong(), "https://github.com/649472774/CopilotGO/releases",
        apkName = "app-debug.apk", apkSha256 = hash
    )

    private fun release(version: String = "0.2.0", digest: String? = "sha256:$hash") =
        Json.encodeToString(GithubRelease(
            tagName = "v$version", assets = listOf(GithubAsset(
                name = "app-debug.apk", browserDownloadUrl = server.url("/app.apk").toString(),
                size = payload.size.toLong(), digest = digest
            ))
        ))

    private fun apkResponse() = MockResponse().setBody(Buffer().write(payload))
    private fun part(target: File) = File(target.parentFile, "${target.name}.part")

    @Test fun debug_suffix_does_not_offer_same_release_again() = runBlocking {
        server.enqueue(MockResponse().setBody(release("0.1.33", null)))
        assertTrue(checker().check() is UpdateChecker.CheckResult.UpToDate)
    }

    @Test fun update_carries_exact_asset_digest() = runBlocking {
        server.enqueue(MockResponse().setBody(release()))
        val result = checker().check() as UpdateChecker.CheckResult.Available
        assertEquals(hash, result.info.apkSha256)
        assertEquals(payload.size.toLong(), result.info.apkSize)
    }

    @Test fun known_debug_artifact_is_not_offered_to_release_channel() = runBlocking {
        server.enqueue(MockResponse().setBody(release()))
        assertTrue(checker(debugPackage = false).check() is UpdateChecker.CheckResult.Failed)
    }

    @Test fun missing_integrity_and_malformed_version_are_not_up_to_date() = runBlocking {
        server.enqueue(MockResponse().setBody(release(digest = null)))
        server.enqueue(MockResponse().setBody(release(version = "unknown")))
        val checker = checker()
        assertTrue(checker.check() is UpdateChecker.CheckResult.Failed)
        assertTrue(checker.check() is UpdateChecker.CheckResult.Failed)
    }

    @Test fun entire_metadata_body_is_consumed_off_calling_ui_dispatcher() = runBlocking {
        val readOnUi = AtomicBoolean()
        server.enqueue(MockResponse().setBody(release()))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val original = requireNotNull(response.body)
            val source = object : ForwardingSource(original.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (Thread.currentThread().name == "update-fixture-ui") readOnUi.set(true)
                    return super.read(sink, byteCount)
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source() = source
            }).build()
        }.build()
        val updater = checker(client)
        Executors.newSingleThreadExecutor { Thread(it, "update-fixture-ui") }.asCoroutineDispatcher().use { ui ->
            assertTrue(withContext(ui) { updater.check() } is UpdateChecker.CheckResult.Available)
        }
        assertFalse(readOnUi.get())
    }

    @Test fun metadata_size_bound_applies_to_chunked_bodies() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("x".repeat(2048), 32))
        val result = checker(limits = UpdateLimits(maxMetadataBytes = 128)).check()
        assertTrue(result is UpdateChecker.CheckResult.Failed)
    }

    @Test fun check_deadline_includes_body_not_only_headers() = runBlocking {
        server.enqueue(MockResponse().setBody(release()).setBodyDelay(2, TimeUnit.SECONDS))
        val result = withTimeout(1500) { checker(limits = UpdateLimits(checkTimeoutMillis = 200)).check() }
        assertTrue(result is UpdateChecker.CheckResult.Failed)
    }

    @Test fun cancelled_check_is_not_converted_to_a_failure_result() = runBlocking {
        val started = CountDownLatch(1)
        server.enqueue(MockResponse().setBody(release()).setBodyDelay(2, TimeUnit.SECONDS))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request()).also { started.countDown() }
        }.build()
        val updater = checker(client)
        val job = async { updater.check() }
        withContext(Dispatchers.IO) { assertTrue(started.await(3, TimeUnit.SECONDS)) }
        withTimeout(750) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test fun awaits_provider_readiness_before_any_request() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().setBody(release()))
        val job = async { checker(readiness = ready).check() }
        kotlinx.coroutines.yield()
        assertEquals(0, server.requestCount)
        ready.complete(Unit)
        assertTrue(withTimeout(3000) { job.await() } is UpdateChecker.CheckResult.Available)
    }

    @Test fun successful_download_atomically_replaces_only_after_verification() = runBlocking {
        server.enqueue(apkResponse())
        val target = folder.newFile("update.apk").apply { writeText("previous valid download") }
        val events = mutableListOf<UpdateChecker.DownloadEvent>()
        checker().download(info(), target).collect {
            events += it
        }
        assertArrayEquals(payload, target.readBytes())
        assertEquals(hash, (events.last() as UpdateChecker.DownloadEvent.Done).sha256)
        assertTrue(events.indexOf(UpdateChecker.DownloadEvent.Verifying) < events.lastIndex)
        assertFalse(part(target).exists())
    }

    @Test fun checksum_mismatch_keeps_previous_file_and_removes_partial() = runBlocking {
        server.enqueue(apkResponse())
        val target = folder.newFile("update.apk").apply { writeText("previous") }
        expectDownloadFailure(checker(), info().copy(apkSha256 = "f".repeat(64)), target)
        assertEquals("previous", target.readText())
        assertFalse(part(target).exists())
    }

    @Test fun truncated_and_oversized_chunked_bodies_are_rejected() = runBlocking {
        val updater = checker()
        val target = File(folder.root, "update.apk")
        server.enqueue(MockResponse().setChunkedBody("short", 2))
        expectDownloadFailure(updater, info(), target)
        server.enqueue(MockResponse().setChunkedBody("x".repeat(payload.size + 1), 8))
        expectDownloadFailure(updater, info(), target)
        assertFalse(target.exists())
        assertFalse(part(target).exists())
    }

    @Test fun download_size_cap_is_checked_before_request() = runBlocking {
        val updater = checker(limits = UpdateLimits(maxApkBytes = 4))
        expectDownloadFailure(updater, info(), File(folder.root, "update.apk"))
        assertEquals(0, server.requestCount)
    }

    @Test fun download_deadline_cancels_socket_and_removes_partial() = runBlocking {
        server.enqueue(apkResponse().setBodyDelay(2, TimeUnit.SECONDS))
        val updater = checker(limits = UpdateLimits(downloadTimeoutMillis = 200))
        val target = File(folder.root, "update.apk")
        withTimeout(1500) { expectDownloadFailure(updater, info(), target) }
        assertFalse(target.exists())
        assertFalse(part(target).exists())
    }

    @Test fun cancellation_after_headers_cleans_partial_and_allows_next_download() = runBlocking {
        server.enqueue(apkResponse().setBodyDelay(2, TimeUnit.SECONDS))
        server.enqueue(apkResponse())
        val target = File(folder.root, "update.apk")
        val updater = checker()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            updater.download(info(), target).collect {
                if (it is UpdateChecker.DownloadEvent.Progress) started.complete(Unit)
            }
        }
        withTimeout(3000) { started.await() }
        withTimeout(750) { job.cancelAndJoin() }
        assertFalse(target.exists())
        assertFalse(part(target).exists())
        assertTrue(updater.download(info(), target).toList().last() is UpdateChecker.DownloadEvent.Done)
    }

    @Test fun overlapping_download_is_rejected_without_touching_active_partial() = runBlocking {
        server.enqueue(apkResponse().setBodyDelay(2, TimeUnit.SECONDS))
        val target = File(folder.root, "update.apk")
        val updater = checker()
        val started = CompletableDeferred<Unit>()
        val first = launch {
            updater.download(info(), target).collect {
                if (it is UpdateChecker.DownloadEvent.Progress) started.complete(Unit)
            }
        }
        withTimeout(3000) { started.await() }
        expectDownloadFailure(updater, info(), target)
        assertEquals(1, server.requestCount)
        withTimeout(750) { first.cancelAndJoin() }
        assertFalse(part(target).exists())
    }

    @Test fun checksum_sidecar_is_verified_before_publishing() = runBlocking {
        server.enqueue(MockResponse().setBody("$hash  app-debug.apk\n"))
        server.enqueue(apkResponse())
        val target = File(folder.root, "update.apk")
        val sidecarInfo = info().copy(apkSha256 = null, checksumUrl = server.url("/SHA256SUMS").toString())
        val last = checker().download(sidecarInfo, target).toList().last() as UpdateChecker.DownloadEvent.Done
        assertEquals(hash, last.sha256)
        assertEquals("/SHA256SUMS", server.takeRequest().path)
    }

    @Test fun redirects_are_bounded_and_untrusted_redirects_never_followed() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/latest")) }
        val updater = checker(limits = UpdateLimits(maxRedirects = 2))
        assertTrue(updater.check() is UpdateChecker.CheckResult.Failed)
        assertEquals(3, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/private"))
        assertTrue(updater.check() is UpdateChecker.CheckResult.Failed)
        assertEquals(4, server.requestCount)
    }

    @Test fun uses_configured_proxy_unless_user_explicitly_chooses_direct() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.start()
            proxy.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(release()))
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.hostName, proxy.port))).build()
            val updater = checker(client)
            assertTrue(updater.check() is UpdateChecker.CheckResult.Failed)
            assertEquals(1, proxy.requestCount)
            assertEquals("No silent direct fallback", 0, server.requestCount)
            assertTrue(updater.check(UpdateRoute.DIRECT) is UpdateChecker.CheckResult.Available)
            assertEquals(1, proxy.requestCount)
            assertEquals(1, server.requestCount)
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    private suspend fun expectDownloadFailure(updater: UpdateChecker, info: UpdateInfo, target: File) {
        try {
            updater.download(info, target).toList()
            fail("Invalid/incomplete download was accepted")
        } catch (expected: UpdateException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }
}
