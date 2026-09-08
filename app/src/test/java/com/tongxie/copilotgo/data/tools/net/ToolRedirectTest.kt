package com.tongxie.copilotgo.data.tools.net

import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ToolRedirectTest {
    @Test
    fun approvedGetAndHeadRedirectsAreManualAndRevalidateEveryConnection() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val lookups = AtomicInteger()
            val provider = fixture.publicProvider(fixtureDns { lookups.incrementAndGet(); listOf(fixture.publicAddress) })
            val http = ToolHttpClient(provider)
            listOf("GET", "HEAD").forEach { method ->
                listOf(301, 302, 303, 307, 308).forEach { status ->
                    fixture.server.enqueue(fixture.response("redirect").setResponseCode(status).setHeader("Location", "/final"))
                    fixture.server.enqueue(fixture.response("final"))
                    val request = fixture.request("/start", "tools.example.com").newBuilder().method(method, null).build()
                    http.withResponse(request, allowRedirects = true) {
                        assertEquals(200, it.statusCode)
                        assertEquals("/final", it.url.encodedPath)
                        assertEquals(if (method == "HEAD") "" else "final", it.source.readUtf8())
                    }
                    val first = fixture.server.takeRequest()
                    val second = fixture.server.takeRequest()
                    assertEquals(method, first.method)
                    assertEquals(method, second.method)
                    assertEquals("/final", second.path)
                    assertTrue(first.requestLine.endsWith("HTTP/1.1"))
                    assertTrue(second.requestLine.endsWith("HTTP/1.1"))
                }
            }
            assertEquals(20, lookups.get())
            assertEquals(20, fixture.connects.size)
        }
    }

    @Test
    fun allPostRedirectStatusesAreRejectedWithoutConvertingOrResendingTheRequest() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.publicProvider { retryOnConnectionFailure(true); followRedirects(true) })
            listOf(301, 302, 303, 307, 308).forEach { status ->
                fixture.server.enqueue(fixture.response().setResponseCode(status).setHeader("Location", "/tools/call"))
                val request = fixture.request("/mcp", "tools.example.com").newBuilder()
                    .post("""{"method":"tools/call","params":{"name":"fixture"}}""".toRequestBody("application/json".toMediaType()))
                    .build()
                expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT) {
                    http.withResponse(request, allowRedirects = true) { error("POST redirect cannot be followed") }
                }
                val sent = fixture.server.takeRequest()
                assertEquals("POST", sent.method)
                assertEquals("/mcp", sent.path)
                assertTrue(sent.body.readUtf8().contains("tools/call"))
                assertNull(fixture.server.takeRequest(50, TimeUnit.MILLISECONDS))
            }
            assertEquals(5, fixture.server.requestCount)
        }
    }

    @Test
    fun postNetworkFailureDoesNotReplayAnAlreadyWrittenToolsCall() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            fixture.server.enqueue(fixture.response("must never be requested"))
            val request = fixture.request("/mcp", "tools.example.com").newBuilder()
                .post("""{"method":"tools/call"}""".toRequestBody("application/json".toMediaType())).build()
            val http = ToolHttpClient(fixture.publicProvider { retryOnConnectionFailure(true) })
            expectNetworkFailure(ToolNetworkErrorCode.NETWORK_ERROR) { http.withResponse(request) { it.source.readUtf8() } }
            assertEquals(1, fixture.server.requestCount)
            assertEquals("POST", fixture.server.takeRequest().method)
        }
    }

    @Test
    fun retryAfterZeroAndRecoverableStatusCodesCannotTriggerHiddenGetOrPostReplays() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.publicProvider { retryOnConnectionFailure(true) })
            listOf("GET", "POST").forEach { method ->
                listOf(408, 503, 421).forEach { status ->
                    fixture.server.enqueue(fixture.response().setResponseCode(status).setHeader("Retry-After", "0"))
                    val request = fixture.request(host = "tools.example.com").newBuilder().apply {
                        if (method == "POST") post("fixture".toRequestBody("text/plain".toMediaType()))
                    }.build()
                    http.withResponse(request) {
                        assertEquals(status, it.statusCode)
                        assertEquals("0", it.headers["Retry-After"])
                        assertEquals("fixture", it.source.readUtf8())
                    }
                    assertEquals(method, fixture.server.takeRequest().method)
                    assertNull(fixture.server.takeRequest(50, TimeUnit.MILLISECONDS))
                }
            }
            assertEquals(6, fixture.server.requestCount)
        }
    }

    @Test
    fun defaultAndTrustedLanPoliciesDoNotGrantRedirectPermission() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response().setResponseCode(302).setHeader("Location", "/final"))
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT) {
                ToolHttpClient(fixture.publicProvider()).withResponse(fixture.request(host = "tools.example.com")) {
                    error("Redirects are opt-in")
                }
            }
            fixture.server.enqueue(fixture.response().setResponseCode(302).setHeader("Location", "/final"))
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT) {
                ToolHttpClient(fixture.provider()).withResponse(
                    fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS, allowRedirects = true
                ) { error("LAN exception applies only to the configured endpoint") }
            }
            assertEquals(2, fixture.server.requestCount)
        }
    }

    @Test
    fun redirectsRejectUnsafeSchemesUserInfoFragmentsLiteralsAndLocalNames() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.publicProvider())
            listOf(
                "http://example.com/", "https://127.0.0.1/", "https://2130706433/", "//0x7f000001/",
                "https://service.internal/", "https://[::ffff:169.254.169.254]/",
                "https://@example.com/", "https://fixture-secret@example.com/",
                "https://example.com/#fixture-secret", "/page#fragment", "//%31%32%37.0.0.1/",
                "https://example.com\\@127.0.0.1/", "file:///fixture", ""
            ).forEach { location ->
                fixture.server.enqueue(fixture.response().setResponseCode(302).setHeader("Location", location))
                expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT) {
                    http.withResponse(fixture.request(host = "tools.example.com"), allowRedirects = true) { error("No callback") }
                }
                fixture.server.takeRequest()
                assertNull(fixture.server.takeRequest(25, TimeUnit.MILLISECONDS))
            }
        }
    }

    @Test
    fun missingAmbiguousAndOverBudgetRedirectsAreRejected() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.publicProvider())
            listOf(
                fixture.response().setResponseCode(302),
                fixture.response().setResponseCode(302).addHeader("Location", "/a").addHeader("Location", "/b")
            ).forEach { response ->
                fixture.server.enqueue(response)
                expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT) {
                    http.withResponse(fixture.request(host = "tools.example.com"), allowRedirects = true) { error("No callback") }
                }
            }
            repeat(3) { fixture.server.enqueue(fixture.response().setResponseCode(302).setHeader("Location", "/again")) }
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT) {
                http.withResponse(
                    fixture.request(host = "tools.example.com"),
                    limits = ToolNetworkLimits(maxRedirects = 2), allowRedirects = true
                ) { error("Redirect loop must be bounded") }
            }
            assertEquals(5, fixture.server.requestCount)
        }
    }

    @Test
    fun credentialBearingRedirectsCannotChangeHostOrPortButCanStayOnSamePublicOrigin() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.publicProvider())
            val request = fixture.request("/start", "tools.example.com").newBuilder()
                .header("Authorization", "Bearer fixture-independent-tool-key")
                .header("X-Tool-Key", "fixture-custom-key").build()
            listOf(
                fixture.url("/final", "other.example.com"),
                fixture.url("/final", "tools.example.com").newBuilder().port(443).build()
            ).forEach { destination ->
                fixture.server.enqueue(fixture.response().setResponseCode(307).setHeader("Location", destination))
                expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT) {
                    http.withResponse(request, allowRedirects = true, hasCredentials = true) { error("No callback") }
                }
                assertEquals("Bearer fixture-independent-tool-key", fixture.server.takeRequest().getHeader("Authorization"))
                assertNull(fixture.server.takeRequest(50, TimeUnit.MILLISECONDS))
            }
            fixture.server.enqueue(fixture.response().setResponseCode(307).setHeader("Location", "/final"))
            fixture.server.enqueue(fixture.response("final"))
            assertEquals("final", http.withResponse(request, allowRedirects = true, hasCredentials = true) { it.source.readUtf8() })
            fixture.server.takeRequest()
            val followUp = fixture.server.takeRequest()
            assertEquals("Bearer fixture-independent-tool-key", followUp.getHeader("Authorization"))
            assertEquals("fixture-custom-key", followUp.getHeader("X-Tool-Key"))
        }
    }

    @Test
    fun credentialFlagProtectsNonHeaderSecretsAndPublicCrossOriginHopsKeepOnlyAccept() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.publicProvider())
            val destination = fixture.url("/final", "other.example.com")
            fixture.server.enqueue(fixture.response().setResponseCode(302).setHeader("Location", destination))
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT) {
                http.withResponse(fixture.request(host = "tools.example.com"), allowRedirects = true, hasCredentials = true) {
                    error("Non-header credentials also bind the origin")
                }
            }
            fixture.server.takeRequest()
            fixture.server.enqueue(
                fixture.response().setResponseCode(302).setHeader("Location", destination)
                    .setHeader("Set-Cookie", "fixture-cookie=secret; Path=/; Secure")
            )
            fixture.server.enqueue(fixture.response())
            val request = fixture.request(host = "tools.example.com").newBuilder()
                .header("Accept", "text/plain")
                .header("Accept-Language", "zh-CN")
                .header("Mcp-Param-filter", "fixture-origin-specific").build()
            http.withResponse(request, allowRedirects = true) { it.source.readUtf8() }
            assertEquals("fixture-origin-specific", fixture.server.takeRequest().getHeader("Mcp-Param-filter"))
            val redirected = fixture.server.takeRequest()
            assertEquals("text/plain", redirected.getHeader("Accept"))
            assertNull(redirected.getHeader("Mcp-Param-filter"))
            assertNull(redirected.getHeader("Accept-Language"))
            assertNull(redirected.getHeader("Cookie"))
        }
    }

    @Test
    fun redirectCannotReuseConnectionOrRebindToPrivateDnsAnswer() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response().setResponseCode(302).setHeader("Location", "/rebound"))
            val lookups = AtomicInteger()
            val http = ToolHttpClient(fixture.publicProvider(fixtureDns {
                if (lookups.incrementAndGet() == 1) listOf(fixture.publicAddress) else listOf(fixture.loopback)
            }))
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_DNS) {
                http.withResponse(fixture.request(host = "tools.example.com"), allowRedirects = true) { error("No callback") }
            }
            assertEquals(2, lookups.get())
            assertEquals(1, fixture.connects.size)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun routeSelectorAndReadinessAreRecheckedOnEachRedirectHop() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response().setResponseCode(302).setHeader("Location", "/next"))
            val selections = AtomicInteger()
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 8080))
            val provider = fixture.publicProvider {
                proxy(null)
                proxySelector(selector {
                    if (selections.incrementAndGet() == 1) listOf(Proxy.NO_PROXY) else listOf(proxy)
                })
            }
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE) {
                ToolHttpClient(provider).withResponse(fixture.request(host = "tools.example.com"), allowRedirects = true) {
                    error("No direct fallback")
                }
            }
            assertEquals(2, selections.get())
            assertEquals(1, fixture.server.requestCount)

            fixture.server.enqueue(fixture.response().setResponseCode(302).setHeader("Location", "/next"))
            val direct = fixture.publicProvider().client
            val proxied = fixture.provider { proxy(proxy) }.client
            val current = AtomicReference(direct)
            val readiness = AtomicInteger()
            val switching = object : HttpClientProvider {
                override val client: OkHttpClient get() = current.get()
                override suspend fun awaitReady() {
                    if (readiness.incrementAndGet() == 2) current.set(proxied)
                }
            }
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE) {
                ToolHttpClient(switching).withResponse(fixture.request(host = "tools.example.com"), allowRedirects = true) {
                    error("No direct fallback")
                }
            }
            assertEquals(2, readiness.get())
            assertEquals(2, fixture.server.requestCount)
        }
    }

    @Test
    fun oneActionDeadlineIncludesAllRedirectsRatherThanRestartingForEachCall() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(
                fixture.response().setResponseCode(302).setHeader("Location", "/final")
                    .setHeadersDelay(900, TimeUnit.MILLISECONDS)
            )
            fixture.server.enqueue(fixture.response().setHeadersDelay(900, TimeUnit.MILLISECONDS))
            val started = System.nanoTime()
            try {
                ToolHttpClient(fixture.publicProvider()).withResponse(
                    fixture.request(host = "tools.example.com"),
                    limits = ToolNetworkLimits(callTimeoutMillis = 1500), allowRedirects = true
                ) { it.source.readUtf8() }
                throw AssertionError("A redirect must not renew the action deadline")
            } catch (_: CancellationException) {
                // Coroutine deadline and OkHttp's remaining call timeout race; neither retries.
            } catch (e: ToolNetworkException) {
                assertEquals(ToolNetworkErrorCode.NETWORK_ERROR, e.code)
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000)
            assertTrue(fixture.server.requestCount in 1..2)
        }
    }
}
