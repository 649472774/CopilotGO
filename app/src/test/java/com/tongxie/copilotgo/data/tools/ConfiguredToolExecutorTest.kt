package com.tongxie.copilotgo.data.tools

import com.tongxie.copilotgo.data.agent.AgentToolConfigurationChangedException
import com.tongxie.copilotgo.data.agent.AgentToolException
import com.tongxie.copilotgo.data.agent.AgentToolInvocation
import com.tongxie.copilotgo.data.agent.AgentToolKind
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.tools.mcp.RemoteMcpService
import com.tongxie.copilotgo.data.tools.mcp.string
import com.tongxie.copilotgo.data.tools.net.ToolHttpClient
import com.tongxie.copilotgo.data.tools.web.WebToolService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.net.Proxy
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

class ConfiguredToolExecutorTest {
    @Test
    fun builtinCatalogueRequiresConsentAndValidationHasNoNetworkSideEffects() = runBlocking {
        ExecutorFixture().use { fixture ->
            val original = fixture.settings.awaitReady().web
            val unavailable = fixture.executor.snapshot()
            assertTrue(unavailable.tools.isEmpty())
            assertTrue(unavailable.issues.any { it.message.contains("对外发送") })
            fixture.settings.updateWeb(
                WebToolSettingsDraft(true, true, SearchProvider.EXA_KEYLESS, true), original.revision
            )
            val catalogue = fixture.executor.snapshot()
            assertEquals(setOf(AgentToolKind.PUBLIC_WEB_READ, AgentToolKind.PUBLIC_WEB_SEARCH), catalogue.tools.map { it.kind }.toSet())
            val search = catalogue.tools.single { it.kind == AgentToolKind.PUBLIC_WEB_SEARCH }
            val invocation = AgentToolInvocation(
                "fixture-run", "fixture-call", search, BoundedToolJson.objectValue("""{"query":"Compose documentation"}""")
            )
            assertEquals(invocation.arguments, fixture.executor.validate(invocation).displayArguments)
            expectAgentError {
                fixture.executor.validate(invocation.copy(arguments = BoundedToolJson.objectValue("""{"query":"x","conversation":"not allowed"}""")))
            }
            expectAgentError {
                fixture.executor.validate(invocation.copy(tool = search.copy(kind = AgentToolKind.MCP)))
            }
            val page = catalogue.tools.single { it.kind == AgentToolKind.PUBLIC_WEB_READ }
            expectAgentError {
                fixture.executor.validate(invocation.copy(
                    tool = page, arguments = BoundedToolJson.objectValue("""{"url":"https://127.0.0.1/private"}""")
                ))
            }
            assertEquals(0, fixture.requests.size)
        }
    }

    @Test
    fun realDiscoveryExposesNamespacedToolsButNeverExecutesThemOrTrustsReadOnlyHint() = runBlocking {
        ExecutorFixture().use { fixture ->
            val first = fixture.addServer("First", "fixture-first-key")
            val second = fixture.addServer("Second", "fixture-second-key")
            fixture.remote.discover(first.id, first.revision)
            fixture.remote.discover(second.id, second.revision)
            val tools = fixture.executor.snapshot().tools
            assertEquals(2, tools.size)
            assertNotEquals(tools[0].name, tools[1].name)
            assertTrue(tools.all { it.name.contains(it.identity.configId) && it.name.length <= 64 })
            assertTrue(tools.all { it.kind == AgentToolKind.MCP })
            assertEquals(0, fixture.methods().count { it == "tools/call" })
            assertTrue(tools.none { it.toString().contains("fixture-first-key") || it.toString().contains("fixture-second-key") })
        }
    }

    @Test
    fun validatesBeforeCallKeepsArgumentsExactAndRedactsCredentialEchoInResult() = runBlocking {
        ExecutorFixture().use { fixture ->
            val server = fixture.addServer("Tools", "fixture-server-key")
            fixture.remote.discover(server.id, server.revision)
            val descriptor = fixture.executor.snapshot().tools.single()
            val invocation = AgentToolInvocation(
                "run", "call", descriptor,
                BoundedToolJson.objectValue("""{"query":"fixture query","privateInput":"fixture argument"}""")
            )
            val display = fixture.executor.validate(invocation).displayArguments
            assertEquals("[redacted]", (display["privateInput"] as JsonPrimitive).content)
            assertEquals(0, fixture.methods().count { it == "tools/call" })
            val result = fixture.executor.execute(invocation)
            assertFalse(result.isError)
            assertFalse(result.content.contains("fixture-server-key"))
            assertTrue(result.content.contains("[redacted]"))
            assertEquals(1, fixture.methods().count { it == "tools/call" })
            val call = fixture.requests.single { it.message.string("method") == "tools/call" }
            val arguments = (call.message["params"] as JsonObject)["arguments"]
            assertEquals(invocation.arguments, arguments)
            assertEquals("Bearer fixture-server-key", call.authorization)
        }
    }

    @Test
    fun configurationChangeInvalidatesOldProposalWithoutExecutingReplacement() = runBlocking {
        ExecutorFixture().use { fixture ->
            val server = fixture.addServer("Tools", "fixture-old-key")
            fixture.remote.discover(server.id, server.revision)
            val before = fixture.executor.snapshot()
            val descriptor = before.tools.single()
            fixture.settings.saveServer(
                server.id, server.revision, McpServerDraft(server), CredentialUpdate.Replace("fixture-new-key")
            )
            assertFalse(fixture.executor.isCurrent(descriptor.identity))
            val after = fixture.executor.snapshot()
            assertTrue(after.revision > before.revision)
            assertTrue(after.tools.isEmpty())
            try {
                fixture.executor.execute(AgentToolInvocation(
                    "old-run", "old-call", descriptor, BoundedToolJson.objectValue("""{"query":"old"}""")
                ))
                fail("Stale proposal must fail")
            } catch (_: AgentToolConfigurationChangedException) {
                assertEquals(0, fixture.methods().count { it == "tools/call" })
            }
        }
    }

    @Test
    fun explicitlyTrustedLiteralLoopbackUsesNormalTlsAndActualToolExecution() = runBlocking {
        ExecutorFixture().use { fixture ->
            val server = fixture.addServer("Literal LAN fixture", "fixture-literal-key", literal = true)
            val report = fixture.remote.discover(server.id, server.revision)
            assertTrue(report.tools.single().supported)
            val descriptor = fixture.executor.snapshot().tools.single()
            val result = fixture.executor.execute(AgentToolInvocation(
                "literal-run", "literal-call", descriptor,
                BoundedToolJson.objectValue("""{"query":"fixture"}""")
            ))
            assertFalse(result.isError)
            assertEquals(1, fixture.methods().count { it == "tools/call" })
            assertFalse(result.content.contains("fixture-literal-key"))
        }
    }

    @Test
    fun redirectedMcpCallReportsUncertaintyWithoutReplayingAnAlreadyReceivedRequest() = runBlocking {
        ExecutorFixture().use { fixture ->
            val server = fixture.addServer("Redirect fixture", "fixture-redirect-key")
            fixture.remote.discover(server.id, server.revision)
            val descriptor = fixture.executor.snapshot().tools.single()
            fixture.redirectCalls = true
            val result = fixture.executor.execute(AgentToolInvocation(
                "redirect-run", "redirect-call", descriptor,
                BoundedToolJson.objectValue("""{"query":"fixture"}""")
            ))
            assertTrue(result.isError)
            assertTrue(result.outcomeUnknown)
            assertEquals(1, fixture.methods().count { it == "tools/call" })
            assertEquals(3, fixture.requests.size)
        }
    }

    @Test
    fun schemaContainingOriginCredentialIsRejectedWithoutModelExposure() = runBlocking {
        ExecutorFixture().use { fixture ->
            fixture.echoDescription = true
            val server = fixture.addServer("Tools", "fixture-private-key")
            val report = fixture.remote.discover(server.id, server.revision)
            assertTrue(report.tools.single().supported.not())
            assertFalse(report.toString().contains("fixture-private-key"))
            val snapshot = fixture.executor.snapshot()
            assertTrue(snapshot.tools.isEmpty())
            assertFalse(snapshot.toString().contains("fixture-private-key"))
        }
    }

    private suspend fun expectAgentError(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected rejected proposal")
        } catch (_: AgentToolException) {
            Unit
        }
    }
}

private class ExecutorFixture : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    val requests = CopyOnWriteArrayList<Exchange>()
    @Volatile var echoDescription = false
    @Volatile var redirectCalls = false
    val settings = ToolSettingsStore(ToolMemoryVault(), scope)
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    val remote: RemoteMcpService
    val executor: ConfiguredToolExecutor

    init {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val message = BoundedToolJson.objectValue(request.body.readUtf8())
                requests += Exchange(message, request.getHeader("Authorization"))
                if (message.string("method") == "tools/call" && redirectCalls) {
                    return MockResponse().setResponseCode(307).setHeader("Location", "/other")
                }
                val result = when (message.string("method")) {
                    "server/discover" -> buildJsonObject {
                        put("resultType", "complete")
                        put("supportedVersions", JsonArray(listOf(JsonPrimitive("2026-07-28"))))
                        putJsonObject("capabilities") { put("tools", JsonObject(emptyMap())) }
                        putJsonObject("_meta") {
                            putJsonObject("io.modelcontextprotocol/serverInfo") { put("name", "Same Untrusted Server") }
                        }
                    }
                    "tools/list" -> buildJsonObject {
                        put("resultType", "complete")
                        put("tools", JsonArray(listOf(buildJsonObject {
                            put("name", "lookup")
                            put("description", if (echoDescription) request.getHeader("Authorization").orEmpty() else "Fixture lookup")
                            put("inputSchema", BoundedToolJson.objectValue(
                                """{"type":"object","properties":{"query":{"type":"string","minLength":1},"privateInput":{"type":"string","writeOnly":true}},"required":["query"],"additionalProperties":false}"""
                            ))
                            putJsonObject("annotations") { put("readOnlyHint", true) }
                        })))
                    }
                    "tools/call" -> buildJsonObject {
                        put("resultType", "complete")
                        put("content", JsonArray(listOf(buildJsonObject {
                            put("type", "text")
                            put("text", "Actual fixture result ${request.getHeader("Authorization")}")
                        })))
                    }
                    else -> return MockResponse().setResponseCode(400)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", checkNotNull(message["id"]))
                        put("result", result)
                    }.toString()
                )
            }
        }
        server.start(loopback, 0)
        val provider = object : HttpClientProvider {
            override val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (hostname !in setOf("localhost", "127.0.0.1")) throw UnknownHostException("Not a fixture hostname")
                        return listOf(loopback)
                    }
                })
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        }
        val http = ToolHttpClient(provider)
        remote = RemoteMcpService(settings, http, scope)
        executor = ConfiguredToolExecutor(settings, remote, WebToolService(settings, http, remote), scope)
    }

    suspend fun addServer(label: String, key: String, literal: Boolean = false): McpServerSettings {
        settings.awaitReady()
        return settings.saveServer(
            null, null,
            McpServerDraft(
                label, server.url("/mcp").newBuilder()
                    .host(if (literal) "127.0.0.1" else "localhost").build().toString(), enabled = true,
                networkTrust = McpNetworkTrust.LOCAL_NETWORK,
                authMode = McpAuthMode.BEARER, enabledTools = setOf("lookup")
            ),
            CredentialUpdate.Replace(key)
        )
    }

    fun methods(): List<String?> = requests.map { it.message.string("method") }

    override fun close() {
        scope.cancel()
        server.close()
    }

    data class Exchange(val message: JsonObject, val authorization: String?)
}
