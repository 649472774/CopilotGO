package com.tongxie.copilotgo.data.proxy

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tongxie.copilotgo.data.auth.MemoryVault
import com.tongxie.copilotgo.data.auth.withResponse
import com.tongxie.copilotgo.data.net.ProxyAwareHttpClientProvider
import com.tongxie.copilotgo.data.storage.preferenceDataStoreFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress

class ProxyReadinessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun savedProxyIsAppliedBeforeAwaitReadyReturnsAndOldDispatchersRemainUsable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val config = MutableStateFlow(ProxyConfig())
            val ready = MutableStateFlow(false)
            val error = MutableStateFlow<String?>(null)
            val provider = ProxyAwareHttpClientProvider({ OkHttpClient.Builder() }, config, scope, ready, error)
            val old = provider.client
            val waiter = async { provider.awaitReady() }
            config.value = ProxyConfig(enabled = true, host = "proxy.example.com", port = 8080)
            ready.value = true
            withTimeout(3000) { waiter.await() }
            val address = provider.client.proxy!!.address() as InetSocketAddress
            assertEquals("proxy.example.com", address.hostString)
            assertEquals(8080, address.port)
            assertFalse(old.dispatcher.executorService.isShutdown)
            provider.client.dispatcher.executorService.shutdown()
            old.dispatcher.executorService.shutdown()
        } finally { scope.cancel() }
    }

    @Test
    fun uninitializedProviderCannotLeakALegacyCallOntoDirectNetwork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            MockWebServer().use { server ->
                val provider = ProxyAwareHttpClientProvider(
                    { OkHttpClient.Builder() }, MutableStateFlow(ProxyConfig()), scope,
                    MutableStateFlow(false), MutableStateFlow(null)
                )
                try {
                    provider.client.newCall(Request.Builder().url(server.url("/")).build()).withResponse { }
                    fail("Uninitialized configuration sent a request")
                } catch (_: IOException) {
                    assertEquals(0, server.requestCount)
                }
                provider.client.dispatcher.executorService.shutdown()
            }
        } finally { scope.cancel() }
    }

    @Test
    fun encryptedMigrationCompletesBeforeProxyIsReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val legacy = preferenceDataStoreFixture(File(temporary.root, "proxy.preferences_pb"), scope)
            legacy.edit {
                it[booleanPreferencesKey("enabled")] = true
                it[stringPreferencesKey("host")] = "127.0.0.1"
                it[intPreferencesKey("port")] = 8080
                it[stringPreferencesKey("username")] = "fixture-user"
                it[stringPreferencesKey("password")] = "fixture-value"
            }
            val vault = MemoryVault()
            val store = ProxySettingsStore(legacy, vault, scope)
            withTimeout(3000) { store.initialized.first { it } }
            assertNull(store.loadError.value)
            assertTrue(store.config.value.enabled)
            assertEquals(8080, store.config.value.port)
            assertTrue(legacy.data.first().asMap().isEmpty())
            assertTrue(vault.values["proxy"]!!.contains("fixture-value"))
        } finally { scope.cancel() }
    }

    @Test
    fun failedMigrationBlocksNetworkRatherThanPretendingDirectDefaultsWereLoaded() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val legacy = preferenceDataStoreFixture(File(temporary.root, "failed-proxy.preferences_pb"), scope)
            legacy.edit { it[booleanPreferencesKey("enabled")] = true }
            val store = ProxySettingsStore(legacy, MemoryVault().apply { fail = true }, scope)
            withTimeout(3000) { store.initialized.first { it } }
            assertNotNull(store.loadError.value)
            assertEquals(true, legacy.data.first()[booleanPreferencesKey("enabled")])
            val provider = ProxyAwareHttpClientProvider(
                { OkHttpClient.Builder() }, store.config, scope, store.initialized, store.loadError
            )
            try {
                provider.awaitReady()
                fail("Failed proxy migration allowed networking")
            } catch (_: IOException) {
                assertFalse(provider.client.dispatcher.executorService.isShutdown)
            }
            provider.client.dispatcher.executorService.shutdown()
        } finally { scope.cancel() }
    }
}
