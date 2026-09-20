package com.tongxie.copilotgo.data.chat

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal object AgentWireLimits {
    const val MAX_TOOLS = 24
    const val MAX_TOOL_DEFINITIONS = 64
    const val MAX_ARGUMENT_BYTES = 64 * 1024
    const val MAX_TOTAL_ARGUMENT_BYTES = 512 * 1024
    const val MAX_SCHEMA_BYTES = 64 * 1024
    const val MAX_TOTAL_TOOLS_BYTES = 512 * 1024
    const val MAX_EVENT_BYTES = 1024 * 1024
    const val MAX_STREAM_BYTES = 16 * 1024 * 1024
    const val MAX_EVENTS = 100_000
    const val MAX_JSON_DEPTH = 32
    const val MAX_JSON_NODES = 100_000
    const val MAX_REQUEST_BYTES = 24 * 1024 * 1024
    const val MAX_MESSAGES = 512
    const val MAX_NAME_CHARACTERS = 64
    const val MAX_TOOL_CALL_ID_CHARACTERS = 128
    const val MAX_RESPONSES_ID_CHARACTERS = 4096
}

internal fun agentCheck(condition: Boolean, message: String) {
    if (!condition) throw StreamProtocolException(message)
}

internal fun agentUtf8Size(text: String, limit: Int): Int {
    agentCheck(text.length <= limit, "Agent 数据超过大小限制")
    var bytes = 0
    var offset = 0
    while (offset < text.length) {
        val char = text[offset++]
        bytes += when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            char.isHighSurrogate() && offset < text.length && text[offset].isLowSurrogate() -> {
                offset++
                4
            }
            else -> 3
        }
        agentCheck(bytes <= limit, "Agent 数据超过大小限制")
    }
    return bytes
}

internal fun agentToolName(value: String) {
    agentCheck(
        value.length in 1..AgentWireLimits.MAX_NAME_CHARACTERS &&
            value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' },
        "工具名称必须是 1 至 64 位英文字母、数字、下划线或连字符"
    )
}

internal fun agentToolCallId(value: String) =
    agentOpaqueId(value, AgentWireLimits.MAX_TOOL_CALL_ID_CHARACTERS, "工具调用标识")

internal fun agentResponseId(value: String) =
    agentOpaqueId(value, AgentWireLimits.MAX_RESPONSES_ID_CHARACTERS, "Responses 回复标识")

internal fun agentOutputItemId(value: String) =
    agentOpaqueId(value, AgentWireLimits.MAX_RESPONSES_ID_CHARACTERS, "Responses 输出项标识")

private fun agentOpaqueId(value: String, limit: Int, field: String) {
    agentCheck(value.isNotEmpty(), "${field}为空")
    agentCheck(value.length <= limit, "${field}超过本地安全长度限制（$limit 字符）")
    agentCheck(value.all { it.code in 0x21..0x7e }, "${field}含有空白、控制或非 ASCII 字符")
}

internal object AgentJsonGuard {
    fun objectValue(text: String, maxBytes: Int): JsonObject {
        agentUtf8Size(text, maxBytes)
        try {
            Scanner(text).validate()
            return Json.parseToJsonElement(text) as? JsonObject
                ?: throw StreamProtocolException("Agent JSON 必须是对象")
        } catch (_: SerializationException) {
            throw StreamProtocolException("Agent JSON 格式损坏")
        }
    }

    /** Counts escaped wire bytes before encoding, and snapshots mutable JSON collections. */
    class TreeBudget(private val limit: Int) {
        var bytes = 0
            private set
        private var nodes = 0

        fun copy(element: JsonElement, depth: Int = 0): JsonElement {
            agentCheck(++nodes <= AgentWireLimits.MAX_JSON_NODES, "Agent JSON 节点过多")
            return when (element) {
                is JsonObject -> {
                    container(depth, element.size)
                    JsonObject(element.entries.associate { (key, value) ->
                        string(key)
                        add(1)
                        key to copy(value, depth + 1)
                    })
                }
                is JsonArray -> {
                    container(depth, element.size)
                    JsonArray(element.map { copy(it, depth + 1) })
                }
                is JsonPrimitive -> {
                    if (element.isString) string(element.content)
                    else add(agentUtf8Size(element.content, limit))
                    element
                }
            }
        }

        fun string(value: String) {
            add(2)
            add(agentUtf8Size(value, limit))
            for (char in value) {
                when {
                    char == '"' || char == '\\' -> add(1)
                    char.code < 0x20 -> add(5)
                }
            }
        }

        private fun container(depth: Int, size: Int) {
            agentCheck(depth < AgentWireLimits.MAX_JSON_DEPTH, "Agent JSON 嵌套过深")
            add(2)
            if (size > 0) add(size - 1)
        }

        private fun add(count: Int) {
            agentCheck(count <= limit - bytes, "Agent JSON 超过大小限制")
            bytes += count
        }
    }

    // JsonObject otherwise silently keeps the last duplicate key, making exact approvals ambiguous.
    private class Scanner(private val text: String) {
        private var offset = 0
        private var nodes = 0

        fun validate() {
            value(0)
            whitespace()
            agentCheck(offset == text.length, "Agent JSON 含有多余内容")
        }

        private fun value(depth: Int) {
            whitespace()
            agentCheck(++nodes <= AgentWireLimits.MAX_JSON_NODES, "Agent JSON 节点过多")
            when (peek()) {
                '{' -> {
                    nesting(depth)
                    offset++
                    whitespace()
                    if (take('}')) return
                    val keys = HashSet<String>()
                    do {
                        whitespace()
                        val start = offset
                        string()
                        val key = Json.parseToJsonElement(text.substring(start, offset)).jsonPrimitive.content
                        agentCheck(keys.add(key), "Agent JSON 含有重复对象字段")
                        whitespace()
                        expect(':')
                        value(depth + 1)
                        whitespace()
                    } while (take(','))
                    expect('}')
                }
                '[' -> {
                    nesting(depth)
                    offset++
                    whitespace()
                    if (take(']')) return
                    do {
                        value(depth + 1)
                        whitespace()
                    } while (take(','))
                    expect(']')
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> throw StreamProtocolException("Agent JSON 格式损坏")
            }
        }

        private fun nesting(depth: Int) =
            agentCheck(depth < AgentWireLimits.MAX_JSON_DEPTH, "Agent JSON 嵌套过深")

        private fun string() {
            expect('"')
            while (offset < text.length) {
                when (val char = text[offset++]) {
                    '"' -> return
                    '\\' -> {
                        agentCheck(offset < text.length, "Agent JSON 字符串不完整")
                        when (text[offset++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> repeat(4) {
                                agentCheck(peek() in "0123456789abcdefABCDEF", "Agent JSON 转义无效")
                                offset++
                            }
                            else -> throw StreamProtocolException("Agent JSON 转义无效")
                        }
                    }
                    else -> agentCheck(char.code >= 0x20, "Agent JSON 字符串无效")
                }
            }
            throw StreamProtocolException("Agent JSON 字符串不完整")
        }

        private fun number() {
            take('-')
            if (!take('0')) {
                agentCheck(peek() in '1'..'9', "Agent JSON 数值无效")
                digits()
            }
            if (take('.')) digits(required = true)
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                digits(required = true)
            }
        }

        private fun digits(required: Boolean = false) {
            val start = offset
            while (peek() in '0'..'9') offset++
            agentCheck(!required || offset > start, "Agent JSON 数值无效")
        }

        private fun literal(value: String) {
            agentCheck(text.startsWith(value, offset), "Agent JSON 值无效")
            offset += value.length
        }

        private fun whitespace() {
            while (peek() in " \t\r\n") offset++
        }

        private fun expect(char: Char) =
            agentCheck(take(char), "Agent JSON 格式损坏")

        private fun take(char: Char): Boolean {
            if (peek() != char) return false
            offset++
            return true
        }

        private fun peek(): Char = text.getOrNull(offset) ?: '\u0000'
    }
}
