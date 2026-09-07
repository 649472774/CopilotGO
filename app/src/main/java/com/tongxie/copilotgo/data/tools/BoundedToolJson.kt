package com.tongxie.copilotgo.data.tools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

internal object BoundedToolJson {
    const val MAX_DEPTH = 32
    const val MAX_NODES = 16_384
    val json = Json { isLenient = false }

    fun parse(text: String, maxBytes: Int = 2 * 1024 * 1024): JsonElement {
        if (text.length > maxBytes || text.toByteArray(Charsets.UTF_8).size > maxBytes) {
            toolFailure(ToolProblemCode.TOO_LARGE, "工具 JSON 数据超过大小限制")
        }
        checkStructure(text)
        return try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            toolFailure(ToolProblemCode.PROTOCOL, "工具返回了无效 JSON")
        } catch (_: IllegalArgumentException) {
            toolFailure(ToolProblemCode.PROTOCOL, "工具返回了无效 JSON")
        }
    }

    fun objectValue(text: String, maxBytes: Int = 2 * 1024 * 1024): JsonObject =
        parse(text, maxBytes) as? JsonObject
            ?: toolFailure(ToolProblemCode.PROTOCOL, "MCP 消息必须是单个 JSON 对象，不能是批量消息")

    fun digest(value: JsonElement): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonical(value).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
            "${JsonPrimitive(it.key)}:${canonical(it.value)}"
        }
        is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
        else -> value.toString()
    }

    /** Bound nesting before the allocating parser, and reject duplicate (including escaped) keys. */
    private fun checkStructure(text: String) {
        val stack = ArrayDeque<Container>()
        var index = 0
        var nodes = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> {
                    if (++nodes > MAX_NODES || stack.size >= MAX_DEPTH) {
                        toolFailure(ToolProblemCode.TOO_LARGE, "工具 JSON 嵌套或节点数量超过限制")
                    }
                    stack.addLast(Container(text[index] == '{'))
                }
                '}', ']' -> {
                    val current = stack.removeLastOrNull()
                        ?: toolFailure(ToolProblemCode.PROTOCOL, "工具 JSON 结构无效")
                    if (current.isObject != (text[index] == '}')) {
                        toolFailure(ToolProblemCode.PROTOCOL, "工具 JSON 结构无效")
                    }
                }
                ',' -> stack.lastOrNull()?.let { if (it.isObject) it.expectingKey = true }
                '"' -> {
                    val start = index
                    index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    if (index >= text.length) toolFailure(ToolProblemCode.PROTOCOL, "工具 JSON 字符串未结束")
                    if (++nodes > MAX_NODES) toolFailure(ToolProblemCode.TOO_LARGE, "工具 JSON 节点数量超过限制")
                    stack.lastOrNull()?.takeIf { it.isObject && it.expectingKey }?.let { container ->
                        val key = try {
                            (json.parseToJsonElement(text.substring(start, index + 1)) as? JsonPrimitive)?.content
                                ?: toolFailure(ToolProblemCode.PROTOCOL, "工具 JSON 属性名称无效")
                        } catch (_: SerializationException) {
                            toolFailure(ToolProblemCode.PROTOCOL, "工具 JSON 属性名称无效")
                        }
                        if (!container.keys.add(key)) {
                            toolFailure(ToolProblemCode.PROTOCOL, "工具 JSON 含有重复属性，已拒绝歧义消息")
                        }
                        container.expectingKey = false
                    }
                }
                '-', in '0'..'9', 't', 'f', 'n' -> {
                    if (++nodes > MAX_NODES) toolFailure(ToolProblemCode.TOO_LARGE, "工具 JSON 节点数量超过限制")
                    while (index + 1 < text.length &&
                        !text[index + 1].isWhitespace() && text[index + 1] !in charArrayOf(',', ']', '}')
                    ) {
                        index++
                    }
                }
            }
            index++
        }
        if (stack.isNotEmpty()) toolFailure(ToolProblemCode.PROTOCOL, "工具 JSON 结构未结束")
    }

    private class Container(val isObject: Boolean) {
        var expectingKey: Boolean = isObject
        val keys = mutableSetOf<String>()
    }
}
