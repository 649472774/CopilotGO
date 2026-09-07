package com.tongxie.copilotgo.data.tools.net

import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ToolDnsRoutingTest {
    @Test
    fun waitsForReadinessBeforeReadingTheProviderClientOrConnecting() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response())
            val delegate = fixture.provider()
            val entered = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<Unit>()
            val clientReads = AtomicInteger()
            val provider = object : HttpClientProvider {
                override val client: OkHttpClient
                    get() {
                        check(ready.isCompleted)
                        clientReads.incrementAndGet()
                        return delegate.client
                    }
                override suspend fun awaitReady() {
                    entered.complete(Unit)
                    ready.await()
                }
            }
            val result = async(Dispatchers.IO) {
                ToolHttpClient(provider).withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) {
                    it.source.readUtf8()
                }
            }
            entered.await()
            assertEquals(0, clientReads.get())
            assertEquals(0, fixture.server.requestCount)
            ready.complete(Unit)
            assertEquals("fixture", result.await())
            assertTrue(clientReads.get() > 0)
        }
    }

    @Test
    fun readinessFailureIsSanitizedWithoutAccessingOrFallingBackToClient() = runBlocking {
        val reads = AtomicInteger()
        val provider = object : HttpClientProvider {
            override val client: OkHttpClient get() {
                reads.incrementAndGet()
                error("Client must not be accessed")
            }
            override suspend fun awaitReady(): Unit = throw IOException("fixture-secret https://private.invalid")
        }
        val request = okhttp3.Request.Builder().url("https://example.com/").build()
        val failure = expectNetworkFailure(ToolNetworkErrorCode.NETWORK_ERROR) {
            ToolHttpClient(provider).withResponse(request) { error("No callback") }
        }
        assertFalse(failure.toString().contains("fixture-secret"))
        assertEquals(0, reads.get())
    }

    @Test
    fun actualResolverRejectsEveryMixedOrUnsafeAnswerBeforeAnyConnection() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val lookups = AtomicInteger()
            val badAnswers = listOf(
                emptyList(), listOf(fixture.loopback), listOf(fixture.publicAddress, fixture.loopback),
                listOf(InetAddress.getByName("169.254.169.254")),
                listOf(InetAddress.getByName("::ffff:127.0.0.1")),
                listOf(InetAddress.getByName("fd00:ec2::254"))
            )
            badAnswers.forEach { answers ->
                val provider = fixture.publicProvider(fixtureDns {
                    lookups.incrementAndGet()
                    answers
                })
                expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_DNS) {
                    ToolHttpClient(provider).withResponse(fixture.request(host = "tools.example.com")) { error("No callback") }
                }
            }
            assertEquals(badAnswers.size, lookups.get())
            assertTrue(fixture.connects.isEmpty())
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun trustedLanStillRejectsAnyMetadataOrLinkLocalDnsAnswer() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            listOf("169.254.169.254", "168.63.129.16", "fd00:ec2::254", "fe80::1").forEach { literal ->
                val provider = fixture.provider {
                    dns(fixtureDns { listOf(fixture.loopback, InetAddress.getByName(literal)) })
                }
                expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_DNS) {
                    ToolHttpClient(provider).withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) {
                        error("Mixed DNS answer must fail closed")
                    }
                }
            }
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun resolverFailureDoesNotExposeTheHostnameSecretOrCause() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val provider = fixture.publicProvider(fixtureDns { throw IOException("fixture-secret from private.example.com") })
            val failure = expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_DNS) {
                ToolHttpClient(provider).withResponse(fixture.request(host = "tools.example.com")) { error("No callback") }
            }
            assertFalse(failure.toString().contains("fixture-secret"))
            assertFalse(failure.toString().contains("private.example.com"))
            assertTrue(fixture.connects.isEmpty())
        }
    }

    @Test
    fun cancellationDuringBlockedDnsCannotConnectWhenTheResolverEventuallyReturns() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val provider = fixture.publicProvider(fixtureDns {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                listOf(fixture.publicAddress)
            })
            val request = launch(Dispatchers.IO) {
                ToolHttpClient(provider).withResponse(fixture.request(host = "tools.example.com")) { error("No callback") }
            }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
                withTimeout(750) { request.cancelAndJoin() }
            } finally {
                release.countDown()
            }
            provider.client.dispatcher.executorService.shutdown()
            withContext(Dispatchers.IO) {
                assertTrue(provider.client.dispatcher.executorService.awaitTermination(3, TimeUnit.SECONDS))
            }
            assertTrue(request.isCancelled)
            assertTrue(fixture.connects.isEmpty())
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun providerRouteChangeWhileResolvingAbortsBeforeConnectInsteadOfUsingTheOldDirectRoute() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val proxied = fixture.provider {
                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 8080)))
            }.client
            val current = AtomicReference(proxied)
            val direct = fixture.publicProvider(fixtureDns {
                current.set(proxied)
                listOf(fixture.publicAddress)
            }).client
            current.set(direct)
            val provider = object : HttpClientProvider { override val client: OkHttpClient get() = current.get() }
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE) {
                ToolHttpClient(provider).withResponse(fixture.request(host = "tools.example.com")) { error("No callback") }
            }
            assertTrue(fixture.connects.isEmpty())
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun successfulPublicRequestUsesTheValidatedAddressAtConnectWithoutASecondLookup() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response())
            val lookups = AtomicInteger()
            val provider = fixture.publicProvider(fixtureDns {
                check(lookups.incrementAndGet() == 1) { "A preflight lookup is not a connect-time guarantee" }
                listOf(fixture.publicAddress)
            })
            val result = ToolHttpClient(provider).withResponse(fixture.request(host = "tools.example.com")) {
                it.source.readUtf8()
            }
            assertEquals("fixture", result)
            assertEquals(1, lookups.get())
            assertEquals(listOf(fixture.publicAddress), fixture.connects.map { it.address })
        }
    }

    @Test
    fun socketRemoteAddressMustMatchTheValidatedRouteBeforeSendingHttp() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val provider = fixture.publicProvider(reportLogicalAddress = false)
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_DNS) {
                ToolHttpClient(provider).withResponse(fixture.request(host = "tools.example.com")) { error("No callback") }
            }
            assertEquals(1, fixture.connects.size)
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun freshToolPoolDoesNotReuseAnInheritedConnectionAfterDnsRebinds() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response("warm"))
            val lookups = AtomicInteger()
            val provider = fixture.publicProvider(fixtureDns {
                if (lookups.incrementAndGet() == 1) listOf(fixture.publicAddress) else listOf(fixture.loopback)
            })
            val request = fixture.request(host = "tools.example.com")
            provider.client.newCall(request).execute().use { assertEquals("warm", it.body!!.string()) }
            assertTrue(provider.client.connectionPool.connectionCount() > 0)
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_DNS) {
                ToolHttpClient(provider).withResponse(request) { error("Inherited connection must not be reused") }
            }
            assertEquals(2, lookups.get())
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun eachToolOperationResolvesAgainInsteadOfReusingAPreviousToolConnection() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response())
            val lookups = AtomicInteger()
            val provider = fixture.publicProvider(fixtureDns {
                if (lookups.incrementAndGet() == 1) listOf(fixture.publicAddress) else listOf(fixture.loopback)
            })
            val client = ToolHttpClient(provider)
            val request = fixture.request(host = "tools.example.com")
            client.withResponse(request) { it.source.readUtf8() }
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_DNS) { client.withResponse(request) { it.source.readUtf8() } }
            assertEquals(2, lookups.get())
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun publicLiteralStillChecksConnectionEvenWhenOkHttpSkipsDns() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response())
            val provider = fixture.publicProvider(fixtureDns { throw AssertionError("Literal should not need DNS") })
            assertEquals(
                "fixture",
                ToolHttpClient(provider).withResponse(fixture.request(host = "8.8.8.8")) { it.source.readUtf8() }
            )
            assertEquals(fixture.publicAddress, fixture.connects.single().address)
        }
    }

    @Test
    fun explicitConnectAndSocksRoutesAreRejectedBeforeLookupProxyAuthOrTraffic() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val authenticated = AtomicInteger()
            val resolved = AtomicInteger()
            listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS).forEach { type ->
                val provider = fixture.provider {
                    proxy(Proxy(type, InetSocketAddress.createUnresolved("proxy.example.com", 8080)))
                    proxyAuthenticator { _, _ -> authenticated.incrementAndGet(); null }
                    dns(fixtureDns { resolved.incrementAndGet(); listOf(fixture.loopback) })
                }
                ToolNetworkPolicy.entries.forEach { policy ->
                    expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE) {
                        ToolHttpClient(provider).withResponse(fixture.request(host = "tools.example.com"), policy) {
                            error("No callback")
                        }
                    }
                }
            }
            assertEquals(0, authenticated.get())
            assertEquals(0, resolved.get())
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun actualProxySelectorCannotFallBackFromProxyToDirect() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val selections = AtomicInteger()
            val provider = fixture.publicProvider {
                proxy(null)
                proxySelector(selector {
                    selections.incrementAndGet()
                    listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 8080)), Proxy.NO_PROXY)
                })
            }
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE) {
                ToolHttpClient(provider).withResponse(fixture.request(host = "tools.example.com")) { error("No callback") }
            }
            assertEquals(1, selections.get())
            assertTrue(fixture.connects.isEmpty())
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun directSelectorWorksButExplicitDirectDoesNotConsultAnUnusedSystemProxy() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val selections = AtomicInteger()
            val direct = selector { selections.incrementAndGet(); listOf(Proxy.NO_PROXY) }
            val provider = fixture.publicProvider { proxy(null); proxySelector(direct) }
            fixture.server.enqueue(fixture.response())
            ToolHttpClient(provider).withResponse(fixture.request(host = "tools.example.com")) { it.source.readUtf8() }
            assertEquals(1, selections.get())
            val explicit = fixture.publicProvider {
                proxy(Proxy.NO_PROXY)
                proxySelector(selector { throw AssertionError("The provider explicitly chose DIRECT") })
            }
            fixture.server.enqueue(fixture.response())
            ToolHttpClient(explicit).withResponse(fixture.request(host = "tools.example.com")) { it.source.readUtf8() }
            assertEquals(2, fixture.server.requestCount)
        }
    }
}

internal fun selector(choose: (URI) -> List<Proxy>): ProxySelector = object : ProxySelector() {
    override fun select(uri: URI): List<Proxy> = choose.invoke(uri)
    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
}
