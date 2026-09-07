package com.tongxie.copilotgo.data.tools.schema

import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.toolFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** These limits apply before either Jackson or the schema evaluator sees untrusted data. */
internal object BoundedSchemaJson {
    const val MAX_BYTES = 32 * 1024
    const val MAX_DEPTH = 16
    const val MAX_SCHEMA_NODES = 256
    const val MAX_INSTANCE_NODES = 2_048
    const val MAX_ARRAY_ITEMS = 256
    const val MAX_OBJECT_PROPERTIES = 128
    const val MAX_STRING_LENGTH = 16 * 1024
    const val MAX_KEY_LENGTH = 256
    const val MAX_NUMBER_LENGTH = 128
    const val MAX_NUMBER_DIGITS = 64
    const val MAX_NUMBER_EXPONENT = 128

    fun schema(value: JsonObject): JsonObject =
        Budget(MAX_SCHEMA_NODES).snapshot(value, 1) as JsonObject

    fun instance(value: JsonElement): String =
        text(Budget(MAX_INSTANCE_NODES).snapshot(value, 1))

    fun text(value: JsonElement): String = value.toString().also {
        if (it.length > MAX_BYTES || it.toByteArray(Charsets.UTF_8).size > MAX_BYTES) tooLarge()
    }

    private class Budget(private val maxNodes: Int) {
        private var nodes = 0
        private var bytes = 0

        fun snapshot(value: JsonElement, depth: Int): JsonElement {
            if (depth > MAX_DEPTH || ++nodes > maxNodes) tooLarge()
            return when (value) {
                is JsonObject -> {
                    if (value.size > MAX_OBJECT_PROPERTIES) tooLarge()
                    consume(2 + (value.size - 1).coerceAtLeast(0))
                    val fields = LinkedHashMap<String, JsonElement>()
                    value.forEach { (key, child) ->
                        if (fields.size >= MAX_OBJECT_PROPERTIES) tooLarge()
                        string(key, MAX_KEY_LENGTH)
                        consume(1)
                        fields[key] = snapshot(child, depth + 1)
                    }
                    JsonObject(fields)
                }
                is JsonArray -> {
                    if (value.size > MAX_ARRAY_ITEMS) tooLarge()
                    consume(2 + (value.size - 1).coerceAtLeast(0))
                    JsonArray(value.mapIndexed { index, child ->
                        if (index >= MAX_ARRAY_ITEMS) tooLarge()
                        snapshot(child, depth + 1)
                    })
                }
                is JsonPrimitive -> {
                    if (value.isString) {
                        string(value.content, MAX_STRING_LENGTH)
                    } else {
                        val literal = value.content
                        if (literal != "null" && literal != "true" && literal != "false") {
                            number(literal)
                        }
                        consume(literal.length)
                    }
                    value
                }
            }
        }

        private fun string(value: String, maxLength: Int) {
            if (value.length > maxLength) tooLarge()
            consume(2)
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                consume(
                    when {
                        char == '"' || char == '\\' ||
                            char == '\b' || char == '\t' || char == '\n' ||
                            char == '\r' || char == '\u000C' -> 2
                        char < ' ' -> 6
                        char < '\u0080' -> 1
                        char < '\u0800' -> 2
                        char.isHighSurrogate() -> {
                            if (index == value.length || !value[index].isLowSurrogate()) invalidJson()
                            index++
                            4
                        }
                        char.isLowSurrogate() -> invalidJson()
                        else -> 3
                    }
                )
            }
        }

        private fun consume(amount: Int) {
            bytes += amount
            if (bytes > MAX_BYTES) tooLarge()
        }
    }

    private fun number(value: String) {
        if (value.length > MAX_NUMBER_LENGTH) unsafeNumber()
        var index = 0
        var digits = 0
        if (value.getOrNull(index) == '-') index++
        when (value.getOrNull(index)) {
            '0' -> {
                index++
                digits++
            }
            in '1'..'9' -> {
                while (value.getOrNull(index) in '0'..'9') {
                    index++
                    digits++
                }
            }
            else -> invalidJson()
        }
        if (value.getOrNull(index) == '.') {
            index++
            val start = index
            while (value.getOrNull(index) in '0'..'9') {
                index++
                digits++
            }
            if (index == start) invalidJson()
        }
        if (digits > MAX_NUMBER_DIGITS) unsafeNumber()
        if (value.getOrNull(index) == 'e' || value.getOrNull(index) == 'E') {
            index++
            if (value.getOrNull(index) == '-' || value.getOrNull(index) == '+') index++
            val start = index
            var exponent = 0
            while (value.getOrNull(index) in '0'..'9') {
                exponent = exponent * 10 + (value[index++] - '0')
                if (exponent > MAX_NUMBER_EXPONENT) unsafeNumber()
            }
            if (index == start) invalidJson()
        }
        if (index != value.length) invalidJson()
    }

    private fun unsafeNumber(): Nothing =
        toolFailure(ToolProblemCode.SCHEMA, "Schema 或工具数据中的数字精度、长度或指数超出安全范围")

    private fun invalidJson(): Nothing =
        toolFailure(ToolProblemCode.SCHEMA, "Schema 或工具数据包含无效的 JSON 字面量或 Unicode")

    private fun tooLarge(): Nothing =
        toolFailure(ToolProblemCode.TOO_LARGE, "Schema 或工具数据的大小、嵌套或节点数量超过安全限制")
}
