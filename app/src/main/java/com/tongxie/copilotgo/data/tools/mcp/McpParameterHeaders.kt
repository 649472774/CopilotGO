package com.tongxie.copilotgo.data.tools.mcp

import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.toolFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64

internal class McpParameterHeaders private constructor(private val bindings: List<Binding>) {
    fun forArguments(arguments: JsonObject): Map<String, String> {
        var totalBytes = 0
        return buildMap {
            bindings.forEach { binding ->
                var value: JsonElement? = arguments
                binding.path.forEach { key -> value = (value as? JsonObject)?.get(key) }
                if (value == null || value == JsonNull) return@forEach
                val primitive = value as? JsonPrimitive
                    ?: toolFailure(ToolProblemCode.SCHEMA, "MCP 请求头参数必须是标量")
                val text = when (binding.type) {
                    "string" -> primitive.takeIf { it.isString }?.content
                    "boolean" -> primitive.booleanOrNull?.takeUnless { primitive.isString }?.toString()
                    "integer" -> safeInteger(primitive)
                    else -> null
                } ?: toolFailure(ToolProblemCode.SCHEMA, "MCP 请求头参数类型与工具定义不一致")
                val encoded = encode(text)
                totalBytes += encoded.length + binding.name.length + 12
                if (totalBytes > MAX_HEADER_BYTES) {
                    toolFailure(ToolProblemCode.TOO_LARGE, "MCP 参数请求头超过大小限制")
                }
                put("Mcp-Param-${binding.name}", encoded)
            }
        }
    }

    private data class Binding(val path: List<String>, val name: String, val type: String)

    companion object {
        private const val MAX_HEADER_BYTES = 8192
        private const val MAX_BINDINGS = 32
        private val HEADER_TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val MAX_SAFE_INTEGER = BigInteger("9007199254740991")
        private val SCHEMA_VALUES = setOf(
            "items", "additionalItems", "additionalProperties", "unevaluatedItems", "unevaluatedProperties",
            "contains", "propertyNames", "not", "if", "then", "else"
        )
        private val SCHEMA_ARRAYS = setOf("allOf", "anyOf", "oneOf", "prefixItems")
        private val SCHEMA_MAPS = setOf("\$defs", "definitions", "patternProperties", "dependentSchemas", "dependencies")

        fun compile(schema: JsonObject): McpParameterHeaders {
            val bindings = mutableListOf<Binding>()
            val names = mutableSetOf<String>()
            fun visit(element: JsonElement, path: List<String>?) {
                if (element is JsonArray) {
                    element.forEach { visit(it, null) }
                    return
                }
                if (element !is JsonObject) return
                element["x-mcp-header"]?.let { annotation ->
                    val name = (annotation as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val type = (element["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (path.isNullOrEmpty() || "\$ref" in element ||
                        name == null || name.length !in 1..64 || !name.matches(HEADER_TOKEN) ||
                        !names.add(name.lowercase()) || type !in setOf("string", "boolean", "integer")
                    ) {
                        toolFailure(ToolProblemCode.SCHEMA, "工具的 x-mcp-header 定义无效或不是静态可达标量属性")
                    }
                    if (bindings.size >= MAX_BINDINGS) {
                        toolFailure(ToolProblemCode.SCHEMA, "工具定义的 MCP 参数请求头过多")
                    }
                    bindings += Binding(path, name, checkNotNull(type))
                }
                (element["properties"] as? JsonObject)?.forEach { (name, child) ->
                    visit(child, path?.plus(name))
                }
                SCHEMA_VALUES.forEach { key -> element[key]?.let { visit(it, null) } }
                SCHEMA_ARRAYS.forEach { key -> (element[key] as? JsonArray)?.forEach { visit(it, null) } }
                SCHEMA_MAPS.forEach { key ->
                    (element[key] as? JsonObject)?.values?.forEach { visit(it, null) }
                }
            }
            visit(schema, emptyList())
            return McpParameterHeaders(bindings)
        }

        fun encode(value: String): String {
            if (value.length > MAX_HEADER_BYTES) {
                toolFailure(ToolProblemCode.TOO_LARGE, "MCP 请求头值超过大小限制")
            }
            val safeAscii = value.all { it == '\t' || it.code in 0x20..0x7e }
            val edgeWhitespace = value.firstOrNull()?.isWhitespace() == true ||
                value.lastOrNull()?.isWhitespace() == true
            val sentinel = value.startsWith("=?base64?") && value.endsWith("?=")
            return if (safeAscii && !edgeWhitespace && !sentinel) value else {
                val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
                if (encoded.length > MAX_HEADER_BYTES) {
                    toolFailure(ToolProblemCode.TOO_LARGE, "MCP 编码后的请求头值超过大小限制")
                }
                "=?base64?$encoded?="
            }
        }

        private fun safeInteger(value: JsonPrimitive): String? {
            if (value.isString || value.content.length > 128) return null
            val integer = try {
                val decimal = BigDecimal(value.content)
                if (decimal.scale() !in -128..128 ||
                    decimal.precision().toLong() - decimal.scale() > 16
                ) return null
                decimal.toBigIntegerExact()
            } catch (_: NumberFormatException) {
                return null
            } catch (_: ArithmeticException) {
                return null
            }
            return integer.takeIf { it.abs() <= MAX_SAFE_INTEGER }?.toString()
        }
    }
}
