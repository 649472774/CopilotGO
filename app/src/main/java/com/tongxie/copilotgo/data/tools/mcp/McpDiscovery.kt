package com.tongxie.copilotgo.data.tools.mcp

import com.tongxie.copilotgo.data.tools.ToolProblem
import kotlinx.serialization.json.JsonObject

data class McpDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject?,
    val supported: Boolean,
    val enabled: Boolean,
    val problem: ToolProblem? = null
)

data class McpDiscoveryReport(
    val serverId: String,
    val configRevision: Long,
    val protocolVersion: String,
    val serverName: String?,
    val tools: List<McpDiscoveredTool>,
    val discoveredAtMillis: Long
)

data class McpDiscoveryState(
    val configRevision: Long,
    val loading: Boolean = false,
    val report: McpDiscoveryReport? = null,
    val problem: ToolProblem? = null
)
