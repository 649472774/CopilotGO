package com.tongxie.copilotgo.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Collections

internal object AgentValues {
    fun detached(value: JsonElement): JsonElement = detached(value, 0)

    fun detached(value: JsonObject): JsonObject =
        JsonObject(Collections.unmodifiableMap(value.mapValues { detached(it.value, 1) }))

    private fun detached(value: JsonElement, depth: Int): JsonElement {
        require(depth <= 64) { "Agent JSON nesting exceeds the safety limit" }
        return when (value) {
            is JsonObject -> JsonObject(Collections.unmodifiableMap(value.mapValues { detached(it.value, depth + 1) }))
            is JsonArray -> JsonArray(Collections.unmodifiableList(value.map { detached(it, depth + 1) }))
            else -> value
        }
    }

    fun canonical(value: JsonElement): String = canonical(value, 0)

    private fun canonical(value: JsonElement, depth: Int): String {
        require(depth <= 64) { "Agent JSON nesting exceeds the safety limit" }
        return when (value) {
            is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") {
                "${JsonPrimitive(it.key)}:${canonical(it.value, depth + 1)}"
            }
            is JsonArray -> value.joinToString(",", "[", "]") { canonical(it, depth + 1) }
            else -> value.toString()
        }
    }

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun descriptorDigest(tool: AgentToolDescriptor): String = digest(canonical(JsonObject(mapOf(
        "configId" to JsonPrimitive(tool.identity.configId),
        "configRevision" to JsonPrimitive(tool.identity.configRevision),
        "toolName" to JsonPrimitive(tool.identity.toolName),
        "definitionDigest" to JsonPrimitive(tool.identity.definitionDigest),
        "name" to JsonPrimitive(tool.name),
        "description" to JsonPrimitive(tool.description),
        "destination" to JsonPrimitive(tool.destination),
        "kind" to JsonPrimitive(tool.kind.name),
        "schema" to tool.inputSchema
    ))))

    fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    fun truncateUtf8(value: String, maximumBytes: Int): String {
        if (maximumBytes <= 0) return ""
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maximumBytes) return value
        var end = maximumBytes
        while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
        return bytes.copyOf(end).toString(Charsets.UTF_8)
    }
}

internal fun AgentRunRecord.detached(): AgentRunRecord = copy(
    steps = steps.map { step ->
        step.copy(toolCalls = step.toolCalls.map { call ->
            call.copy(
                arguments = call.arguments?.let(AgentValues::detached),
                result = call.result?.copy(sources = call.result.sources.toList())
            )
        })
    },
    pendingApproval = pendingApproval?.copy(arguments = AgentValues.detached(pendingApproval.arguments)),
    sources = sources.toList()
)

internal fun AgentRunRecord.interrupt(
    terminalStatus: AgentRunStatus,
    reason: String,
    now: Long = System.currentTimeMillis()
): AgentRunRecord = copy(
    status = terminalStatus,
    finishedAt = now,
    pendingApproval = null,
    notice = reason,
    steps = steps.map { step ->
        step.copy(toolCalls = step.toolCalls.map { call ->
            if (call.status !in setOf(
                AgentToolCallStatus.PROPOSED, AgentToolCallStatus.AWAITING_APPROVAL, AgentToolCallStatus.RUNNING
            )) call else {
                val unknown = call.outcomeUnknown ||
                    (call.status == AgentToolCallStatus.RUNNING && call.kind == AgentToolKind.MCP)
                call.copy(
                    status = if (terminalStatus == AgentRunStatus.CANCELLED && !unknown) {
                        AgentToolCallStatus.CANCELLED
                    } else AgentToolCallStatus.INTERRUPTED,
                    finishedAt = now,
                    outcomeUnknown = unknown,
                    result = AgentToolResult(
                        if (unknown) "$reason; remote outcome is unknown and must not be replayed automatically"
                        else "$reason; no successful tool result was received",
                        isError = true, outcomeUnknown = unknown
                    )
                )
            }
        })
    }
)
