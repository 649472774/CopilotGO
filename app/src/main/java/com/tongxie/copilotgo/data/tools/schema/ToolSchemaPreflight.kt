package com.tongxie.copilotgo.data.tools.schema

import com.networknt.schema.SpecificationVersion
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.net.URI

internal enum class ToolSchemaDialect(val uri: String, val version: SpecificationVersion) {
    DRAFT_07("http://json-schema.org/draft-07/schema", SpecificationVersion.DRAFT_7),
    DRAFT_2020_12("https://json-schema.org/draft/2020-12/schema", SpecificationVersion.DRAFT_2020_12);

    companion object {
        fun of(value: JsonElement?): ToolSchemaDialect {
            if (value == null) return DRAFT_2020_12
            val uri = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: invalidToolSchema()
            return entries.firstOrNull { uri == it.uri || uri == "${it.uri}#" }
                ?: schemaFailure(ToolProblemCode.SCHEMA, "工具 Schema 声明了尚未支持的 JSON Schema 版本")
        }
    }
}

internal data class ToolSchemaPreflightResult(
    val dialect: ToolSchemaDialect,
    val normalized: JsonObject,
    val subschemas: List<JsonElement>
)

/**
 * An intentionally restricted profile, not a replacement JSON Schema validator.
 * Only schema-bearing locations are inspected; defaults, examples and constants remain data.
 */
internal class ToolSchemaPreflight(private val definition: JsonObject) {
    private val dialect = ToolSchemaDialect.of(definition["\$schema"])
    private val allowedKeywords = COMMON_KEYWORDS +
        if (dialect == ToolSchemaDialect.DRAFT_07) DRAFT_07_KEYWORDS else DRAFT_2020_KEYWORDS
    private val nodes = LinkedHashMap<String, Node>()

    fun inspect(): ToolSchemaPreflightResult {
        val normalized = visit("#", definition) as JsonObject
        nodes.values.forEach { node ->
            node.reference?.let { reference ->
                val target = pointer(reference)
                if (target !in nodes) invalidReference()
                node.children += target
            }
        }
        expansion("#")
        return ToolSchemaPreflightResult(dialect, normalized, nodes.values.map { it.normalized })
    }

    private fun visit(path: String, value: JsonElement): JsonElement {
        if (nodes.size >= MAX_SUBSCHEMAS) complexity()
        val node = Node()
        nodes[path] = node
        if (value is JsonPrimitive && !value.isString && value.booleanOrNull != null) {
            node.normalized = value
            return value
        }
        val schema = value as? JsonObject ?: invalidToolSchema()
        val fields = LinkedHashMap<String, JsonElement>()
        schema.forEach { (keyword, child) ->
            when {
                keyword in UNSAFE_KEYWORDS ->
                    schemaFailure(ToolProblemCode.SCHEMA, "暂不支持 Schema 正则、格式或内容解码约束，已拒绝以免忽略约束")
                keyword in SCOPED_REFERENCE_KEYWORDS ->
                    schemaFailure(ToolProblemCode.SCHEMA, "暂不支持 Schema 标识作用域、锚点、动态引用或递归引用")
                keyword !in allowedKeywords ->
                    schemaFailure(ToolProblemCode.SCHEMA, "Schema 含有当前规范下不支持的关键字，已拒绝以免忽略约束")
            }
            if (keyword in COUNT_KEYWORDS) checkCount(child)
            val childPath = "$path/${escape(keyword)}"
            fields[keyword] = when (keyword) {
                "\$schema" -> {
                    if (ToolSchemaDialect.of(child) != dialect) {
                        schemaFailure(ToolProblemCode.SCHEMA, "暂不支持在子 Schema 中切换 JSON Schema 版本")
                    }
                    JsonPrimitive(dialect.uri)
                }
                "\$vocabulary" -> {
                    checkVocabulary(child)
                    child
                }
                "\$ref" -> {
                    node.reference = (child as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: invalidToolSchema()
                    pointer(checkNotNull(node.reference))
                    child
                }
                "properties", "\$defs", "definitions", "dependentSchemas" -> {
                    val children = child as? JsonObject ?: invalidToolSchema()
                    JsonObject(children.mapValues { (key, entry) ->
                        subSchema(node, "$childPath/${escape(key)}", entry)
                    })
                }
                "allOf", "anyOf", "oneOf", "prefixItems" ->
                    schemaArray(node, childPath, child)
                "items" -> if (dialect == ToolSchemaDialect.DRAFT_07 && child is JsonArray) {
                    schemaArray(node, childPath, child)
                } else {
                    subSchema(node, childPath, child)
                }
                "additionalProperties", "additionalItems", "propertyNames", "contains", "not",
                "if", "then", "else", "unevaluatedProperties", "unevaluatedItems" ->
                    subSchema(node, childPath, child)
                "dependencies" -> {
                    val children = child as? JsonObject ?: invalidToolSchema()
                    JsonObject(children.mapValues { (key, entry) ->
                        if (entry is JsonArray) entry else subSchema(node, "$childPath/${escape(key)}", entry)
                    })
                }
                "deprecated" -> {
                    // Draft-07 permits this newer annotation but its metaschema does not type-check it.
                    if (child !is JsonPrimitive || child.isString || child.booleanOrNull == null) {
                        invalidToolSchema()
                    }
                    child
                }
                else -> child
            }
        }
        if (dialect == ToolSchemaDialect.DRAFT_07 && node.reference != null &&
            schema.keys.any { it !in DRAFT_07_REFERENCE_SIBLINGS }
        ) {
            schemaFailure(ToolProblemCode.SCHEMA, "Draft-07 引用旁的约束会被规范忽略，请改用 allOf 组合")
        }
        node.normalized = JsonObject(fields)
        return node.normalized
    }

    private fun subSchema(parent: Node, path: String, value: JsonElement): JsonElement {
        parent.children += path
        return visit(path, value)
    }

    private fun schemaArray(parent: Node, path: String, value: JsonElement): JsonArray {
        val array = value as? JsonArray ?: invalidToolSchema()
        return JsonArray(array.mapIndexed { index, child -> subSchema(parent, "$path/$index", child) })
    }

    private fun checkVocabulary(value: JsonElement) {
        val vocabulary = value as? JsonObject ?: invalidToolSchema()
        vocabulary.forEach { (name, flag) ->
            if (name.any { it.code !in 33..126 } || !URI(name).isAbsolute) invalidToolSchema()
            val required = (flag as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: invalidToolSchema()
            if (required && name !in SUPPORTED_VOCABULARIES) {
                schemaFailure(ToolProblemCode.SCHEMA, "Schema 要求当前尚未支持的词汇表，已拒绝以免忽略约束")
            }
        }
    }

    private fun checkCount(value: JsonElement) {
        if (value is JsonPrimitive && !value.isString &&
            value.content != "null" && value.booleanOrNull == null
        ) {
            // The evaluator stores cardinalities in Ints; never allow a valid large integer to wrap.
            val count = BigDecimal(value.content)
            if (count > MAX_COUNT || count < MIN_COUNT) {
                schemaFailure(ToolProblemCode.SCHEMA, "Schema 的长度或数量约束超出安全整数范围")
            }
        }
    }

    private fun pointer(reference: String): String {
        if (reference != "#" && !reference.startsWith("#/")) invalidReference()
        if (reference.drop(1).any { it == '#' || it == '%' || it == '\\' || it <= ' ' }) {
            invalidReference()
        }
        if (reference == "#") return reference
        return "#/" + reference.substring(2).split('/').joinToString("/") { token ->
            val decoded = StringBuilder()
            var index = 0
            while (index < token.length) {
                val char = token[index++]
                if (char != '~') {
                    decoded.append(char)
                } else {
                    decoded.append(
                        when (token.getOrNull(index++)) {
                            '0' -> '~'
                            '1' -> '/'
                            else -> invalidReference()
                        }
                    )
                }
            }
            escape(decoded.toString())
        }
    }

    private fun expansion(path: String, traversalDepth: Int = 1): Expansion {
        if (traversalDepth > BoundedSchemaJson.MAX_DEPTH) complexity()
        val node = checkNotNull(nodes[path])
        if (node.visiting) schemaFailure(ToolProblemCode.SCHEMA, "Schema 含有循环引用，暂不支持")
        node.expansion?.let { return it }
        node.visiting = true
        var work = 1
        var depth = 1
        node.children.forEach { child ->
            val expansion = expansion(child, traversalDepth + 1)
            work += expansion.work
            depth = maxOf(depth, expansion.depth + 1)
            if (work > MAX_EXPANDED_WORK || depth > BoundedSchemaJson.MAX_DEPTH) complexity()
        }
        node.visiting = false
        return Expansion(work, depth).also { node.expansion = it }
    }

    private class Node {
        lateinit var normalized: JsonElement
        val children = mutableListOf<String>()
        var reference: String? = null
        var visiting = false
        var expansion: Expansion? = null
    }

    private data class Expansion(val work: Int, val depth: Int)

    companion object {
        const val MAX_SUBSCHEMAS = 256
        const val MAX_EXPANDED_WORK = 512

        private val MAX_COUNT = BigDecimal(Int.MAX_VALUE)
        private val MIN_COUNT = BigDecimal(Int.MIN_VALUE)
        private val ANNOTATIONS = setOf(
            "title", "description", "default", "examples", "deprecated", "readOnly", "writeOnly",
            "\$comment", "x-mcp-header"
        )
        private val COUNT_KEYWORDS = setOf(
            "minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties",
            "minContains", "maxContains"
        )
        private val COMMON_KEYWORDS = ANNOTATIONS + COUNT_KEYWORDS - setOf("minContains", "maxContains") + setOf(
            "\$schema", "\$ref", "\$defs", "definitions", "type", "enum", "const", "multipleOf",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "uniqueItems", "required",
            "properties", "additionalProperties", "propertyNames", "items", "contains",
            "allOf", "anyOf", "oneOf", "not", "if", "then", "else"
        )
        private val DRAFT_07_KEYWORDS = setOf("additionalItems", "dependencies")
        private val DRAFT_2020_KEYWORDS = setOf(
            "\$vocabulary", "prefixItems", "unevaluatedItems", "unevaluatedProperties",
            "dependentRequired", "dependentSchemas", "minContains", "maxContains"
        )
        private val DRAFT_07_REFERENCE_SIBLINGS = ANNOTATIONS + setOf("\$schema", "\$ref", "\$defs", "definitions")
        private val UNSAFE_KEYWORDS = setOf(
            "pattern", "patternProperties", "format", "contentEncoding", "contentMediaType", "contentSchema"
        )
        private val SCOPED_REFERENCE_KEYWORDS = setOf(
            "\$id", "id", "\$anchor", "\$dynamicAnchor", "\$dynamicRef", "\$recursiveAnchor", "\$recursiveRef"
        )
        private val SUPPORTED_VOCABULARIES = setOf(
            "https://json-schema.org/draft/2020-12/vocab/core",
            "https://json-schema.org/draft/2020-12/vocab/applicator",
            "https://json-schema.org/draft/2020-12/vocab/validation",
            "https://json-schema.org/draft/2020-12/vocab/unevaluated",
            "https://json-schema.org/draft/2020-12/vocab/meta-data"
        )

        private fun escape(value: String): String = value.replace("~", "~0").replace("/", "~1")

        private fun invalidReference(): Nothing =
            schemaFailure(
                ToolProblemCode.SCHEMA,
                "Schema 引用必须是可解析的本地 JSON Pointer；暂不支持外部资源、锚点或百分号编码引用"
            )

        private fun complexity(): Nothing =
            schemaFailure(ToolProblemCode.SCHEMA, "Schema 子结构或引用展开的深度、计算量超过安全限制")
    }
}

internal fun invalidToolSchema(): Nothing =
    schemaFailure(ToolProblemCode.SCHEMA, "工具 Schema 不符合所声明的 JSON Schema 规范")
