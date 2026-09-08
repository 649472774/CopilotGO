package com.tongxie.copilotgo.data.tools.mcp

import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.tools.McpAuthMode
import com.tongxie.copilotgo.data.tools.McpNetworkTrust
import com.tongxie.copilotgo.data.tools.ToolEndpointLease
import com.tongxie.copilotgo.data.tools.net.ToolHttpClient
import com.tongxie.copilotgo.data.tools.staleConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.Closeable
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class McpProtocolFixture(
    val era: Era = Era.MODERN,
    val negotiatedVersion: String = LEGACY_VERSION,
    val issueSession: Boolean = true,
    authMode: McpAuthMode = McpAuthMode.NONE,
    credential: String? = null
) : Closeable {
    enum class Era { MODERN, LEGACY }

    val currentRevision = AtomicLong(1)
    val initializationCount = AtomicInteger()
    val callCount = AtomicInteger()
    val mutationCount = AtomicInteger()

    @Volatile var tools: List<JsonObject> = listOf(tool())
    @Volatile var serverName: String = "Synthetic MCP server"
    @Volatile var hasTools: Boolean = true
    @Volatile var handler: ((McpFixtureRequest) -> MockResponse?)? = null

    private val captured = CopyOnWriteArrayList<McpFixtureRequest>()
    private val dispatcherFailures = ConcurrentLinkedQueue<Throwable>()
    private val server = MockWebServer()
    private val httpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname != "localhost") throw UnknownHostException("Not a fixture hostname")
                return listOf(loopback)
            }
        })
        .retryOnConnectionFailure(false)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    val requests: List<McpFixtureRequest> get() = captured.toList()
    val endpoint: String
    val lease: ToolEndpointLease
    val protocol: McpProtocolClient

    init {
        try {
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = try {
                    val body = Json.parseToJsonElement(request.body.clone().readUtf8()) as? JsonObject
                        ?: error("Expected a JSON object in the fixture request")
                    val rpc = McpFixtureRequest(request, body)
                    captured += rpc
                    when (rpc.method) {
                        "initialize" -> initializationCount.incrementAndGet()
                        "tools/call" -> callCount.incrementAndGet()
                    }
                    handler?.invoke(rpc) ?: defaultResponse(rpc)
                } catch (failure: Throwable) {
                    // Fail on the test thread after closing sockets, never strand a dispatcher socket.
                    dispatcherFailures += failure
                    MockResponse().setResponseCode(500).setBody("Fixture dispatch failed")
                }
            }
            server.start(loopback, 0)
            endpoint = server.url("/mcp?fixture=protocol").newBuilder().host("localhost").build().toString()
            lease = ToolEndpointLease(
                configurationId = CONFIGURATION_ID,
                revision = 1,
                endpoint = endpoint,
                networkTrust = McpNetworkTrust.LOCAL_NETWORK,
                authMode = authMode,
                authHeaderName = if (authMode == McpAuthMode.BEARER) "Authorization" else AUTH_HEADER,
                credential = credential
            )
            val provider = object : HttpClientProvider {
                override val client: OkHttpClient = httpClient
            }
            val assertCurrent: (ToolEndpointLease) -> Unit = {
                if (it.configurationId != CONFIGURATION_ID || it.revision != currentRevision.get() ||
                    it.endpoint != endpoint
                ) {
                    staleConfiguration()
                }
            }
            protocol = McpProtocolClient(McpWireClient(ToolHttpClient(provider), assertCurrent), assertCurrent)
        } catch (failure: Throwable) {
            try {
                close()
            } catch (closingFailure: Throwable) {
                failure.addSuppressed(closingFailure)
            }
            throw failure
        }
    }

    fun requestsFor(method: String?): List<McpFixtureRequest> = requests.filter { it.method == method }

    fun complete(result: JsonObject): JsonObject = if (era == Era.MODERN) {
        JsonObject(mapOf("resultType" to JsonPrimitive("complete")) + result)
    } else {
        result
    }

    fun reply(request: McpFixtureRequest, result: JsonObject): MockResponse =
        request.json(complete(result))

    fun sse(
        request: McpFixtureRequest,
        result: JsonObject,
        prefix: String = "",
        suffix: String = ""
    ): MockResponse = stream(prefix + event(request.envelope(complete(result))) + suffix)

    fun discoveryResult(): JsonObject = buildJsonObject {
        put("supportedVersions", JsonArray(listOf(JsonPrimitive(MODERN_VERSION))))
        put("capabilities", capabilities())
        putJsonObject("_meta") {
            putJsonObject("io.modelcontextprotocol/serverInfo") {
                put("name", serverName)
                put("version", "fixture-1")
            }
        }
    }

    fun initializationResult(): JsonObject = buildJsonObject {
        put("protocolVersion", negotiatedVersion)
        put("capabilities", capabilities())
        putJsonObject("serverInfo") {
            put("name", serverName)
            put("version", "fixture-1")
        }
    }

    fun mutationResult(): JsonObject =
        textResult("Fixture mutation ${mutationCount.incrementAndGet()}")

    private fun capabilities(): JsonObject = buildJsonObject {
        if (hasTools) put("tools", JsonObject(emptyMap()))
    }

    private fun defaultResponse(request: McpFixtureRequest): MockResponse = when (request.method) {
        "server/discover" -> if (era == Era.MODERN) {
            reply(request, discoveryResult())
        } else {
            request.error(-32000, status = 400, responseId = JsonNull, message = "not initialized")
        }
        "initialize" -> {
            check(era == Era.LEGACY) { "A modern fixture must not be initialized" }
            request.json(initializationResult()).apply {
                if (issueSession) setHeader("Mcp-Session-Id", "fixture-session-${initializationCount.get()}")
            }
        }
        "notifications/initialized" -> {
            check(era == Era.LEGACY) { "A modern fixture must not receive initialized" }
            accepted()
        }
        "tools/list" -> reply(request, listing(tools))
        "tools/call" -> reply(request, mutationResult())
        null -> {
            check(era == Era.LEGACY && request.id != null &&
                ("result" in request.body || "error" in request.body)
            ) { "Unexpected client message" }
            accepted()
        }
        else -> error("Unexpected method: ${request.method}")
    }

    override fun close() {
        httpClient.dispatcher.cancelAll()
        try {
            server.shutdown()
        } finally {
            httpClient.connectionPool.evictAll()
            httpClient.dispatcher.executorService.shutdownNow()
        }
        dispatcherFailures.poll()?.let { throw AssertionError("MCP fixture dispatch failed", it) }
    }

    companion object {
        const val MODERN_VERSION = "2026-07-28"
        const val LEGACY_VERSION = "2025-11-25"
        const val CONFIGURATION_ID = "protocol-fixture"
        const val AUTH_HEADER = "X-Fixture-Key"
        const val FAKE_KEY = "fixture-key-not-a-real-credential"
        const val REMOTE_DIAGNOSTIC = "untrusted-remote-diagnostic-do-not-display"
        const val TOOL_NAME = "web_search_exa"

        private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val certificate by lazy {
            HeldCertificate.Builder()
                .commonName("MCP local HTTPS fixture")
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .build()
        }
        private val serverCertificates by lazy {
            HandshakeCertificates.Builder().heldCertificate(certificate).build()
        }
        private val clientCertificates by lazy {
            HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        }

        fun querySchema(draft07: Boolean = false, header: String? = null): JsonObject = buildJsonObject {
            if (draft07) put("\$schema", "http://json-schema.org/draft-07/schema#")
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("minLength", 1)
                    if (header != null) put("x-mcp-header", header)
                }
            }
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
            put("additionalProperties", false)
        }

        fun tool(
            name: String = TOOL_NAME,
            input: JsonObject = querySchema(),
            output: JsonObject? = null,
            description: String = "Synthetic query tool"
        ): JsonObject = buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", input)
            if (output != null) put("outputSchema", output)
        }

        fun arguments(query: String = "synthetic query"): JsonObject = buildJsonObject {
            put("query", query)
        }

        fun listing(tools: List<JsonObject>, cursor: JsonElement? = null): JsonObject = buildJsonObject {
            put("tools", JsonArray(tools))
            if (cursor != null) put("nextCursor", cursor)
        }

        fun textResult(text: String, isError: Boolean? = null): JsonObject = buildJsonObject {
            put("content", JsonArray(listOf(buildJsonObject {
                put("type", "text")
                put("text", text)
            })))
            if (isError != null) put("isError", isError)
        }

        fun event(message: JsonObject): String = "event: message\ndata: $message\n\n"

        fun stream(body: String): MockResponse = MockResponse()
            .setHeader("Content-Type", "text/event-stream; charset=utf-8")
            .setChunkedBody(body, 37)

        fun accepted(): MockResponse = MockResponse().setResponseCode(202)
    }
}

internal class McpFixtureRequest(val http: RecordedRequest, val body: JsonObject) {
    val method: String? get() = (body["method"] as? JsonPrimitive)?.content
    val id: JsonElement? get() = body["id"]
    val params: JsonObject get() = body["params"] as? JsonObject ?: JsonObject(emptyMap())
    val meta: JsonObject get() = params["_meta"] as? JsonObject ?: JsonObject(emptyMap())

    fun envelope(result: JsonObject, responseId: JsonElement? = id): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        if (responseId != null) put("id", responseId)
        put("result", result)
    }

    fun json(result: JsonObject, responseId: JsonElement? = id): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(envelope(result, responseId).toString())

    fun error(
        code: Int,
        status: Int = 400,
        responseId: JsonElement? = id,
        supported: List<String>? = null,
        message: String = McpProtocolFixture.REMOTE_DIAGNOSTIC
    ): MockResponse = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(buildJsonObject {
            put("jsonrpc", "2.0")
            if (responseId != null) put("id", responseId)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
                if (supported != null) putJsonObject("data") {
                    put("supported", JsonArray(supported.map(::JsonPrimitive)))
                }
            }
        }.toString())

    fun progress(
        token: JsonElement? = meta["progressToken"],
        amount: JsonElement = JsonPrimitive(1)
    ): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "notifications/progress")
        putJsonObject("params") {
            if (token != null) put("progressToken", token)
            put("progress", amount)
        }
    }
}
