package com.tongxie.copilotgo.data.tools.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Response
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ToolHttpIsolationTest {
    @Test
    fun inheritedInterceptorsCookiesAndEventListenersNeverRunAndAppHeadersAreRemoved() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response().setHeader("Set-Cookie", "session=fixture-cookie; Secure; Path=/"))
            val leaks = AtomicInteger()
            val provider = fixture.provider {
                addInterceptor { chain ->
                    leaks.incrementAndGet()
                    chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer fixture-app-secret").build())
                }
                addNetworkInterceptor { chain -> leaks.incrementAndGet(); chain.proceed(chain.request()) }
                eventListener(object : EventListener() {
                    override fun responseHeadersEnd(call: Call, response: Response) { leaks.incrementAndGet() }
                })
                cookieJar(object : CookieJar {
                    override fun loadForRequest(url: HttpUrl): List<Cookie> {
                        leaks.incrementAndGet()
                        return listOf(Cookie.Builder().name("app").value("fixture-cookie").domain(url.host).build())
                    }
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { leaks.incrementAndGet() }
                })
            }
            val forbidden = mapOf(
                "Authorization" to "Bearer fixture-app-secret",
                "Cookie" to "app=fixture-cookie", "Cookie2" to "fixture-cookie",
                "Proxy-Authorization" to "Basic fixture-proxy-secret",
                "Host" to "metadata.google.internal", "Referer" to "https://private.invalid/fixture-secret",
                "Origin" to "https://private.invalid", "Editor-Version" to "fixture-editor",
                "Editor-Plugin-Version" to "fixture-plugin", "Copilot-Integration-Id" to "fixture-integration",
                "X-GitHub-Token" to "fixture-token", "Openai-Intent" to "fixture-intent",
                "VScode-SessionId" to "fixture-session", "VScode-MachineId" to "fixture-machine",
                "X-Request-Id" to "fixture-request", "X-Api-Key" to "fixture-api-key",
                "Mcp-Session-Id" to "fixture-tool-session", "X-Unknown-Auth" to "fixture-unknown",
                "User-Agent" to "fixture-app", "Accept-Encoding" to "br",
                "Expect" to "100-continue", "Upgrade" to "websocket"
            )
            val request = fixture.request().newBuilder().apply {
                forbidden.forEach { (name, value) -> header(name, value) }
                header("Accept", "text/plain")
            }.build()
            ToolHttpClient(provider).withResponse(request, ToolNetworkPolicy.TRUSTED_LAN_HTTPS) {
                assertNull(it.headers["Set-Cookie"])
                assertEquals("fixture", it.source.readUtf8())
                assertFalse(it.toString().contains("fixture-cookie"))
            }
            val sent = fixture.server.takeRequest()
            forbidden.keys.filterNot { it in setOf("Host", "User-Agent", "Accept-Encoding") }.forEach {
                assertNull(it, sent.getHeader(it))
            }
            assertEquals("gzip", sent.getHeader("Accept-Encoding"))
            assertEquals("CopilotGO-Tools", sent.getHeader("User-Agent"))
            assertEquals("text/plain", sent.getHeader("Accept"))
            assertEquals("close", sent.getHeader("Connection"))
            assertEquals("no-store", sent.getHeader("Cache-Control"))
            assertTrue(sent.getHeader("Host")!!.startsWith("localhost:"))
            assertEquals(0, leaks.get())
        }
    }

    @Test
    fun explicitToolCredentialsAreAllowedButNeverProxyOrAppIdentityHeaders() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response())
            val request = fixture.request().newBuilder()
                .header("Authorization", "Bearer fixture-independent-tool-key")
                .header("X-Tool-Key", "fixture-custom-tool-key")
                .header("Mcp-Session-Id", "fixture-tool-session")
                .header("Mcp-Param-filter", "fixture-filter")
                .header("Proxy-Authorization", "Basic fixture-proxy-key")
                .header("Cookie", "fixture-cookie")
                .header("Copilot-Integration-Id", "fixture-app")
                .build()
            ToolHttpClient(fixture.provider()).withResponse(
                request, ToolNetworkPolicy.TRUSTED_LAN_HTTPS, hasCredentials = true
            ) { it.source.readUtf8() }
            val sent = fixture.server.takeRequest()
            assertEquals("Bearer fixture-independent-tool-key", sent.getHeader("Authorization"))
            assertEquals("fixture-custom-tool-key", sent.getHeader("X-Tool-Key"))
            assertEquals("fixture-tool-session", sent.getHeader("Mcp-Session-Id"))
            assertEquals("fixture-filter", sent.getHeader("Mcp-Param-filter"))
            assertNull(sent.getHeader("Proxy-Authorization"))
            assertNull(sent.getHeader("Cookie"))
            assertNull(sent.getHeader("Copilot-Integration-Id"))
        }
    }

    @Test
    fun originAuthenticationChallengeDoesNotInvokeInheritedAuthenticatorOrReplay() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val authentications = AtomicInteger()
            fixture.server.enqueue(fixture.response().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=fixture"))
            fixture.server.enqueue(fixture.response("must not be requested"))
            val provider = fixture.provider {
                authenticator { _, response ->
                    authentications.incrementAndGet()
                    response.request.newBuilder().header("Authorization", "Basic fixture-app-secret").build()
                }
            }
            val status = ToolHttpClient(provider).withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) {
                it.statusCode
            }
            assertEquals(401, status)
            assertEquals(0, authentications.get())
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun inheritedCacheIsNeitherReadNorWritten() = runBlocking {
        val directory = File("build", "tool-network-cache-${UUID.randomUUID()}")
        val cache = Cache(directory, 1024 * 1024)
        try {
            ToolHttpsFixture().use { fixture ->
                val provider = fixture.provider { cache(cache) }
                fixture.server.enqueue(fixture.response("cached application response").setHeader("Cache-Control", "max-age=3600"))
                val request = fixture.request()
                provider.client.newCall(request).execute().use { it.body!!.string() }
                val hits = cache.hitCount()
                val writes = cache.writeSuccessCount()
                fixture.server.enqueue(fixture.response("fresh tool response").setHeader("Cache-Control", "max-age=3600"))
                assertEquals(
                    "fresh tool response",
                    ToolHttpClient(provider).withResponse(request, ToolNetworkPolicy.TRUSTED_LAN_HTTPS) { it.source.readUtf8() }
                )
                assertEquals(hits, cache.hitCount())
                assertEquals(writes, cache.writeSuccessCount())
                assertEquals(2, fixture.server.requestCount)
            }
        } finally {
            cache.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun callbacksParsingAndBlockingBodyReadsStayOffTheCallingUiDispatcher() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response("""{"ok":true}""").setHeader("Content-Type", "application/json"))
            val http = ToolHttpClient(fixture.provider())
            Executors.newSingleThreadExecutor { Thread(it, "fixture-ui") }.asCoroutineDispatcher().use { ui ->
                withContext(ui) {
                    http.withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) {
                        assertFalse(Thread.currentThread().name == "fixture-ui")
                        val value = kotlinx.serialization.json.Json.parseToJsonElement(it.source.readUtf8())
                        assertEquals("""{"ok":true}""", value.toString())
                        assertFalse(Thread.currentThread().name == "fixture-ui")
                    }
                }
            }
        }
    }

    @Test
    fun cancellationAfterHeadersClosesAStalledReadPromptly() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response().setBodyDelay(1, TimeUnit.SECONDS))
            val reading = CountDownLatch(1)
            val reader = launch(Dispatchers.IO) {
                ToolHttpClient(fixture.provider()).withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) {
                    reading.countDown()
                    it.source.readUtf8()
                }
            }
            withContext(Dispatchers.IO) { assertTrue(reading.await(3, TimeUnit.SECONDS)) }
            withTimeout(750) { reader.cancelAndJoin() }
            assertTrue(reader.isCancelled)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun callbackAndRevisionFailuresKeepTheirIdentityAndEscapedSourcesAreClosed() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.provider())
            val stale = IOException("Controlled revision failure")
            try {
                http.withResponse(
                    fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS,
                    assertCurrent = { throw stale }
                ) { error("No callback") }
            } catch (e: IOException) {
                assertSame(stale, e)
            }
            assertEquals(0, fixture.server.requestCount)
            fixture.server.enqueue(fixture.response())
            val current = AtomicBoolean(true)
            try {
                http.withResponse(
                    fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS,
                    assertCurrent = { if (!current.get()) throw stale }
                ) {
                    current.set(false)
                    it.source.readUtf8()
                }
                throw AssertionError("Revision change must abort")
            } catch (e: IOException) {
                assertSame(stale, e)
            }
            fixture.server.enqueue(fixture.response())
            val callbackFailure = IOException("Controlled callback failure")
            try {
                http.withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) { throw callbackFailure }
            } catch (e: IOException) {
                assertSame(callbackFailure, e)
            }
            fixture.server.enqueue(fixture.response())
            val escaped = http.withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) {
                assertTrue(it.source.request(1))
                it
            }
            assertTrue(escaped.source.buffer.exhausted())
            expectNetworkFailure(ToolNetworkErrorCode.NETWORK_ERROR) { escaped.source.readUtf8() }
            Unit
        }
    }

    @Test
    fun trustedLanDoesNotRelaxCertificateTrustOrHostnameVerification() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val platform = HandshakeCertificates.Builder().addPlatformTrustedCertificates().build()
            val untrusted = fixture.provider {
                sslSocketFactory(platform.sslSocketFactory(), platform.trustManager)
            }
            expectNetworkFailure(ToolNetworkErrorCode.NETWORK_ERROR) {
                ToolHttpClient(untrusted).withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) { error("No callback") }
            }
            val wrongName = fixture.publicProvider()
            expectNetworkFailure(ToolNetworkErrorCode.NETWORK_ERROR) {
                ToolHttpClient(wrongName).withResponse(fixture.request(host = "wrong.example.com")) { error("No callback") }
            }
            assertEquals(0, fixture.server.requestCount)
        }
    }
}
