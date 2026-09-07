package com.tongxie.copilotgo.data.tools.mcp

import com.tongxie.copilotgo.data.tools.McpServerSettings
import com.tongxie.copilotgo.data.tools.ToolEndpointLease
import com.tongxie.copilotgo.data.tools.ToolException
import com.tongxie.copilotgo.data.tools.ToolProblem
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.ToolSettingsLimits
import com.tongxie.copilotgo.data.tools.ToolSettingsStore
import com.tongxie.copilotgo.data.tools.net.ToolHttpClient
import com.tongxie.copilotgo.data.tools.net.ToolNetworkException
import com.tongxie.copilotgo.data.tools.staleConfiguration
import com.tongxie.copilotgo.data.tools.toolFailure
import com.tongxie.copilotgo.data.tools.toToolProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.IOException

class RemoteMcpService(
    private val settings: ToolSettingsStore,
    http: ToolHttpClient,
    scope: CoroutineScope
) {
    private val client = McpProtocolClient(McpWireClient(http, settings::assertCurrent), settings::assertCurrent)
    private val mutableDiscovery = MutableStateFlow<Map<String, McpDiscoveryState>>(emptyMap())
    val discovery = mutableDiscovery.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)
    internal val catalogRevision = mutableRevision.asStateFlow()

    init {
        scope.launch {
            settings.revisions.collect {
                val revisions = settings.revisions.value
                client.retainCurrent(revisions)
                mutableDiscovery.update { states ->
                    states.filter { (id, state) -> revisions[id] == state.configRevision }
                }
                changed()
            }
        }
    }

    /** This action sends only discovery/list requests, including when the saved server is disabled. */
    suspend fun discover(serverId: String, expectedRevision: Long): McpDiscoveryReport {
        val snapshot = settings.awaitReady()
        val server = snapshot.servers.firstOrNull { it.id == serverId && it.revision == expectedRevision }
            ?: staleConfiguration()
        return discoverGuarded(serverId, expectedRevision) {
            settings.withServer(serverId, expectedRevision, requireEnabled = false) { lease ->
                report(client.discover(lease), server.enabledTools)
            }
        }
    }

    /** Validates Exa availability and advertised schemas without sending a search query. */
    suspend fun discoverSearch(expectedRevision: Long): McpDiscoveryReport {
        settings.awaitReady()
        return discoverGuarded(ToolSettingsLimits.WEB_CONFIGURATION_ID, expectedRevision) {
            settings.withWeb(expectedRevision) { web, lease ->
                if (!web.searchEnabled) toolFailure(ToolProblemCode.DISABLED, "联网搜索已停用")
                report(client.discover(lease), setOf(EXA_SEARCH_TOOL))
            }
        }
    }

    internal fun currentCatalog(server: McpServerSettings): McpCatalog? =
        client.catalog(server.id, server.revision)?.takeIf { it.endpoint == server.endpoint }

    internal fun isCurrent(configurationId: String, revision: Long, name: String, digest: String): Boolean =
        settings.isCurrent(configurationId, revision) &&
            client.catalog(configurationId, revision)?.tools?.any {
                it.name == name && it.definitionDigest == digest
            } == true

    internal suspend fun call(
        serverId: String,
        revision: Long,
        name: String,
        digest: String,
        arguments: JsonObject
    ): McpCallContent = settings.withServer(serverId, revision) { lease ->
        val config = settings.awaitReady().servers.firstOrNull { it.id == serverId && it.revision == revision }
            ?: staleConfiguration()
        if (name !in config.enabledTools) toolFailure(ToolProblemCode.DISABLED, "此 MCP 工具尚未在设置中启用")
        val tool = client.catalog(serverId, revision)?.tools?.firstOrNull {
            it.name == name && it.definitionDigest == digest
        } ?: staleConfiguration()
        try {
            client.call(lease, tool, arguments)
        } catch (e: ToolException) {
            if (e.problem.code == ToolProblemCode.CONFIGURATION_CHANGED) {
                setState(serverId, revision, McpDiscoveryState(revision, problem = e.problem))
                changed()
            }
            throw e
        }
    }

    internal suspend fun search(lease: ToolEndpointLease, arguments: JsonObject): McpCallContent {
        val catalog = client.catalog(lease.configurationId, lease.revision) ?: client.discover(lease)
        val tool = catalog.tools.firstOrNull { it.name == EXA_SEARCH_TOOL }
            ?: toolFailure(ToolProblemCode.SCHEMA, "Exa 未提供受支持的 web_search_exa 工具，请检查服务或稍后重试")
        return client.call(lease, tool, arguments)
    }

    private suspend fun discoverGuarded(
        id: String,
        revision: Long,
        action: suspend () -> McpDiscoveryReport
    ): McpDiscoveryReport {
        if (!settings.isCurrent(id, revision)) staleConfiguration()
        setState(id, revision, McpDiscoveryState(revision, loading = true))
        try {
            val report = withTimeout(DISCOVERY_TIMEOUT_MILLIS) { action() }
            if (!settings.isCurrent(id, revision)) staleConfiguration()
            setState(id, revision, McpDiscoveryState(revision, report = report))
            changed()
            return report
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            val problem = ToolProblem(ToolProblemCode.NETWORK, "MCP 工具发现超时，未运行任何工具；可以重试", true)
            setState(id, revision, McpDiscoveryState(revision, problem = problem))
            throw ToolException(problem)
        } catch (e: CancellationException) {
            setState(id, revision, McpDiscoveryState(revision))
            throw e
        } catch (e: ToolException) {
            setState(id, revision, McpDiscoveryState(revision, problem = e.problem))
            throw e
        } catch (e: ToolNetworkException) {
            val problem = e.toToolProblem()
            setState(id, revision, McpDiscoveryState(revision, problem = problem))
            throw ToolException(problem)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            val problem = ToolProblem(ToolProblemCode.NETWORK, "无法连接 MCP 服务，请检查网络和应用代理配置", true)
            setState(id, revision, McpDiscoveryState(revision, problem = problem))
            throw ToolException(problem)
        }
    }

    private fun setState(id: String, revision: Long, value: McpDiscoveryState) {
        mutableDiscovery.update { current ->
            if (settings.isCurrent(id, revision)) current + (id to value) else current - id
        }
    }

    private fun report(catalog: McpCatalog, enabledTools: Set<String>) = McpDiscoveryReport(
        serverId = catalog.configurationId,
        configRevision = catalog.revision,
        protocolVersion = catalog.connection.version,
        serverName = catalog.connection.serverName,
        tools = catalog.tools.map {
            McpDiscoveredTool(it.name, it.description, it.input.definition, true, it.name in enabledTools)
        } + catalog.rejected.map {
            McpDiscoveredTool(it.name, "", null, false, false, it.problem)
        },
        discoveredAtMillis = System.currentTimeMillis()
    )

    private fun changed() {
        mutableRevision.update { it + 1 }
    }

    companion object {
        private const val EXA_SEARCH_TOOL = "web_search_exa"
        private const val DISCOVERY_TIMEOUT_MILLIS = 60_000L
    }
}
