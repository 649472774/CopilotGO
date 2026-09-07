package com.tongxie.copilotgo.integration

import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.agent.AgentApprovalDecision
import com.tongxie.copilotgo.data.agent.AgentApprovalRequest
import com.tongxie.copilotgo.data.agent.AgentApprovalResponse
import com.tongxie.copilotgo.data.agent.AgentChatRequest
import com.tongxie.copilotgo.data.agent.AgentEngine
import com.tongxie.copilotgo.data.agent.AgentModelTransport
import com.tongxie.copilotgo.data.agent.AgentPromptBuilder
import com.tongxie.copilotgo.data.agent.AgentRunStatus
import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import com.tongxie.copilotgo.data.agent.AgentToolCallStatus
import com.tongxie.copilotgo.data.agent.AgentToolResult
import com.tongxie.copilotgo.data.agent.SourceKind
import com.tongxie.copilotgo.data.chat.ChatStreamCenter
import com.tongxie.copilotgo.data.chat.CopilotChatClient
import com.tongxie.copilotgo.data.chat.ModelCatalog
import com.tongxie.copilotgo.data.chat.OperationResult
import com.tongxie.copilotgo.data.chat.SendResult
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.storage.SecretVault
import com.tongxie.copilotgo.data.storage.AppPaths
import com.tongxie.copilotgo.data.storage.SessionStore
import com.tongxie.copilotgo.data.tools.ConfiguredToolExecutor
import com.tongxie.copilotgo.data.tools.CredentialUpdate
import com.tongxie.copilotgo.data.tools.McpAuthMode
import com.tongxie.copilotgo.data.tools.McpNetworkTrust
import com.tongxie.copilotgo.data.tools.McpServerDraft
import com.tongxie.copilotgo.data.tools.ToolSettingsStore
import com.tongxie.copilotgo.data.tools.mcp.RemoteMcpService
import com.tongxie.copilotgo.data.tools.net.ToolHttpClient
import com.tongxie.copilotgo.data.tools.web.WebToolService
import com.tongxie.copilotgo.ui.agent.agentCallLabel
import com.tongxie.copilotgo.ui.agent.blockedAgentReplayMessageIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** Real production client/engine/executor/MCP stack; only the remote endpoints and secrets are fixtures. */
internal class AgentToolIntegrationScenario {
    suspend fun runConversation(
        modelFixture: AgentIntegrationModelFixture,
        legacySse: Boolean,
        failAfterDispatch: Boolean = false
    ) {
        modelFixture.use { core ->
            McpEndpoint(legacySse, failAfterDispatch).use { endpoint ->
                val job = SupervisorJob()
                val scope = CoroutineScope(job + Dispatchers.Default)
                val inheritedInterceptorCalls = AtomicInteger()
                val readinessCalls = AtomicInteger()
                val toolBase = OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .dns(endpoint.dns)
                    .sslSocketFactory(endpoint.clientTls.sslSocketFactory(), endpoint.clientTls.trustManager)
                    .addInterceptor { chain ->
                        inheritedInterceptorCalls.incrementAndGet()
                        chain.proceed(chain.request().newBuilder()
                            .header("Authorization", "Bearer fixture-bearer")
                            .header("Cookie", "account-cookie=fixture")
                            .build())
                    }
                    .build()
                val provider = object : HttpClientProvider {
                    override val client = toolBase
                    override suspend fun awaitReady() { readinessCalls.incrementAndGet() }
                }
                val settings = ToolSettingsStore(MemoryVault(), scope)
                val toolHttp = ToolHttpClient(provider)
                val remoteMcp = RemoteMcpService(settings, toolHttp, scope)
                val webTools = WebToolService(settings, toolHttp, remoteMcp)
                val executor = ConfiguredToolExecutor(settings, remoteMcp, webTools, scope)
                val center = ChatStreamCenter(
                    core.store, core.client, core.catalog, scope,
                    agentRunner = AgentEngine(
                        AgentModelTransport(core.client::streamAgentChat),
                        executor, AgentPromptBuilder(core.store.attachments), core.json
                    )
                )
                try {
                    settings.awaitReady()
                    val server = settings.saveServer(
                        null, null,
                        McpServerDraft(
                            label = "Controlled MCP fixture",
                            endpoint = endpoint.url,
                            enabled = true,
                            networkTrust = McpNetworkTrust.LOCAL_NETWORK,
                            authMode = McpAuthMode.BEARER,
                            enabledTools = setOf(REMOTE_TOOL)
                        ),
                        CredentialUpdate.Replace(MCP_SECRET)
                    )
                    val discovery = remoteMcp.discover(server.id, server.revision)
                    assertEquals(if (legacySse) LEGACY_VERSION else MODERN_VERSION, discovery.protocolVersion)
                    assertTrue(discovery.tools.single().supported)
                    val descriptor = executor.snapshot().tools.single()
                    assertEquals(server.id, descriptor.identity.configId)
                    assertEquals(REMOTE_TOOL, descriptor.identity.toolName)
                    assertNotEquals(REMOTE_TOOL, descriptor.name)
                    assertEquals(0, endpoint.toolCalls.get())

                    core.create(SESSION)
                    core.store.update(SESSION) {
                        it.copy(agentSettings = AgentSessionSettings(enabled = true, autoApprovePublicWebReads = true))
                    }
                    core.replies.add(MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody(interleavedProposal(descriptor.name)))
                    core.enqueueText(if (failAfterDispatch) {
                        "The controlled remote outcome is unknown; do not replay the operation."
                    } else "The controlled MCP result supports this answer [S1].")
                    val accepted = center.submit(
                        SESSION, PRIVATE_PROMPT, submissionId = "durable-fixture-draft"
                    )
                    assertTrue(accepted is SendResult.Accepted)
                    val approval = awaitApproval(center, APPROVED_CALL)
                    assertTrue(center.sendingFlow(SESSION).value)
                    assertEquals("MCP readOnlyHint is not execution consent", 0, endpoint.toolCalls.get())
                    val file = File(core.paths.sessions, "$SESSION.json")
                    val admitted = core.json.decodeFromString(Session.serializer(), file.readText())
                    assertEquals(approval.binding, admitted.messages.last().agentRun?.pendingApproval?.binding)
                    assertEquals("durable-fixture-draft", admitted.messages.first().submissionId)
                    assertTrue(center.submit(SESSION, "duplicate concurrent request") is SendResult.Rejected)
                    assertTrue(center.respondToApproval(
                        SESSION, approval.binding.copy(argumentsDigest = "wrong"), AgentApprovalDecision.APPROVE
                    ) is AgentApprovalResponse.Rejected)
                    assertEquals(0, endpoint.toolCalls.get())
                    assertEquals(AgentApprovalResponse.Accepted,
                        center.respondToApproval(SESSION, approval.binding, AgentApprovalDecision.APPROVE))

                    val denied = awaitApproval(center, DENIED_CALL)
                    assertEquals(1, endpoint.toolCalls.get())
                    assertEquals(AgentApprovalResponse.Accepted,
                        center.respondToApproval(SESSION, denied.binding, AgentApprovalDecision.DENY))
                    withTimeout(15_000) { center.sendingFlow(SESSION).first { !it } }
                    assertNull(center.errorFlow(SESSION).value)
                    val final = requireNotNull(core.store.getSession(SESSION))
                    val run = requireNotNull(final.messages.last().agentRun)
                    assertEquals(AgentRunStatus.COMPLETED, run.status)
                    assertNull(run.pendingApproval)
                    val calls = run.steps.first().toolCalls
                    assertEquals(listOf(APPROVED_CALL, DENIED_CALL), calls.map { it.id })
                    assertEquals(if (failAfterDispatch) AgentToolCallStatus.FAILED else AgentToolCallStatus.SUCCEEDED,
                        calls[0].status)
                    assertEquals(AgentToolCallStatus.DENIED, calls[1].status)
                    assertTrue(calls[1].result!!.isError)
                    if (failAfterDispatch) {
                        assertTrue(calls[0].outcomeUnknown)
                        assertTrue(calls[0].result!!.outcomeUnknown)
                        assertTrue(calls[0].result!!.isError)
                        assertTrue(calls[0].result!!.content.isNotBlank())
                        assertEquals(R.string.agent_outcome_unknown, agentCallLabel(calls[0]))
                        assertFalse(run.safeToRetry)
                        assertTrue(final.messages.last().id in blockedAgentReplayMessageIds(final.messages))
                        assertTrue(run.sources.isEmpty())
                        assertTrue(final.messages.last().content.contains("outcome is unknown"))
                        assertTrue(center.retryLastAndAwait(SESSION) is OperationResult.Rejected)
                        withTimeout(5000) { center.sendingFlow(SESSION).first { !it } }
                        assertTrue(center.regenerateAndAwait(SESSION, final.messages.last().id) is OperationResult.Rejected)
                        withTimeout(5000) { center.sendingFlow(SESSION).first { !it } }
                    } else {
                        assertTrue(calls[0].result!!.content.contains("ACTUAL_MCP_RESULT"))
                        assertEquals(SOURCE_URL, run.sources.single().url)
                        assertEquals(SourceKind.TOOL_RESOURCE, run.sources.single().kind)
                        assertEquals("S1", run.sources.single().id)
                        assertEquals(APPROVED_CALL, run.sources.single().toolCallId)
                        assertTrue(final.messages.last().content.contains("[S1]"))
                    }
                    assertEquals(run, core.json.decodeFromString(Session.serializer(), file.readText()).messages.last().agentRun)

                    val modelRequests = core.requests.filter { it.path == "/chat/completions" }
                    assertEquals(2, modelRequests.size)
                    modelRequests.forEach { assertEquals("Bearer fixture-bearer", it.getHeader("Authorization")) }
                    val continuation = core.json.decodeFromString(
                        AgentChatRequest.serializer(), modelRequests.last().body.clone().readUtf8()
                    )
                    val toolMessages = continuation.messages.filter { it.role == "tool" }
                    assertEquals(listOf(APPROVED_CALL, DENIED_CALL), toolMessages.map { it.toolCallId })
                    val continuedResult = core.json.decodeFromString(
                        AgentToolResult.serializer(), toolMessages.first().content!!.jsonPrimitive.content
                    )
                    assertEquals(failAfterDispatch, continuedResult.outcomeUnknown)
                    if (!failAfterDispatch) assertTrue(continuedResult.content.contains("ACTUAL_MCP_RESULT"))
                    assertEquals(1, endpoint.toolCalls.get())
                    val sentTool = endpoint.requests.single { it.method == "tools/call" }
                    assertEquals(REMOTE_TOOL, sentTool.message["params"]!!.jsonObject["name"]!!.jsonPrimitive.content)
                    assertEquals("public fixture query",
                        sentTool.message["params"]!!.jsonObject["arguments"]!!.jsonObject["query"]!!.jsonPrimitive.content)
                    assertTrue(readinessCalls.get() >= endpoint.requests.size)
                    assertEquals("Inherited account/header interceptors must never run for tools", 0, inheritedInterceptorCalls.get())
                    endpoint.requests.forEach {
                        assertEquals("Bearer $MCP_SECRET", it.headers["Authorization"])
                        assertNull(it.headers["Cookie"])
                        assertFalse(it.message.toString().contains(PRIVATE_PROMPT))
                        assertFalse(it.headers.toString().contains("fixture-bearer"))
                        assertFalse(it.headers.toString().contains("fixture-github"))
                    }

                    val modelAndHistory = modelRequests.joinToString { it.body.clone().readUtf8() } + file.readText()
                    assertFalse(modelAndHistory.contains(MCP_SECRET))
                    assertFalse(modelAndHistory.contains(MCP_SESSION))
                    if (legacySse) {
                        endpoint.requests.filter { it.method in setOf("notifications/initialized", "tools/list", "tools/call") }
                            .forEach { assertEquals(MCP_SESSION, it.headers["Mcp-Session-Id"]) }
                    } else {
                        endpoint.requests.forEach {
                            assertNull(it.headers["Mcp-Session-Id"])
                            assertEquals(MODERN_VERSION, it.headers["MCP-Protocol-Version"])
                            assertEquals(it.method, it.headers["Mcp-Method"])
                        }
                        assertEquals(REMOTE_TOOL, sentTool.headers["Mcp-Name"])
                    }
                    assertEquals(accepted, center.submit(SESSION, PRIVATE_PROMPT, submissionId = "durable-fixture-draft"))
                    withTimeout(5000) { center.sendingFlow(SESSION).first { !it } }
                    assertEquals(2, core.requests.count { it.path == "/chat/completions" })
                    assertEquals(1, endpoint.toolCalls.get())
                } finally {
                    center.close()
                    job.cancelAndJoin()
                    toolBase.connectionPool.evictAll()
                    toolBase.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    private suspend fun awaitApproval(center: ChatStreamCenter, callId: String): AgentApprovalRequest = withTimeout(15_000) {
        center.sessionFlow(SESSION).first {
            it?.messages?.lastOrNull()?.agentRun?.pendingApproval?.binding?.callId == callId
        }!!.messages.last().agentRun!!.pendingApproval!!
    }

    private fun interleavedProposal(name: String): String {
        val approved = """{"query":"public fixture query"}"""
        val denied = """{"query":"denied fixture query"}"""
        fun call(index: Int, id: String?, fragment: String) = buildJsonObject {
            put("index", index)
            if (id != null) { put("id", id); put("type", "function") }
            put("function", buildJsonObject {
                if (id != null) put("name", name)
                put("arguments", fragment)
            })
        }
        fun event(calls: List<JsonObject>? = null, finish: Boolean = false): String = "data: " + buildJsonObject {
            put("choices", JsonArray(listOf(buildJsonObject {
                put("index", 0)
                put("delta", if (calls == null) JsonObject(emptyMap()) else buildJsonObject {
                    put("tool_calls", JsonArray(calls))
                })
                if (finish) put("finish_reason", "tool_calls")
            })))
        } + "\n\n"
        return event(listOf(
            call(0, APPROVED_CALL, approved.take(12)),
            call(1, DENIED_CALL, denied.take(10))
        )) + event(listOf(
            call(1, null, denied.drop(10)), call(0, null, approved.drop(12))
        )) + event(finish = true) + "data: [DONE]\n\n"
    }

    private class MemoryVault : SecretVault {
        private val records = ConcurrentHashMap<String, String>()
        override suspend fun read(name: String): String? = records[name]
        override suspend fun write(name: String, value: String) { records[name] = value }
    }

    private data class McpRequest(val method: String, val headers: Headers, val message: JsonObject)

    private class McpEndpoint(
        private val legacy: Boolean,
        private val failAfterDispatch: Boolean
    ) : AutoCloseable {
        private val address = InetAddress.getByName("127.0.0.1")
        private val certificate = HeldCertificate.Builder()
            .commonName("Controlled MCP fixture").addSubjectAlternativeName("localhost").build()
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname != "localhost") throw UnknownHostException("Unexpected fixture hostname")
                return listOf(address)
            }
        }
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val server = MockWebServer()
        val requests = CopyOnWriteArrayList<McpRequest>()
        val toolCalls = AtomicInteger()
        val url: String get() = server.url("/mcp").newBuilder().host("localhost").build().toString()

        init {
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val message = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                    val method = message["method"]!!.jsonPrimitive.content
                    requests += McpRequest(method, request.headers, message)
                    if (legacy && method == "server/discover") {
                        return MockResponse().setResponseCode(400).setHeader("Content-Type", "text/plain").setBody("Legacy fixture")
                    }
                    if (method == "notifications/initialized") return MockResponse().setResponseCode(202)
                    val result = when (method) {
                        "server/discover" -> buildJsonObject {
                            put("supportedVersions", JsonArray(listOf(JsonPrimitive(MODERN_VERSION))))
                            put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                            put("_meta", buildJsonObject {
                                put("io.modelcontextprotocol/serverInfo", buildJsonObject {
                                    put("name", "controlled-mcp"); put("version", "fixture")
                                })
                            })
                        }
                        "initialize" -> buildJsonObject {
                            put("protocolVersion", LEGACY_VERSION)
                            put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                            put("serverInfo", buildJsonObject { put("name", "controlled-mcp"); put("version", "fixture") })
                        }
                        "tools/list" -> buildJsonObject {
                            put("tools", JsonArray(listOf(buildJsonObject {
                                put("name", REMOTE_TOOL)
                                put("description", "Controlled read-only fixture; annotations do not grant approval.")
                                put("annotations", buildJsonObject { put("readOnlyHint", true) })
                                put("inputSchema", Json.parseToJsonElement(
                                    """{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":64}},"required":["query"],"additionalProperties":false}"""
                                ))
                                put("outputSchema", Json.parseToJsonElement(
                                    """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}"""
                                ))
                            })))
                        }
                        "tools/call" -> {
                            toolCalls.incrementAndGet()
                            if (failAfterDispatch) {
                                return MockResponse().setResponseCode(307).setHeader(
                                    "Location", server.url("/must-not-replay").newBuilder().host("localhost").build()
                                )
                            }
                            buildJsonObject {
                                val value = "ACTUAL_MCP_RESULT; credential echo $MCP_SECRET" + if (legacy) " / $MCP_SESSION" else ""
                                put("isError", false)
                                put("structuredContent", buildJsonObject { put("value", value) })
                                put("content", JsonArray(listOf(
                                    buildJsonObject { put("type", "text"); put("text", value) },
                                    buildJsonObject {
                                        put("type", "resource_link"); put("uri", SOURCE_URL)
                                        put("name", "Controlled resource returned by the actual MCP operation")
                                    }
                                )))
                            }
                        }
                        else -> return MockResponse().setResponseCode(500)
                    }
                    val completed = if (legacy) result else JsonObject(result + ("resultType" to JsonPrimitive("complete")))
                    return reply(message["id"]!!, completed).apply {
                        if (method == "initialize") setHeader("Mcp-Session-Id", MCP_SESSION)
                    }
                }
            }
            server.start(address, 0)
        }

        private fun reply(id: JsonElement, result: JsonObject): MockResponse {
            val envelope = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
            return if (legacy) {
                MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("event: message\ndata: $envelope\n\n")
            } else {
                MockResponse().setHeader("Content-Type", "application/json").setBody(envelope.toString())
            }
        }

        override fun close() { server.shutdown() }
    }

    companion object {
        private const val SESSION = "fixture-session"
        private const val APPROVED_CALL = "call-approved"
        private const val DENIED_CALL = "call-denied"
        private const val REMOTE_TOOL = "read_fixture"
        private const val PRIVATE_PROMPT = "PRIVATE_CHAT_MARKER: consult the controlled public fixture only."
        private const val MCP_SECRET = "controlled-mcp-credential"
        private const val MCP_SESSION = "controlled-mcp-session"
        private const val SOURCE_URL = "https://example.com/controlled-mcp-resource"
        private const val MODERN_VERSION = "2026-07-28"
        private const val LEGACY_VERSION = "2025-11-25"
    }
}

internal interface AgentIntegrationModelFixture : AutoCloseable {
    val json: Json
    val client: CopilotChatClient
    val catalog: ModelCatalog
    val paths: AppPaths
    val store: SessionStore
    val requests: List<RecordedRequest>
    val replies: LinkedBlockingQueue<MockResponse>
    suspend fun create(id: String): Session
    fun enqueueText(text: String)
}
