package com.tongxie.copilotgo.data.tools

import com.tongxie.copilotgo.data.agent.AgentToolResult
import kotlinx.serialization.json.Json

/** Leave room below the runtime's 32 KiB envelope for its assigned source/call IDs. */
internal fun boundAgentToolResult(result: AgentToolResult, maxBytes: Int = 24 * 1024): AgentToolResult {
    require(maxBytes >= 1024)
    var sources = result.sources.take(32)
    var candidate = result.copy(sources = sources, truncated = result.truncated || sources.size != result.sources.size)
    if (encodedResultBytes(candidate) <= maxBytes) return candidate
    while (sources.isNotEmpty() &&
        encodedResultBytes(candidate.copy(content = RESULT_LIMIT_NOTICE, sources = sources, truncated = true)) > maxBytes
    ) {
        sources = sources.dropLast(1)
    }
    var low = 0
    var high = minOf(result.content.toByteArray(Charsets.UTF_8).size, maxBytes)
    candidate = candidate.copy(content = RESULT_LIMIT_NOTICE, sources = sources, truncated = true)
    while (low <= high) {
        val mid = low + (high - low) / 2
        val prefix = boundToolText(result.content, mid).text
        val attempt = result.copy(content = prefix + RESULT_LIMIT_NOTICE, sources = sources, truncated = true)
        if (encodedResultBytes(attempt) <= maxBytes) {
            candidate = attempt
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return candidate
}

private val toolResultJson = Json { encodeDefaults = true }
private const val RESULT_LIMIT_NOTICE = "\n\n[工具输出已按大小限制截断]"

internal fun encodedResultBytes(result: AgentToolResult): Int =
    toolResultJson.encodeToString(AgentToolResult.serializer(), result).toByteArray(Charsets.UTF_8).size
