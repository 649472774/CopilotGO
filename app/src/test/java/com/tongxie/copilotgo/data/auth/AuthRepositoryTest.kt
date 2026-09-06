package com.tongxie.copilotgo.data.auth

import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.ui.viewmodel.AuthViewModel
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun cancelledLoginCannotCompleteAConcurrentCredentialWrite() = runBlocking {
        Fixture().use { fixture ->
            fixture.server.enqueue(deviceCode())
            val code = fixture.auth.beginDeviceLogin()
            fixture.server.enqueue(MockResponse().setBody("""{"access_token":"fixture-new-github"}"""))
            fixture.server.enqueue(MockResponse().setBody(
                """{"token":"fixture-new-copilot","expires_at":${System.currentTimeMillis() / 1000 + 3600}}"""
            ))
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            fixture.store.afterWrite = { credentials ->
                if (credentials.githubToken == "fixture-new-github") {
                    writeStarted.complete(Unit)
                    releaseWrite.await()
                }
            }
            val login = async(Dispatchers.Default) { fixture.auth.pollUntilDone(code) }
            withTimeout(3000) { writeStarted.await() }
            fixture.auth.cancelLogin()
            releaseWrite.complete(Unit)
            try {
                login.await()
                fail("Cancelled login completed")
            } catch (_: CancellationException) {
                assertEquals(StoredCredentials(), fixture.store.credentials)
                assertEquals(AuthState.NotLoggedIn, fixture.auth.state.value)
                assertFalse(fixture.auth.busy.value)
            }
        }
    }

    @Test
    fun cancelWhileRequestingCodeClosesNetworkAndCannotRestoreWaitingState() = runBlocking {
        Fixture().use { fixture ->
            fixture.server.enqueue(deviceCode().setBodyDelay(1, TimeUnit.SECONDS))
            val login = async(Dispatchers.Default) { fixture.auth.beginDeviceLogin() }
            assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            fixture.auth.cancelLogin()
            try {
                withTimeout(750) { login.await() }
                fail("Cancelled code request completed")
            } catch (_: CancellationException) {
                assertEquals(AuthState.NotLoggedIn, fixture.auth.state.value)
                assertFalse(fixture.auth.busy.value)
            }
        }
    }

    @Test
    fun concurrentLogoutActionsJoinTheSameDurableClear() = runBlocking {
        Fixture().use { fixture ->
            fixture.store.credentials = StoredCredentials("fixture-github")
            fixture.auth.bootstrap()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = AtomicInteger()
            fixture.store.afterWrite = {
                writes.incrementAndGet()
                entered.complete(Unit)
                release.await()
            }
            val first = async(Dispatchers.Default) { fixture.auth.logout() }
            withTimeout(3000) { entered.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.auth.logout() }
            assertFalse(second.isCompleted)
            assertTrue(fixture.auth.loggingOut.value)
            release.complete(Unit)
            first.await()
            second.await()
            assertEquals(1, writes.get())
            assertFalse(fixture.auth.loggingOut.value)
            assertEquals(AuthState.NotLoggedIn, fixture.auth.state.value)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun viewModelDoesNotPublishLoggedOutBeforePendingCredentialClearCompletes() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            Fixture().use { fixture ->
                fixture.store.credentials = StoredCredentials("fixture-existing")
                val viewModel = AuthViewModel(fixture.auth)
                val owners = ViewModelStore().apply { put("fixture", viewModel) }
                try {
                    fixture.server.enqueue(deviceCode())
                    fixture.server.enqueue(MockResponse().setBody("""{"error":"authorization_pending"}""")
                        .setBodyDelay(1, TimeUnit.SECONDS))
                    viewModel.startLogin()
                    withTimeout(3000) { viewModel.state.first { it is AuthState.AwaitingUserAuthorization } }
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    fixture.store.afterWrite = { entered.complete(Unit); release.await() }
                    val logout = async(start = CoroutineStart.UNDISPATCHED) { viewModel.logoutAndAwait() }
                    withTimeout(3000) { entered.await() }
                    assertNotEquals(AuthState.NotLoggedIn, viewModel.state.value)
                    assertTrue(viewModel.loggingOut.value)
                    release.complete(Unit)
                    logout.await()
                    assertEquals(AuthState.NotLoggedIn, viewModel.state.value)
                    assertNull(viewModel.deviceCode.value)
                } finally { owners.clear() }
            }
        } finally { Dispatchers.resetMain() }
    }

    private fun deviceCode() = MockResponse().setBody(
        """{"device_code":"fixture","user_code":"CODE","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""
    )

    private inner class Fixture : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val provider = object : HttpClientProvider { override val client = OkHttpClient() }
        val store = MemoryCredentialStore()
        val auth = AuthRepository(store,
            DeviceFlowClient(provider, json, clientId = "fixture-client",
                deviceCodeUrl = server.url("/code").toString(),
                accessTokenUrl = server.url("/access").toString(), pollDelay = {}),
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
    var afterWrite: suspend (StoredCredentials) -> Unit = {}
    override suspend fun readCredentials() = credentials
    override suspend fun writeCredentials(credentials: StoredCredentials) {
        if (failWrites) throw java.io.IOException("fixture disk failure")
        this.credentials = credentials
        afterWrite(credentials)
    }
}
