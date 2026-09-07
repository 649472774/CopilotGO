package com.tongxie.copilotgo.data.tools.mcp

import com.tongxie.copilotgo.BuildConfig
import com.tongxie.copilotgo.data.chat.SseParser
import com.tongxie.copilotgo.data.tools.BoundedToolJson
import com.tongxie.copilotgo.data.tools.ToolEndpointLease
import com.tongxie.copilotgo.data.tools.ToolException
import com.tongxie.copilotgo.data.tools.ToolProblem
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.net.ToolHttpClient
import com.tongxie.copilotgo.data.tools.net.ToolNetworkLimits
import com.tongxie.copilotgo.data.tools.toolFailure
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import java.util.concurrent.atomic.AtomicLong

internal data class McpConnection(
    val version: String,
    val sessionId: String? = null,
    val serverName: String? = null,
    val hasTools: Boolean = true
) {
    val modern: Boolean get() = version == MODERN_VERSION

    override fun toString(): String = "McpConnection(version=$version, session=<redacted>)"

    companion object {
        const val MODERN_VERSION = "2026-07-28"
        val LEGACY_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
    }
}

internal data class McpWireError(val code: Int, val supportedVersions: List<String> = emptyList())

internal data class McpWireResponse(
    val statusCode: Int,
    val result: JsonObject? = null,
    val error: McpWireError? = null,
    val sessionId: String? = null
) {
    override fun toString(): String = "McpWireResponse(status=$statusCode, error=${error?.code}, body=<redacted>)"
}

internal class McpWireClient(
    private val http: ToolHttpClient,
    private val assertCurrent: (ToolEndpointLease) -> Unit,
    private val clientVersion: String = BuildConfig.VERSION_NAME
) {
    suspend fun request(
        lease: ToolEndpointLease,
        connection: McpConnection,
        method: String,
        parameters: JsonObject = JsonObject(emptyMap()),
        parameterHeaders: Map<String, String> = emptyMap(),
        discoveryProbe: Boolean = false
    ): McpWireResponse = withTimeout(30_000) {
        assertCurrent(lease)
        val id = JsonPrimitive("copilotgo-${ids.incrementAndGet()}")
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            putJsonObject("params") {
                parameters.forEach { (key, value) -> put(key, value) }
                putJsonObject("_meta") {
                    put("progressToken", id)
                    if (connection.modern) {
                        put("io.modelcontextprotocol/protocolVersion", connection.version)
                        putJsonObject("io.modelcontextprotocol/clientInfo") {
                            put("name", "CopilotGO")
                            put("version", clientVersion)
                        }
                        put("io.modelcontextprotocol/clientCapabilities", JsonObject(emptyMap()))
                    }
                }
            }
        }
        val request = buildRequest(lease, connection, message, method, parameters, parameterHeaders)
        http.withResponse(
            request = request,
            policy = lease.networkPolicy,
            limits = ToolNetworkLimits(maxCompressedBytes = MAX_WIRE_BYTES, maxDecodedBytes = MAX_WIRE_BYTES),
            hasCredentials = lease.hasCredential || connection.sessionId != null,
            assertCurrent = { assertCurrent(lease) }
        ) { response ->
            checkAuthenticationAndQuota(response.statusCode)
            if (response.statusCode >= 500) {
                throw ToolException(ToolProblem(ToolProblemCode.NETWORK, "MCP 服务暂时不可用", true, response.statusCode))
            }
            if (response.statusCode == 404 && connection.sessionId != null) {
                return@withResponse McpWireResponse(404)
            }
            val contentType = response.headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase()
            val sessionId = response.headers["Mcp-Session-Id"]?.also(::validateSessionId)
            if (!connection.modern && connection.sessionId != null &&
                sessionId != null && connection.sessionId != sessionId
            ) {
                toolFailure(ToolProblemCode.PROTOCOL, "MCP 响应的会话标识与本次请求不一致")
            }
            val allowLegacyProbeError = discoveryProbe && response.statusCode in setOf(400, 404, 405)
            val source = McpUtf8Source(response.source).buffer()
            val parsed = when (contentType) {
                "application/json" -> {
                    val body = source.readUtf8().removePrefix("\uFEFF")
                    if (body.isBlank() && allowLegacyProbeError) null else {
                        parseResponse(
                            BoundedToolJson.objectValue(body), id, response.statusCode,
                            allowLegacyProbeError
                        )
                    }
                }
                "text/event-stream" -> {
                    var finalResponse: McpWireResponse? = null
                    var eventCount = 0
                    SseParser.events(source).firstOrNull { event ->
                        assertCurrent(lease)
                        if (++eventCount > MAX_EVENTS) {
                            toolFailure(ToolProblemCode.TOO_LARGE, "MCP 流式消息数量超过限制")
                        }
                        if (event.data.isEmpty() && event.event != "error") return@firstOrNull false
                        if (event.event != "message") {
                            toolFailure(ToolProblemCode.PROTOCOL, "MCP 流式服务返回了不支持的事件")
                        }
                        val value = BoundedToolJson.objectValue(event.data)
                        val incomingMethod = value.string("method")
                        if (incomingMethod != null) {
                            validateNotificationOrRequest(value, id)
                            if (value.containsKey("id")) {
                                if (connection.modern) {
                                    toolFailure(
                                        ToolProblemCode.UNSUPPORTED_INTERACTION,
                                        "此 MCP 服务尝试发起未支持的客户端请求，已拒绝"
                                    )
                                }
                                val serverRequestId = checkNotNull(value["id"])
                                sendLegacyReply(lease, connection, serverRequestId, incomingMethod == "ping")
                                if (incomingMethod != "ping") {
                                    toolFailure(
                                        ToolProblemCode.UNSUPPORTED_INTERACTION,
                                        "已拒绝 MCP 的采样、凭据交互或本地资源请求；本次操作没有被自动重试"
                                    )
                                }
                            }
                            false
                        } else {
                            finalResponse = parseResponse(value, id, response.statusCode, allowLegacyProbeError)
                            true
                        }
                    }
                    finalResponse ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 流在返回对应请求的结果前已中断")
                }
                else -> {
                    if (allowLegacyProbeError) null else {
                        toolFailure(ToolProblemCode.UNSUPPORTED_CONTENT, "MCP 服务必须返回 JSON 或请求级 SSE")
                    }
                }
            }
            assertCurrent(lease)
            (parsed ?: McpWireResponse(response.statusCode)).copy(sessionId = sessionId)
        }
    }

    suspend fun initialized(lease: ToolEndpointLease, connection: McpConnection) {
        postLegacyMessage(
            lease, connection,
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }
        )
    }

    private suspend fun sendLegacyReply(
        lease: ToolEndpointLease,
        connection: McpConnection,
        id: JsonElement,
        ping: Boolean
    ) {
        postLegacyMessage(
            lease, connection,
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                if (ping) {
                    put("result", JsonObject(emptyMap()))
                } else {
                    putJsonObject("error") {
                        put("code", -32601)
                        put("message", "Client capability not supported")
                    }
                }
            }
        )
    }

    private suspend fun postLegacyMessage(
        lease: ToolEndpointLease,
        connection: McpConnection,
        message: JsonObject
    ) {
        check(!connection.modern)
        val request = buildRequest(lease, connection, message)
        http.withResponse(
            request, policy = lease.networkPolicy,
            limits = ToolNetworkLimits(maxCompressedBytes = 4096, maxDecodedBytes = 4096),
            hasCredentials = lease.hasCredential || connection.sessionId != null,
            assertCurrent = { assertCurrent(lease) }
        ) {
            checkAuthenticationAndQuota(it.statusCode)
            if (it.statusCode != 202 || !it.source.exhausted()) {
                toolFailure(ToolProblemCode.PROTOCOL, "MCP 服务未确认客户端通知或协议拒绝消息")
            }
        }
    }

    private fun buildRequest(
        lease: ToolEndpointLease,
        connection: McpConnection,
        message: JsonObject,
        method: String? = null,
        parameters: JsonObject = JsonObject(emptyMap()),
        parameterHeaders: Map<String, String> = emptyMap()
    ): Request {
        val payload = message.toString()
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            toolFailure(ToolProblemCode.TOO_LARGE, "MCP 请求参数超过大小限制")
        }
        val builder = Request.Builder().url(lease.endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", connection.version)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
        if (connection.modern && method != null) {
            builder.header("Mcp-Method", method)
            if (method == "tools/call") {
                val name = parameters.string("name")
                    ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 工具调用缺少名称")
                builder.header("Mcp-Name", McpParameterHeaders.encode(name))
            }
        }
        parameterHeaders.forEach { (key, value) -> builder.header(key, value) }
        if (!connection.modern) connection.sessionId?.let { builder.header("Mcp-Session-Id", it) }
        lease.authorize(builder)
        return builder.build()
    }

    private fun parseResponse(
        value: JsonObject,
        expectedId: JsonPrimitive,
        httpStatus: Int,
        allowLegacyProbeError: Boolean
    ): McpWireResponse {
        if (value.string("jsonrpc") != "2.0" || "method" in value ||
            value.containsKey("result") == value.containsKey("error")
        ) {
            toolFailure(ToolProblemCode.PROTOCOL, "MCP 响应格式无效")
        }
        val errorObject = value["error"] as? JsonObject
        val codeValue = errorObject?.get("code") as? JsonPrimitive
        val code = codeValue?.takeUnless { it.isString }?.intOrNull
        if ("error" in value && (errorObject == null || code == null || errorObject.string("message") == null)) {
            toolFailure(ToolProblemCode.PROTOCOL, "MCP 错误消息格式无效")
        }
        val uncorrelatedLegacyRejection = allowLegacyProbeError && code in -32019..-32000 &&
            (value["id"] == null || value["id"] == JsonNull)
        if (value["id"] != expectedId && !uncorrelatedLegacyRejection) {
            toolFailure(ToolProblemCode.PROTOCOL, "MCP 响应标识与本次请求不一致")
        }
        if (errorObject != null && code != null) {
            val supported = if (code == -32022) {
                val versions = (errorObject["data"] as? JsonObject)?.get("supported") as? JsonArray
                    ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 版本协商响应缺少支持版本列表")
                if (versions.size !in 1..16) toolFailure(ToolProblemCode.PROTOCOL, "MCP 支持版本列表无效")
                versions.map {
                    (it as? JsonPrimitive)?.takeIf { item -> item.isString && item.content.length in 1..32 }?.content
                        ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 支持版本列表无效")
                }
            } else {
                emptyList()
            }
            return McpWireResponse(httpStatus, error = McpWireError(code, supported))
        }
        val result = value["result"] as? JsonObject
            ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 响应结果必须是对象")
        if (httpStatus !in 200..299) {
            toolFailure(ToolProblemCode.PROTOCOL, "MCP HTTP 状态与结果不一致")
        }
        return McpWireResponse(httpStatus, result = result)
    }

    private fun validateNotificationOrRequest(value: JsonObject, expectedProgressToken: JsonPrimitive) {
        if (value.string("jsonrpc") != "2.0" || "error" in value || "result" in value) {
            toolFailure(ToolProblemCode.PROTOCOL, "MCP 流式通知格式无效")
        }
        val id = value["id"]
        if (id != null && (id !is JsonPrimitive || id == JsonNull ||
                (!id.isString && id.content.toLongOrNull() == null))
        ) {
            toolFailure(ToolProblemCode.PROTOCOL, "MCP 客户端请求标识无效")
        }
        if (value.string("method") == "notifications/progress") {
            val parameters = value["params"] as? JsonObject
                ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 进度消息缺少参数")
            val progress = (parameters["progress"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            if (parameters["progressToken"] != expectedProgressToken || progress == null || !progress.isFinite()) {
                toolFailure(ToolProblemCode.PROTOCOL, "MCP 进度消息不属于本次请求或数值无效")
            }
        }
    }

    private fun checkAuthenticationAndQuota(status: Int) {
        when (status) {
            401, 403 -> throw ToolException(
                ToolProblem(ToolProblemCode.AUTHENTICATION_REQUIRED, "MCP 服务要求有效认证或拒绝此请求，请检查该服务的独立凭据", false, status)
            )
            429 -> throw ToolException(
                ToolProblem(ToolProblemCode.RATE_LIMITED, "搜索或 MCP 服务已达到速率或免费额度限制，请稍后重试", true, status)
            )
        }
    }

    private fun validateSessionId(value: String) {
        if (value.length !in 1..1024 || value.any { it.code !in 0x21..0x7e }) {
            toolFailure(ToolProblemCode.PROTOCOL, "MCP 会话标识无效")
        }
    }

    companion object {
        private val ids = AtomicLong()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_REQUEST_BYTES = 64 * 1024
        private const val MAX_WIRE_BYTES = 2L * 1024 * 1024
        private const val MAX_EVENTS = 256
    }
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
