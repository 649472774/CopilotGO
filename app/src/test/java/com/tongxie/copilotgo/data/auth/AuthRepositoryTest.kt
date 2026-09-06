package com.tongxie.copilotgo.data.auth

import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AuthRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun cancellingLoginClearsWaitingStateAndAllowsRetry() = runBlocking {
        Fixture().use { fixture ->
            fixture.server.enqueue(deviceCode())
            fixture.auth.beginDeviceLogin()
            assertTrue(fixture.auth.state.value is AuthState.AwaitingUserAuthorization)
            fixture.auth.cancelLogin()
            assertEquals(AuthState.NotLoggedIn, fixture.auth.state.value)
            assertFalse(fixture.auth.busy.value)
            fixture.server.enqueue(deviceCode())
            fixture.auth.beginDeviceLogin()
            assertTrue(fixture.auth.state.value is AuthState.AwaitingUserAuthorization)
            fixture.auth.cancelLogin()
        }
    }

    @Test
    fun requestFailurePublishesFailureInsteadOfLeavingSpinner() = runBlocking {
        Fixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            try {
                fixture.auth.beginDeviceLogin()
                fail("Expected request failure")
            } catch (_: java.io.IOException) {
                assertTrue(fixture.auth.state.value is AuthState.Failed)
                assertFalse(fixture.auth.busy.value)
            }
        }
    }

    @Test
    fun logoutWinsAgainstInFlightTokenRefresh() = runBlocking {
        Fixture().use { fixture ->
            fixture.store.credentials = StoredCredentials("fixture-github", TokenStore.CachedCopilot(
                "fixture-old", 0, "fixture", fixture.server.url("/").toString().trimEnd('/')
            ))
            fixture.server.enqueue(MockResponse().setBody(
                """{"token":"fixture-fresh","expires_at":${System.currentTimeMillis() / 1000 + 3600}}"""
            ).setBodyDelay(400, TimeUnit.MILLISECONDS))
            val refresh = async(Dispatchers.Default) { fixture.auth.getValidCopilotSession() }
            assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            fixture.auth.logout()
            try {
                refresh.await()
                fail("A stale refresh was accepted after logout")
            } catch (_: CancellationException) {
                assertEquals(StoredCredentials(), fixture.store.credentials)
                assertEquals(AuthState.NotLoggedIn, fixture.auth.state.value)
            }
        }
    }

    @Test
    fun failedLogoutIsNotReportedAsLoggedOut() = runBlocking {
        Fixture().use { fixture ->
            fixture.store.credentials = StoredCredentials("fixture-github")
            fixture.auth.bootstrap()
            fixture.store.failWrites = true
            try {
                fixture.auth.logout()
                fail("Expected durable clear failure")
            } catch (_: java.io.IOException) {
                assertTrue(fixture.auth.state.value is AuthState.Failed)
                assertFalse(fixture.auth.loggingOut.value)
                assertEquals("fixture-github", fixture.store.credentials.githubToken)
            }
        }
    }

    @Test
    fun cachedSessionKeepsTheFixtureBearerAndDoesNotExchange() = runBlocking {
        Fixture().use { fixture ->
            fixture.store.credentials = StoredCredentials("fixture-github", TokenStore.CachedCopilot(
                "fixture-cached", System.currentTimeMillis() / 1000 + 3600,
                "fixture", fixture.server.url("/").toString().trimEnd('/')
            ))
            assertEquals("fixture-cached", fixture.auth.getValidCopilotSession().token)
            assertEquals(0, fixture.server.requestCount)
        }
    }

    private fun deviceCode() = MockResponse().setBody(
        """{"device_code":"fixture","user_code":"CODE","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""
    )

    private inner class Fixture : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val provider = object : HttpClientProvider { override val client = OkHttpClient() }
        val store = MemoryCredentialStore()
        val auth = AuthRepository(store,
            DeviceFlowClient(provider, json, deviceCodeUrl = server.url("/code").toString()),
            CopilotTokenClient(provider, json, server.url("/token").toString())
        )

        override fun close() {
            provider.client.connectionPool.evictAll()
            provider.client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }
}

internal class MemoryCredentialStore : CredentialStore {
    @Volatile var credentials = StoredCredentials()
    var failWrites = false
    override suspend fun readCredentials() = credentials
    override suspend fun writeCredentials(credentials: StoredCredentials) {
        if (failWrites) throw java.io.IOException("fixture disk failure")
        this.credentials = credentials
    }
}
