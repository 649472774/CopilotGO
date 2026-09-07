package com.tongxie.copilotgo.data.tools.mcp

import com.tongxie.copilotgo.BuildConfig
import com.tongxie.copilotgo.data.tools.BoundedToolJson
import com.tongxie.copilotgo.data.tools.ToolEndpointLease
import com.tongxie.copilotgo.data.tools.ToolException
import com.tongxie.copilotgo.data.tools.ToolProblem
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.schema.ToolSchemaCompiler
import com.tongxie.copilotgo.data.tools.schema.ValidatedToolSchema
import com.tongxie.copilotgo.data.tools.staleConfiguration
import com.tongxie.copilotgo.data.tools.toolFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal data class McpToolSpec(
    val name: String,
    val description: String,
    val input: ValidatedToolSchema,
    val output: ValidatedToolSchema?,
    val headers: McpParameterHeaders,
    val definitionDigest: String
)

internal data class McpRejectedTool(val name: String, val problem: ToolProblem)

internal class McpCatalog(
    val configurationId: String,
    val revision: Long,
    val endpoint: String,
    val connection: McpConnection,
    val tools: List<McpToolSpec>,
    val rejected: List<McpRejectedTool>
) {
    override fun toString(): String = "McpCatalog($configurationId, revision=$revision, tools=${tools.size})"
}

internal class McpProtocolClient(
    private val wire: McpWireClient,
    private val assertCurrent: (ToolEndpointLease) -> Unit
) {
    private val discoveryMutex = Mutex()
    private val cacheLock = Any()
    private val catalogs = mutableMapOf<String, McpCatalog>()

    fun catalog(configurationId: String, revision: Long): McpCatalog? = synchronized(cacheLock) {
        catalogs[configurationId]?.takeIf { it.revision == revision }
    }

    fun retainCurrent(revisions: Map<String, Long>) = synchronized(cacheLock) {
        catalogs.entries.removeAll { revisions[it.key] != it.value.revision }
    }

    suspend fun discover(lease: ToolEndpointLease): McpCatalog = withContext(Dispatchers.IO) {
        discoveryMutex.withLock {
            assertCurrent(lease)
            val cached = catalog(lease.configurationId, lease.revision)?.takeIf { it.endpoint == lease.endpoint }
            var connection = cached?.connection ?: negotiate(lease)
            var listing = listTools(lease, connection)
            if (listing.expired) {
                invalidate(lease)
                // Only read-only discovery is retried; tools/call never takes this path.
                connection = negotiate(lease)
                listing = listTools(lease, connection)
                if (listing.expired) toolFailure(ToolProblemCode.PROTOCOL, "MCP 会话再次失效，请检查服务器配置")
            }
            val catalog = McpCatalog(
                lease.configurationId, lease.revision, lease.endpoint, connection, listing.tools, listing.rejected
            )
            assertCurrent(lease)
            synchronized(cacheLock) {
                if (catalogs.size >= 17 && lease.configurationId !in catalogs) {
                    toolFailure(ToolProblemCode.TOO_LARGE, "MCP 缓存服务器数量超过限制")
                }
                catalogs[lease.configurationId] = catalog
            }
            catalog
        }
    }

    suspend fun call(
        lease: ToolEndpointLease,
        expected: McpToolSpec,
        arguments: JsonObject
    ): McpCallContent = withContext(Dispatchers.IO) {
        assertCurrent(lease)
        val catalog = catalog(lease.configurationId, lease.revision)?.takeIf { it.endpoint == lease.endpoint }
            ?: staleConfiguration()
        val tool = catalog.tools.firstOrNull {
            it.name == expected.name && it.definitionDigest == expected.definitionDigest
        } ?: staleConfiguration()
        BoundedToolJson.objectValue(arguments.toString(), 32 * 1024)
        tool.input.validate(arguments)
        val headers = tool.headers.forArguments(arguments)
        val response = wire.request(
            lease, catalog.connection, "tools/call",
            buildJsonObject {
                put("name", tool.name)
                put("arguments", arguments)
            },
            parameterHeaders = headers
        )
        if (response.statusCode == 404 && catalog.connection.sessionId != null) {
            invalidate(lease)
            throw ToolException(
                ToolProblem(
                    ToolProblemCode.CONFIGURATION_CHANGED,
                    "MCP 会话已过期，请重新发现工具；本次调用没有被自动重试",
                    httpStatus = 404
                )
            )
        }
        val result = requireComplete(response, catalog.connection)
        assertCurrent(lease)
        McpResultContent.parse(result, tool.output) { text -> redact(text, lease, catalog.connection) }
    }

    private suspend fun negotiate(lease: ToolEndpointLease): McpConnection {
        val modern = McpConnection(McpConnection.MODERN_VERSION)
        val response = wire.request(lease, modern, "server/discover", discoveryProbe = true)
        val error = response.error
        if (error?.code == -32022) {
            val legacy = McpConnection.LEGACY_VERSIONS.firstOrNull { it in error.supportedVersions }
            if (legacy != null && McpConnection.MODERN_VERSION !in error.supportedVersions) {
                return initialize(lease, legacy)
            }
            toolFailure(ToolProblemCode.PROTOCOL, "MCP 服务没有可共同使用的协议版本，或返回了矛盾的版本协商结果")
        }
        if (error?.code in setOf(-32020, -32021, -32042) ||
            (response.statusCode == 404 && error?.code == -32601)
        ) {
            requireComplete(response, modern)
        }
        if (response.result != null) {
            val result = requireComplete(response, modern)
            val versions = result["supportedVersions"] as? JsonArray
                ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 发现响应缺少支持版本列表")
            if (versions.size !in 1..16 || versions.any {
                    it !is JsonPrimitive || !it.isString || it.content.length !in 1..32
                }
            ) {
                toolFailure(ToolProblemCode.PROTOCOL, "MCP 发现响应的版本列表无效")
            }
            if (versions.none { (it as JsonPrimitive).content == McpConnection.MODERN_VERSION }) {
                toolFailure(ToolProblemCode.PROTOCOL, "MCP 发现响应未确认当前协议版本")
            }
            val capabilities = result["capabilities"] as? JsonObject
                ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 发现响应缺少能力声明")
            val server = ((result["_meta"] as? JsonObject)
                ?.get("io.modelcontextprotocol/serverInfo") as? JsonObject)?.string("name")
            return modern.copy(
                serverName = server?.let { lease.redact(it).take(128) },
                hasTools = capabilities["tools"] is JsonObject
            )
        }
        if (response.statusCode in setOf(400, 404, 405) ||
            (response.statusCode in 200..299 && error?.code == -32601)
        ) {
            return initialize(lease, McpConnection.LEGACY_VERSIONS.first())
        }
        requireComplete(response, modern)
        toolFailure(ToolProblemCode.PROTOCOL, "MCP 协议发现失败")
    }

    private suspend fun initialize(lease: ToolEndpointLease, requestedVersion: String): McpConnection {
        val requested = McpConnection(requestedVersion)
        val response = wire.request(
            lease, requested, "initialize",
            buildJsonObject {
                put("protocolVersion", requestedVersion)
                put("capabilities", JsonObject(emptyMap()))
                putJsonObject("clientInfo") {
                    put("name", "CopilotGO")
                    put("version", BuildConfig.VERSION_NAME)
                }
            },
            discoveryProbe = true
        )
        if (response.result == null && response.error == null && response.statusCode in setOf(400, 404, 405)) {
            toolFailure(
                ToolProblemCode.UNSUPPORTED_TRANSPORT,
                "该地址不支持远程 Streamable HTTP MCP；旧 HTTP+SSE 或桌面 stdio 需要 HTTPS 桥接服务"
            )
        }
        val result = requireComplete(response, requested)
        val version = result.string("protocolVersion")
        if (version !in McpConnection.LEGACY_VERSIONS) {
            toolFailure(ToolProblemCode.PROTOCOL, "MCP 服务选择了尚未支持的旧版协议")
        }
        val capabilities = result["capabilities"] as? JsonObject
            ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 初始化响应缺少能力声明")
        val serverInfo = result["serverInfo"] as? JsonObject
            ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 初始化响应缺少服务标识")
        val connection = McpConnection(
            checkNotNull(version), response.sessionId,
            serverInfo.string("name")?.let {
                val name = lease.redact(it)
                (response.sessionId?.let { session -> name.replace(session, "[redacted]") } ?: name).take(128)
            },
            capabilities["tools"] is JsonObject
        )
        wire.initialized(lease, connection)
        return connection
    }

    private suspend fun listTools(lease: ToolEndpointLease, connection: McpConnection): Listing {
        if (!connection.hasTools) return Listing()
        val tools = mutableListOf<McpToolSpec>()
        val rejected = mutableListOf<McpRejectedTool>()
        val allNames = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var pages = 0
        var definitions = 0
        var invalidNames = 0
        do {
            if (++pages > MAX_PAGES) toolFailure(ToolProblemCode.TOO_LARGE, "MCP 工具分页数量超过限制")
            val response = wire.request(
                lease, connection, "tools/list",
                buildJsonObject { cursor?.let { put("cursor", it) } }
            )
            if (response.statusCode == 404 && connection.sessionId != null) return Listing(expired = true)
            val result = requireComplete(response, connection)
            val listed = result["tools"] as? JsonArray
                ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 工具列表格式无效")
            definitions += listed.size
            if (definitions > MAX_TOOLS) toolFailure(ToolProblemCode.TOO_LARGE, "MCP 工具数量超过限制")
            listed.forEach { value ->
                val definition = value as? JsonObject
                    ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 工具定义必须是对象")
                val name = definition.string("name")
                if (name == null || name.isBlank() || name.length > 128 || name.any(Char::isISOControl) ||
                    redact(name, lease, connection) != name
                ) {
                    rejected += McpRejectedTool("(invalid tool ${++invalidNames})", ToolProblem(ToolProblemCode.SCHEMA, "工具名称无效或含认证信息"))
                    return@forEach
                }
                if (!allNames.add(name)) {
                    tools.removeAll { it.name == name }
                    rejected.removeAll { it.name == name }
                    rejected += McpRejectedTool(name, ToolProblem(ToolProblemCode.SCHEMA, "服务器返回了重复工具名称"))
                    return@forEach
                }
                try {
                    val raw = definition.toString()
                    if (redact(raw, lease, connection) != raw) {
                        toolFailure(ToolProblemCode.SCHEMA, "工具定义包含认证信息，已阻止暴露给模型")
                    }
                    BoundedToolJson.objectValue(raw, 48 * 1024)
                    val input = definition["inputSchema"] as? JsonObject
                        ?: toolFailure(ToolProblemCode.SCHEMA, "工具缺少有效的输入 JSON Schema")
                    val output = definition["outputSchema"]?.let {
                        it as? JsonObject ?: toolFailure(ToolProblemCode.SCHEMA, "工具输出 Schema 必须是对象")
                    }
                    val description = definition.string("description") ?: definition.string("title") ?: name
                    if (description.length > 4096) toolFailure(ToolProblemCode.SCHEMA, "工具描述过长")
                    tools += McpToolSpec(
                        name, description, ToolSchemaCompiler.compile(input),
                        output?.let(ToolSchemaCompiler::compile),
                        McpParameterHeaders.compile(input),
                        BoundedToolJson.digest(definition)
                    )
                } catch (e: ToolException) {
                    if (e.problem.code !in setOf(ToolProblemCode.SCHEMA, ToolProblemCode.TOO_LARGE)) throw e
                    rejected += McpRejectedTool(name, e.problem)
                }
            }
            cursor = if ("nextCursor" in result) {
                result.string("nextCursor")
                    ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 分页游标类型无效")
            } else {
                null
            }
            cursor?.let {
                if (it.isEmpty() || it.length > 2048 || !cursors.add(it)) {
                    toolFailure(ToolProblemCode.PROTOCOL, "MCP 分页游标为空、重复或过长")
                }
            }
        } while (cursor != null)
        return Listing(tools, rejected)
    }

    private fun requireComplete(response: McpWireResponse, connection: McpConnection): JsonObject {
        response.error?.let { error ->
            val problem = when (error.code) {
                -32021, -32042 -> ToolProblem(
                    ToolProblemCode.UNSUPPORTED_INTERACTION,
                    "此 MCP 工具需要尚未支持的采样、交互授权或本地资源能力，未自动提供任何数据"
                )
                -32020 -> ToolProblem(ToolProblemCode.PROTOCOL, "MCP 请求头与工具定义不一致，请重新发现工具；本次操作未被重试")
                -32022 -> ToolProblem(ToolProblemCode.PROTOCOL, "MCP 协议版本已变化，请重新发现工具；本次操作未被重试")
                -32601 -> ToolProblem(ToolProblemCode.UNSUPPORTED_TRANSPORT, "MCP 服务不支持请求的方法")
                else -> ToolProblem(ToolProblemCode.PROTOCOL, "MCP 服务拒绝本次请求（错误码 ${error.code}）")
            }
            throw ToolException(problem.copy(httpStatus = response.statusCode))
        }
        val result = response.result ?: throw ToolException(
            ToolProblem(ToolProblemCode.PROTOCOL, "MCP 请求没有返回可用结果", httpStatus = response.statusCode)
        )
        when (val type = result.string("resultType")) {
            "complete" -> Unit
            null -> if (connection.modern || "resultType" in result) {
                toolFailure(ToolProblemCode.PROTOCOL, "现代 MCP 响应缺少有效的 resultType")
            }
            "input_required" -> toolFailure(
                ToolProblemCode.UNSUPPORTED_INTERACTION,
                "MCP 服务要求额外采样、凭据交互或本地资源；当前不支持，且不会自动继续或重试"
            )
            else -> toolFailure(ToolProblemCode.PROTOCOL, "MCP 返回了尚未支持的结果类型")
        }
        return result
    }

    private fun invalidate(lease: ToolEndpointLease) = synchronized(cacheLock) {
        if (catalogs[lease.configurationId]?.revision == lease.revision) catalogs.remove(lease.configurationId)
    }

    private fun redact(text: String, lease: ToolEndpointLease, connection: McpConnection): String {
        val value = lease.redact(text)
        return connection.sessionId?.let { value.replace(it, "[redacted]") } ?: value
    }

    private data class Listing(
        val tools: List<McpToolSpec> = emptyList(),
        val rejected: List<McpRejectedTool> = emptyList(),
        val expired: Boolean = false
    )

    companion object {
        private const val MAX_PAGES = 8
        private const val MAX_TOOLS = 128
    }
}
