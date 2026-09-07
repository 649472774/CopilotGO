package com.tongxie.copilotgo.data.tools

import com.tongxie.copilotgo.data.storage.SecretVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ToolSettingsStoreTest {
    @Test
    fun defaultsAreKeylessAndRequireExternalSharingConsent() = withStore { store, vault ->
        val web = store.awaitReady().web
        assertEquals(SearchProvider.EXA_KEYLESS, web.provider)
        assertFalse(web.externalSharingConsent)
        assertEquals(ToolCredentialState.MISSING, web.credentialState)
        assertEquals(setOf(ToolSettingsStore.NAMESPACE), vault.values.keys)
        expectProblem(ToolProblemCode.CONSENT_REQUIRED) {
            store.withWeb(web.revision) { _, _ -> fail("No consent") }
        }
        expectProblem(ToolProblemCode.INVALID_CONFIGURATION) {
            store.updateWeb(WebToolSettingsDraft(true, true, SearchProvider.EXA_API_KEY, true), web.revision)
        }
    }

    @Test
    fun publicSettingsNeverExposeKeysAndAuthorizationIsEndpointBound() = withStore { store, vault ->
        store.awaitReady()
        val server = store.saveServer(
            null, null, authenticatedDraft(),
            CredentialUpdate.Replace("fixture-server-secret")
        )
        assertEquals(ToolCredentialState.CONFIGURED, server.credentialState)
        assertFalse(server.toString().contains("fixture-server-secret"))
        assertFalse(store.state.value.toString().contains("fixture-server-secret"))
        assertTrue(vault.values.values.single().contains("fixture-server-secret"))
        assertFalse(CredentialUpdate.Replace("fixture-server-secret").toString().contains("fixture-server-secret"))
        store.withServer(server.id, server.revision) { lease ->
            val request = Request.Builder().url(server.endpoint)
            lease.authorize(request)
            assertEquals("Bearer fixture-server-secret", request.build().header("Authorization"))
            assertFalse(lease.toString().contains("fixture-server-secret"))
            expectProblem(ToolProblemCode.UNSAFE_DESTINATION) {
                lease.authorize(Request.Builder().url("https://other.example/mcp"))
            }
        }
    }

    @Test
    fun changedEndpointRequiresExplicitCredentialRemovalAndNewToolSelection() = withStore { store, _ ->
        store.awaitReady()
        val first = store.saveServer(
            null, null, authenticatedDraft().copy(enabledTools = setOf("search")),
            CredentialUpdate.Replace("fixture-server-secret")
        )
        val changed = McpServerDraft(first).copy(endpoint = "https://next.example/mcp")
        expectProblem(ToolProblemCode.INVALID_CONFIGURATION) {
            store.saveServer(first.id, first.revision, changed)
        }
        assertTrue(store.isCurrent(first.id, first.revision))
        val next = store.saveServer(
            first.id, first.revision, changed.copy(authMode = McpAuthMode.NONE),
            CredentialUpdate.Remove
        )
        assertNotEquals(first.revision, next.revision)
        assertTrue(next.enabledTools.isEmpty())
        assertEquals(ToolCredentialState.MISSING, next.credentialState)
        assertFalse(store.isCurrent(first.id, first.revision))
    }

    @Test
    fun staleEditCannotUndoToggleOrCredentialRotation() = withStore { store, _ ->
        store.awaitReady()
        val first = store.saveServer(null, null, authenticatedDraft(), CredentialUpdate.Replace("fixture-one"))
        val next = store.saveServer(
            first.id, first.revision, McpServerDraft(first).copy(enabled = false),
            CredentialUpdate.Replace("fixture-two")
        )
        expectProblem(ToolProblemCode.CONFIGURATION_CHANGED) {
            store.saveServer(first.id, first.revision, McpServerDraft(first))
        }
        assertEquals(next, store.state.value.snapshot!!.servers.single())
    }

    @Test
    fun deletionRevokesCapturedCredentialBeforeDiskWriteCompletes() = withStore { store, vault ->
        store.awaitReady()
        val server = store.saveServer(null, null, authenticatedDraft(), CredentialUpdate.Replace("fixture-delete"))
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val call = async {
            expectProblem(ToolProblemCode.CONFIGURATION_CHANGED) {
                store.withServer(server.id, server.revision) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
        }
        started.await()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        vault.beforeWrite = {
            entered.complete(Unit)
            gate.await()
        }
        val deletion = async { store.deleteServer(server.id, server.revision) }
        entered.await()
        assertFalse(store.isCurrent(server.id, server.revision))
        withTimeout(2_000) { stopped.await() }
        call.await()
        gate.complete(Unit)
        deletion.await()
        assertTrue(store.state.value.snapshot!!.servers.isEmpty())
        expectProblem(ToolProblemCode.CONFIGURATION_CHANGED) {
            store.withServer(server.id, server.revision) { fail("Deleted server cannot execute") }
        }
    }

    @Test
    fun failedPersistenceBlocksCallsAndPreservesOriginalEncryptedRecord() = withStore { store, vault ->
        store.awaitReady()
        val server = store.saveServer(null, null, authenticatedDraft(), CredentialUpdate.Replace("fixture-preserved"))
        val original = vault.values.toMap()
        vault.beforeWrite = { throw IOException("fixture-storage-secret-must-not-leak") }
        expectProblem(ToolProblemCode.STORAGE) { store.deleteServer(server.id, server.revision) }
        assertEquals(original, vault.values)
        assertFalse(store.isCurrent(server.id, server.revision))
        assertFalse(store.state.value.problem!!.message.contains("fixture-storage-secret"))
        expectProblem(ToolProblemCode.STORAGE) {
            store.withServer(server.id, server.revision) { fail("Failed storage must block calls") }
        }
        vault.beforeWrite = null
        store.reload()
        val restored = store.awaitReady().servers.single()
        assertEquals(server.endpoint, restored.endpoint)
        assertEquals(server.credentialState, restored.credentialState)
        assertTrue(restored.revision > server.revision)
        assertFalse(store.isCurrent(server.id, server.revision))
    }

    @Test
    fun corruptRecordNeverResetsKeysOrReportsLoadedSuccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val vault = ToolMemoryVault().apply { values[ToolSettingsStore.NAMESPACE] = "broken fixture-secret" }
        try {
            val store = ToolSettingsStore(vault, scope)
            expectProblem(ToolProblemCode.STORAGE) { store.awaitReady() }
            assertEquals("broken fixture-secret", vault.values[ToolSettingsStore.NAMESPACE])
            assertNull(store.state.value.snapshot)
            assertTrue(store.revisions.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun keysAreNotSentByKeylessProviderAndRemainOnlyInToolNamespace() = withStore { store, _ ->
        val original = store.awaitReady().web
        val keyed = store.updateWeb(
            WebToolSettingsDraft(true, true, SearchProvider.EXA_API_KEY, true),
            original.revision, CredentialUpdate.Replace("fixture-exa-key")
        )
        store.withWeb(keyed.revision) { _, lease ->
            val request = Request.Builder().url(WebProviderDisclosure.EXA_ENDPOINT)
            lease.authorize(request)
            assertEquals("fixture-exa-key", request.build().header("x-api-key"))
            assertNull(request.build().header("Authorization"))
        }
        val keyless = store.updateWeb(
            WebToolSettingsDraft(keyed).copy(provider = SearchProvider.EXA_KEYLESS),
            keyed.revision
        )
        store.withWeb(keyless.revision) { _, lease ->
            val request = Request.Builder().url(WebProviderDisclosure.EXA_ENDPOINT)
            lease.authorize(request)
            assertTrue(request.build().headers.names().isEmpty())
            assertFalse(lease.hasCredential)
        }
    }

    @Test
    fun encodedEndpointLimitsAreCheckedBeforePersistingARecordThatCannotReload() = withStore { store, vault ->
        store.awaitReady()
        val previous = vault.values.toMap()
        expectProblem(ToolProblemCode.INVALID_CONFIGURATION) {
            store.saveServer(null, null, McpServerDraft("Encoded", "https://example.com/${"界".repeat(240)}"))
        }
        expectProblem(ToolProblemCode.UNSAFE_DESTINATION) {
            store.saveServer(null, null, McpServerDraft("Encoded", "https://example.com/${"界".repeat(1000)}"))
        }
        assertEquals(previous, vault.values)
        assertTrue(store.state.value.snapshot!!.servers.isEmpty())
        assertNull(store.state.value.problem)
    }

    @Test
    fun sensitiveQueryAndReservedHeaderAndDesktopTransportAreRejected() = withStore { store, _ ->
        store.awaitReady()
        expectProblem(ToolProblemCode.INVALID_CONFIGURATION) {
            store.saveServer(null, null, McpServerDraft("bad", "https://example.com/mcp?api_key=fixture-secret"))
        }
        listOf("Host", "Cookie", "Mcp-Name", "Proxy-Authorization", "Authorization", "bad\r\nHeader").forEach { name ->
            expectProblem(ToolProblemCode.INVALID_CONFIGURATION) {
                store.saveServer(
                    null, null,
                    authenticatedDraft().copy(authMode = McpAuthMode.CUSTOM_HEADER, authHeaderName = name),
                    CredentialUpdate.Replace("fixture")
                )
            }
        }
        expectProblem(ToolProblemCode.UNSUPPORTED_TRANSPORT) {
            store.saveServer(null, null, McpServerDraft("desktop", "https://example.com", transport = McpTransport.STDIO))
        }
    }

    private fun authenticatedDraft() = McpServerDraft(
        label = "Fixture MCP", endpoint = "https://mcp.example/mcp",
        enabled = true, authMode = McpAuthMode.BEARER
    )

    private fun withStore(block: suspend CoroutineScope.(ToolSettingsStore, ToolMemoryVault) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val vault = ToolMemoryVault()
            block(ToolSettingsStore(vault, scope), vault)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun expectProblem(code: ToolProblemCode, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected $code")
        } catch (e: ToolException) {
            assertEquals(code, e.problem.code)
        }
    }
}

internal class ToolMemoryVault : SecretVault {
    val values = mutableMapOf<String, String>()
    var beforeWrite: (suspend () -> Unit)? = null
    override suspend fun read(name: String): String? = values[name]
    override suspend fun write(name: String, value: String) {
        beforeWrite?.invoke()
        values[name] = value
    }
}
