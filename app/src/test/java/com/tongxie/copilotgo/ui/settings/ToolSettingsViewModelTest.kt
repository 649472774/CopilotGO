package com.tongxie.copilotgo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.storage.SecretVault
import com.tongxie.copilotgo.data.tools.CredentialUpdate
import com.tongxie.copilotgo.data.tools.McpNetworkTrust
import com.tongxie.copilotgo.data.tools.McpServerDraft
import com.tongxie.copilotgo.data.tools.SearchProvider
import com.tongxie.copilotgo.data.tools.ToolCredentialState
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.ToolSettingsStore
import com.tongxie.copilotgo.data.tools.mcp.RemoteMcpService
import com.tongxie.copilotgo.data.tools.net.ToolHttpClient
import com.tongxie.copilotgo.ui.viewmodel.ToolSettingsPending
import com.tongxie.copilotgo.ui.viewmodel.ToolSettingsViewModel
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolSettingsViewModelTest {
    private val fixtures = mutableListOf<Fixture>()

    @Before fun prepareMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun closeFixtures() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test fun configurationFlowsAreCanonicalAndOnlyCurrentSupportedDiscoveryCanSelectTools() = runBlocking {
        val fixture = fixture()
        val saved = fixture.store.saveServer(null, null, fixture.draft())
        val vm = fixture.vm
        assertSame(fixture.store.state, vm.settings)
        assertSame(fixture.service.discovery, vm.discovery)
        vm.openServer(saved.id)
        vm.discover()
        fixture.awaitDiscovery()
        assertNull(fixture.discoveryDiagnostic(), vm.state.value.problem)
        val report = fixture.service.discovery.value[saved.id]!!.report!!
        assertEquals(saved.revision, report.configRevision)
        assertEquals(setOf("lookup", "second"), report.tools.filter { it.canBeSelected() }.map { it.name }.toSet())
        assertEquals(1, report.tools.count { !it.canBeSelected() })
        vm.setToolSelected("unsupported", true)
        assertEquals(ToolProblemCode.SCHEMA, vm.state.value.problem?.code)
        assertTrue(vm.state.value.server!!.draft.enabledTools.isEmpty())
        vm.setToolSelected("lookup", true)
        vm.setToolSelected("second", true)
        assertEquals(setOf("lookup", "second"), vm.state.value.server!!.draft.enabledTools)
        vm.saveServer()
        fixture.awaitSaved()
        val current = fixture.store.state.value.snapshot!!.servers.single()
        assertEquals(setOf("lookup", "second"), current.enabledTools)
        assertTrue(current.revision > saved.revision)
        assertNull(currentToolDiscovery(current, fixture.service.discovery.value[current.id]))
        vm.setToolSelected("unseen", true)
        assertEquals(ToolProblemCode.CONFIGURATION_CHANGED, vm.state.value.problem?.code)
        assertEquals(setOf("lookup", "second"), vm.state.value.server!!.draft.enabledTools)
        assertEquals(listOf("server/discover", "tools/list"), fixture.methods.toList())
        assertFalse(fixture.methods.contains("tools/call"))
    }

    @Test fun staleEditsDeleteAndEnableRequestsNeverOverwriteConcurrentConfiguration() = runBlocking {
        val fixture = fixture()
        val saved = fixture.store.saveServer(null, null, fixture.draft())
        val vm = fixture.vm
        vm.openServer(saved.id)
        val raw = "${fixture.endpoint}?controlled=draft"
        vm.editServer { it.copy(endpoint = raw) }
        val concurrent = fixture.store.saveServer(saved.id, saved.revision, McpServerDraft(saved).copy(label = "外部修改"))
        vm.saveServer()
        assertEquals(ToolProblemCode.CONFIGURATION_CHANGED, vm.state.value.problem?.code)
        assertEquals(raw, vm.state.value.server?.draft?.endpoint)
        assertEquals(saved.revision, vm.state.value.server?.expectedRevision)
        vm.deleteServer()
        assertEquals(ToolProblemCode.CONFIGURATION_CHANGED, vm.state.value.problem?.code)
        vm.setServerEnabled(saved.id, saved.revision, true)
        assertEquals(ToolProblemCode.CONFIGURATION_CHANGED, vm.state.value.problem?.code)
        assertEquals(concurrent, fixture.store.state.value.snapshot!!.servers.single())
        assertTrue(fixture.methods.isEmpty())
    }

    @Test fun transientCredentialsNeverBecomeFormStateAndOpeningEditorDoesNotReadKeys() = runBlocking {
        val fixture = fixture()
        val reads = fixture.vault.reads.get()
        val vm = fixture.vm
        vm.openSearch()
        assertEquals(reads, fixture.vault.reads.get())
        vm.editSearch { it.copy(provider = SearchProvider.EXA_API_KEY) }
        vm.setSearchCredentialAction(ToolCredentialAction.REPLACE)
        vm.saveSearch(CredentialUpdate.Replace("synthetic-vm-only-key"))
        fixture.awaitSaved()
        assertEquals(ToolCredentialState.CONFIGURED, fixture.store.state.value.snapshot?.web?.credentialState)
        assertEquals(ToolCredentialAction.KEEP, vm.state.value.search?.credentialAction)
        assertFalse(vm.state.value.toString().contains("synthetic-vm-only-key"))
        val readsAfterSave = fixture.vault.reads.get()
        vm.openSearch()
        vm.setSearchCredentialAction(ToolCredentialAction.REPLACE)
        assertEquals(readsAfterSave, fixture.vault.reads.get())
        assertFalse(vm.state.value.javaClass.declaredFields.any {
            CredentialUpdate::class.java.isAssignableFrom(it.type)
        })
        assertTrue(fixture.methods.isEmpty())
    }

    @Test fun duplicatePendingWritesAndEditsAreRejectedWithoutReplacingTheSubmittedDraft() = runBlocking {
        val fixture = fixture()
        val vm = fixture.vm
        vm.openServer(null)
        vm.editServer { fixture.draft().copy(label = "第一次提交") }
        val gate = CompletableDeferred<Unit>()
        fixture.vault.gate.set(gate)
        vm.saveServer()
        withTimeout(10_000) { fixture.vault.writeStarted.await() }
        assertEquals(ToolSettingsPending.SAVE_SERVER, vm.state.value.pending)
        vm.saveServer()
        vm.editServer { it.copy(label = "重复点击不应改写") }
        assertEquals("第一次提交", vm.state.value.server?.draft?.label)
        assertEquals(ToolProblemCode.INVALID_CONFIGURATION, vm.state.value.problem?.code)
        gate.complete(Unit)
        fixture.awaitSaved()
        assertEquals(1, fixture.store.state.value.snapshot!!.servers.size)
        assertEquals("第一次提交", fixture.store.state.value.snapshot!!.servers.single().label)
        assertEquals(1, fixture.vault.gatedWrites.get())
        val generatedId = vm.state.value.server?.serverId
        vm.openServer(null)
        assertEquals("Rotation/recomposition of an add route must retain the saved ID", generatedId, vm.state.value.server?.serverId)
    }

    @Test fun networkQuotaAndUnsupportedProtocolRemainTypedFailures() = runBlocking {
        val fixture = fixture()
        val saved = fixture.store.saveServer(null, null, fixture.draft())
        fixture.vm.openServer(saved.id)
        fixture.httpStatus.set(429)
        fixture.vm.discover()
        fixture.awaitDiscovery()
        assertEquals(fixture.discoveryDiagnostic(), ToolProblemCode.RATE_LIMITED, fixture.vm.state.value.problem?.code)
        assertEquals(429, fixture.vm.state.value.problem?.httpStatus)
        assertNull(fixture.service.discovery.value[saved.id]?.report)
        fixture.httpStatus.set(200)
        fixture.protocol.set("2099-01-01")
        fixture.vm.discover()
        fixture.awaitDiscovery()
        assertEquals(ToolProblemCode.PROTOCOL, fixture.vm.state.value.problem?.code)
        assertTrue(fixture.vm.state.value.problem?.message?.contains("版本") == true)
        assertNull(fixture.service.discovery.value[saved.id]?.report)
        assertEquals(listOf("server/discover", "server/discover"), fixture.methods.toList())
        assertFalse(fixture.methods.contains("tools/list"))
        assertFalse(fixture.methods.contains("tools/call"))
    }

    @Test fun cancellationAndRevisionChangesCannotPublishLateDiscoveryAsSuccessful() = runBlocking {
        val fixture = fixture()
        val saved = fixture.store.saveServer(null, null, fixture.draft())
        fixture.vm.openServer(saved.id)
        val gate = CompletableDeferred<Unit>()
        fixture.readinessGate.set(gate)
        fixture.vm.discover()
        withTimeout(10_000) { fixture.readinessStarted.await() }
        fixture.vm.cancelDiscovery()
        withTimeout(10_000) {
            fixture.service.discovery.first { it[saved.id]?.loading != true }
        }
        assertFalse(fixture.vm.state.value.checking)
        assertTrue(fixture.vm.state.value.discoveryCancelled)
        assertNull(fixture.vm.state.value.problem)
        fixture.vm.discover()
        withTimeout(10_000) { fixture.service.discovery.first { it[saved.id]?.loading == true } }
        fixture.store.saveServer(saved.id, saved.revision, McpServerDraft(saved).copy(label = "新版配置"))
        withTimeout(10_000) { fixture.vm.state.first { !it.checking } }
        gate.complete(Unit)
        assertNull(fixture.service.discovery.value[saved.id]?.report)
        assertTrue(fixture.methods.isEmpty())
        assertEquals(saved.revision, fixture.vm.state.value.server?.expectedRevision)
    }

    @Test fun aTypedWriteFailurePreservesTheNonsecretDraftUntilAnExplicitReset() = runBlocking {
        val fixture = fixture()
        fixture.vm.openServer(null)
        fixture.vm.editServer { fixture.draft().copy(label = "保留原始草稿") }
        fixture.vault.failWrites.set(true)
        fixture.vm.saveServer()
        withTimeout(10_000) { fixture.vm.state.first { it.pending == null && it.problem != null } }
        assertEquals(ToolProblemCode.STORAGE, fixture.vm.state.value.problem?.code)
        assertEquals("保留原始草稿", fixture.vm.state.value.server?.draft?.label)
        assertTrue(fixture.vm.state.value.server!!.dirty)
        assertEquals(0L, fixture.vm.state.value.saveSequence)
        assertTrue(fixture.methods.isEmpty())
    }

    private suspend fun fixture(): Fixture {
        val fixture = Fixture()
        fixtures += fixture
        fixture.store.awaitReady()
        return fixture
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = ViewModelStore()
        val vault = MemoryVault()
        val methods = CopyOnWriteArrayList<String>()
        val httpStatus = AtomicInteger(200)
        val protocol = AtomicReference("2026-07-28")
        val readinessGate = AtomicReference<CompletableDeferred<Unit>?>(null)
        val readinessStarted = CompletableDeferred<Unit>()
        private val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        private val server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = response(request)
            }
            start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        }
        val endpoint: String = server.url("/mcp").newBuilder().host("127.0.0.1").build().toString()
        private val provider = object : HttpClientProvider {
            override val client = OkHttpClient.Builder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .proxy(Proxy.NO_PROXY)
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (hostname != "localhost" && hostname != "127.0.0.1") {
                            throw UnknownHostException("Fixture forbids external DNS")
                        }
                        return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                    }
                }).build()
            override suspend fun awaitReady() {
                readinessStarted.complete(Unit)
                readinessGate.get()?.await()
            }
        }
        val store = ToolSettingsStore(vault, scope)
        val service = RemoteMcpService(store, ToolHttpClient(provider), scope)
        val vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ToolSettingsViewModel(store, service) as T
        })[ToolSettingsViewModel::class.java]

        fun draft() = McpServerDraft(
            label = "受控服务", endpoint = endpoint, networkTrust = McpNetworkTrust.LOCAL_NETWORK
        )

        suspend fun awaitSaved() {
            withTimeout(10_000) { vm.state.first { it.pending == null && (it.savedNotice || it.problem != null) } }
            assertNull("Save must succeed", vm.state.value.problem)
            assertTrue(vm.state.value.savedNotice)
        }

        suspend fun awaitDiscovery() {
            withTimeout(10_000) { vm.state.first { !it.checking } }
        }

        fun discoveryDiagnostic(): String =
            "Endpoint=$endpoint; trust=${store.state.value.snapshot?.servers?.singleOrNull()?.networkTrust}; " +
                "methods=$methods; problem=${vm.state.value.problem}"

        fun close() {
            owner.clear()
            scope.cancel()
            server.shutdown()
            provider.client.connectionPool.evictAll()
            provider.client.dispatcher.executorService.shutdown()
            assertFalse("Settings must never invoke tools/call", methods.contains("tools/call"))
        }

        private fun response(request: RecordedRequest): MockResponse {
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            val method = body["method"]!!.jsonPrimitive.content
            methods += method
            if (httpStatus.get() != 200) return MockResponse().setResponseCode(httpStatus.get())
            val result: JsonObject = when (method) {
                "server/discover" -> buildJsonObject {
                    put("resultType", "complete")
                    put("supportedVersions", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(protocol.get())) })
                    put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                }
                "tools/list" -> buildJsonObject {
                    put("resultType", "complete")
                    put("tools", buildJsonArray {
                        listOf("lookup", "second", "unsupported").forEach { name ->
                            add(buildJsonObject {
                                put("name", name)
                                put("description", "受控测试工具")
                                put("inputSchema", buildJsonObject {
                                    put("type", "object")
                                    if (name == "unsupported") put("\$ref", "https://example.org/unavailable-schema")
                                })
                                put("annotations", buildJsonObject { put("readOnlyHint", true) })
                            })
                        }
                    })
                }
                else -> return MockResponse().setResponseCode(400)
            }
            return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", body["id"]!!)
                    put("result", result)
                }.toString())
        }
    }

    private class MemoryVault : SecretVault {
        private val values = ConcurrentHashMap<String, String>()
        val reads = AtomicInteger()
        val gate = AtomicReference<CompletableDeferred<Unit>?>(null)
        val writeStarted = CompletableDeferred<Unit>()
        val gatedWrites = AtomicInteger()
        val failWrites = AtomicBoolean()
        override suspend fun read(name: String): String? {
            reads.incrementAndGet()
            return values[name]
        }
        override suspend fun write(name: String, value: String) {
            gate.get()?.let {
                gatedWrites.incrementAndGet()
                writeStarted.complete(Unit)
                it.await()
            }
            if (failWrites.get()) throw IOException("Controlled tool settings write failure")
            values[name] = value
        }
    }
}
