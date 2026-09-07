package com.tongxie.copilotgo.data.tools.schema

import com.tongxie.copilotgo.data.tools.ToolException
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolSchemaSafetyTest {
    @Test
    fun rejectsAllExternalResourceSchemesBeforeEvaluatingEvenUnreachableBranches() {
        val references = listOf(
            "https://example.invalid/schema.json",
            "http://127.0.0.1/schema.json",
            "file:///fixture-does-not-exist.json",
            "classpath:draft/2020-12/schema",
            "jar:file:///fixture.jar!/schema.json",
            "data:application/json,{}",
            "urn:fixture:external",
            "../other-schema.json",
            "/schema.json",
            "//example.invalid/schema.json",
            "https://json-schema.org/draft/2020-12/schema"
        )
        ToolSchemaDialect.entries.forEach { dialect ->
            references.forEach { reference ->
                val ref = JsonObject(mapOf("\$ref" to JsonPrimitive(reference)))
                val base = mapOf("\$schema" to JsonPrimitive(dialect.uri))
                listOf(
                    base + ref,
                    base + ("anyOf" to JsonArray(listOf(JsonPrimitive(true), ref))),
                    base + ("definitions" to JsonObject(mapOf("unused" to ref))),
                    base + ("if" to JsonPrimitive(false)) + ("then" to ref),
                    base + ("if" to JsonPrimitive(true)) + ("else" to ref)
                ).forEach { fields ->
                    val error = assertProblem { ToolSchemaCompiler.compile(JsonObject(fields)) }
                    assertTrue(error.message.orEmpty().contains("本地 JSON Pointer"))
                    assertFalse(error.toString().contains(reference))
                }
            }
        }
    }

    @Test
    fun checksEveryLocalReferenceAndRejectsLiteralOrNonSchemaTargets() {
        listOf(
            """{"anyOf":[true,{"${'$'}ref":"#/${'$'}defs/missing"}]}""",
            """{"${'$'}defs":{"unused":{"${'$'}ref":"#/${'$'}defs/missing"}}}""",
            """{"default":{"pattern":"(a+)+$"},"${'$'}ref":"#/default"}""",
            """{"examples":[{"type":"integer"}],"${'$'}ref":"#/examples/0"}""",
            """{"const":{"type":"integer"},"${'$'}ref":"#/const"}""",
            """{"enum":[{"type":"integer"}],"${'$'}ref":"#/enum/0"}""",
            """{"properties":{"value":{"type":"integer"}},"${'$'}ref":"#/properties"}""",
            """{"${'$'}ref":"#/type","type":"object"}""",
            """{"allOf":[true],"${'$'}ref":"#/allOf/01"}""",
            """{"allOf":[true],"${'$'}ref":"#/allOf/-"}""",
            """{"${'$'}ref":"#/missing~2name"}""",
            """{"${'$'}ref":"#/missing~"}""",
            """{"${'$'}ref":"#anchor"}""",
            """{"${'$'}ref":"#/%24defs/value","${'$'}defs":{"value":true}}""",
            """{"${'$'}ref":"#/properties/a b","properties":{"a b":true}}""",
            """{"${'$'}ref":false}"""
        ).forEach { assertProblem { compile(it) } }
    }

    @Test
    fun rejectsCyclesIncludingUnusedAndMutuallyRecursiveDefinitions() {
        listOf(
            """{"${'$'}ref":"#"}""",
            """{"properties":{"child":{"${'$'}ref":"#"}}}""",
            """{"${'$'}defs":{"unused":{"${'$'}ref":"#/${'$'}defs/unused"}}}""",
            """{"${'$'}defs":{"a":{"${'$'}ref":"#/${'$'}defs/b"},"b":{"${'$'}ref":"#/${'$'}defs/a"}}}""",
            """{"${'$'}defs":{"unused":{"properties":{"child":{"${'$'}ref":"#/${'$'}defs/unused"}}}}}"""
        ).forEach { assertProblem { compile(it) } }
    }

    @Test
    fun boundsExpandedDagWorkRatherThanOnlyTheSmallSourceDocument() {
        val small = ToolSchemaCompiler.compile(sharedReferenceDag(3))
        small.validate(json("1"))
        assertProblem { small.validate(json(""""one"""")) }
        val expanded = sharedReferenceDag(9)
        assertTrue(expanded.toString().toByteArray(Charsets.UTF_8).size < 4 * 1024)
        val error = assertProblem { ToolSchemaCompiler.compile(expanded) }
        assertTrue(error.message.orEmpty().contains("计算量"))
    }

    @Test
    fun boundsLongReferenceChainsBeforeLibraryRecursion() {
        val definitions = linkedMapOf<String, JsonElement>("n0" to JsonObject(emptyMap()))
        repeat(BoundedSchemaJson.MAX_DEPTH + 1) { index ->
            definitions["n${index + 1}"] = schema("""{"${'$'}ref":"#/${'$'}defs/n$index"}""")
        }
        assertProblem {
            ToolSchemaCompiler.compile(JsonObject(mapOf("\$defs" to JsonObject(definitions))))
        }
    }

    @Test
    fun rejectsRegexFormatAndContentConstraintsInBothDialects() {
        val unsafe = mapOf(
            "pattern" to JsonPrimitive("(a+)+$"),
            "patternProperties" to schema("""{"(a+)+$":{"type":"string"}}"""),
            "format" to JsonPrimitive("email"),
            "contentEncoding" to JsonPrimitive("base64"),
            "contentMediaType" to JsonPrimitive("application/json"),
            "contentSchema" to schema("""{"type":"string"}""")
        )
        ToolSchemaDialect.entries.forEach { dialect ->
            unsafe.forEach { (keyword, constraint) ->
                val branch = JsonObject(mapOf(keyword to constraint))
                listOf(
                    mapOf("\$schema" to JsonPrimitive(dialect.uri), keyword to constraint),
                    mapOf(
                        "\$schema" to JsonPrimitive(dialect.uri),
                        "anyOf" to JsonArray(listOf(JsonPrimitive(true), branch))
                    )
                ).forEach { fields ->
                    val error = assertProblem { ToolSchemaCompiler.compile(JsonObject(fields)) }
                    assertTrue(error.message.orEmpty().contains("正则、格式或内容解码"))
                }
            }
        }
    }

    @Test
    fun rejectsScopeAnchorsDynamicAndRecursiveFeaturesAtEverySchemaLocation() {
        mapOf(
            "\$id" to JsonPrimitive("https://example.invalid/fixture-scope"),
            "id" to JsonPrimitive("legacy-scope"),
            "\$anchor" to JsonPrimitive("target"),
            "\$dynamicAnchor" to JsonPrimitive("target"),
            "\$dynamicRef" to JsonPrimitive("#target"),
            "\$recursiveAnchor" to JsonPrimitive(true),
            "\$recursiveRef" to JsonPrimitive("#")
        ).forEach { (keyword, value) ->
            val unsupported = JsonObject(mapOf(keyword to value))
            assertProblem { ToolSchemaCompiler.compile(unsupported) }
            assertProblem {
                ToolSchemaCompiler.compile(
                    JsonObject(mapOf("\$defs" to JsonObject(mapOf("unused" to unsupported))))
                )
            }
        }
    }

    @Test
    fun boundsSchemaBytesValueNodesAndDepthBeforeSerializationOrLibraryLoading() {
        assertProblem(ToolProblemCode.TOO_LARGE) {
            ToolSchemaCompiler.compile(JsonObject(mapOf("description" to JsonPrimitive("汉".repeat(12_000)))))
        }
        ToolSchemaCompiler.compile(
            JsonObject(mapOf("default" to JsonArray(List(254) { JsonPrimitive(0) })))
        ).validate(json("null"))
        assertProblem(ToolProblemCode.TOO_LARGE) {
            ToolSchemaCompiler.compile(
                JsonObject(mapOf("default" to JsonArray(List(255) { JsonPrimitive(0) })))
            )
        }
        var nested: JsonObject = JsonObject(emptyMap())
        repeat(BoundedSchemaJson.MAX_DEPTH - 1) { nested = JsonObject(mapOf("not" to nested)) }
        ToolSchemaCompiler.compile(nested)
        assertProblem(ToolProblemCode.TOO_LARGE) {
            ToolSchemaCompiler.compile(JsonObject(mapOf("not" to nested)))
        }
    }

    @Test
    fun boundsSchemaObjectAndArrayCardinalityEvenInAnnotations() {
        assertProblem(ToolProblemCode.TOO_LARGE) {
            ToolSchemaCompiler.compile(JsonObject((0..128).associate { "field$it" to JsonPrimitive(true) }))
        }
        assertProblem(ToolProblemCode.TOO_LARGE) {
            ToolSchemaCompiler.compile(
                JsonObject(mapOf("default" to JsonArray(List(257) { JsonPrimitive(true) })))
            )
        }
    }

    @Test
    fun boundsInstanceUtf8AndEscapedJsonBytesWithTheSameLimitForOutputs() {
        val validator = compile("{}")
        validator.validate(JsonPrimitive("a".repeat(BoundedSchemaJson.MAX_STRING_LENGTH)))
        validator.validate(JsonPrimitive("汉".repeat(8_000)))
        validator.validate(JsonPrimitive("\u0000".repeat(5_461)))
        listOf(
            JsonPrimitive("a".repeat(BoundedSchemaJson.MAX_STRING_LENGTH + 1)),
            JsonPrimitive("汉".repeat(12_000)),
            JsonPrimitive("\u0000".repeat(5_462)),
            JsonPrimitive("\\".repeat(16_384)),
            schema("""{"a":"${"a".repeat(16_384)}","b":"${"b".repeat(16_384)}"}""")
        ).forEach { value -> assertProblem(ToolProblemCode.TOO_LARGE) { validator.validate(value) } }
    }

    @Test
    fun boundsInstanceValueNodesAndNestingIncludingUnconstrainedData() {
        val validator = compile("{}")
        val atLimit = JsonArray(List(256) { index ->
            JsonObject((0 until if (index == 255) 6 else 7).associate { "k$it" to JsonPrimitive(0) })
        })
        validator.validate(atLimit)
        val overLimit = JsonArray(List(256) {
            JsonObject((0 until 7).associate { "k$it" to JsonPrimitive(0) })
        })
        assertProblem(ToolProblemCode.TOO_LARGE) { validator.validate(overLimit) }
        var nested: JsonElement = JsonPrimitive(0)
        repeat(BoundedSchemaJson.MAX_DEPTH - 1) { nested = JsonArray(listOf(nested)) }
        validator.validate(nested)
        assertProblem(ToolProblemCode.TOO_LARGE) { validator.validate(JsonArray(listOf(nested))) }
    }

    @Test
    fun boundsInstanceCollectionsAndPropertyNameLengths() {
        val validator = compile("{}")
        validator.validate(JsonArray(List(256) { JsonPrimitive(0) }))
        validator.validate(JsonObject((0 until 128).associate { "k$it" to JsonPrimitive(0) }))
        assertProblem(ToolProblemCode.TOO_LARGE) {
            validator.validate(JsonArray(List(257) { JsonPrimitive(0) }))
        }
        assertProblem(ToolProblemCode.TOO_LARGE) {
            validator.validate(JsonObject((0 until 129).associate { "k$it" to JsonPrimitive(0) }))
        }
        assertProblem(ToolProblemCode.TOO_LARGE) {
            validator.validate(JsonObject(mapOf("k".repeat(257) to JsonPrimitive(0))))
        }
    }

    @Test
    fun refusesCyclicCallerOwnedJsonTreesBeforeCallingToString() {
        val fields = mutableMapOf<String, JsonElement>()
        val cyclic = JsonObject(fields)
        fields["self"] = cyclic
        assertProblem(ToolProblemCode.TOO_LARGE) { compile("{}").validate(cyclic) }
        assertProblem(ToolProblemCode.TOO_LARGE) { ToolSchemaCompiler.compile(cyclic) }
    }

    @Test
    fun rejectsUnsafeNumericExponentsPrecisionAndNonJsonNumbersBeforeEvaluation() {
        val validator = compile("""{"type":"number"}""")
        listOf(
            "1e2147483647", "1e-2147483647", "0e2147483647", "0e-2147483647",
            "1e129", "1e-129", "1e99999999999999999999999", "9".repeat(65),
            "1e" + "0".repeat(128)
        ).forEach { number ->
            assertProblem { validator.validate(json(number)) }
            assertProblem { compile("""{"maximum":$number}""") }
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { number ->
            assertProblem { validator.validate(JsonPrimitive(number)) }
            assertProblem {
                ToolSchemaCompiler.compile(JsonObject(mapOf("minimum" to JsonPrimitive(number))))
            }
        }
        listOf("1e128", "1e-128", "-0", "9".repeat(64), "0.0001").forEach {
            validator.validate(json(it))
        }
        compile("""{"type":"string","default":"1e2147483647"}""").validate(JsonPrimitive("1e2147483647"))
    }

    @Test
    fun preservesExactIntegerAndFractionalMultipleOfConstraints() {
        val integer = compile("""{"type":"integer","multipleOf":9007199254740993}""")
        integer.validate(json("9007199254740993"))
        integer.validate(json("18014398509481986"))
        listOf("9007199254740992", "18014398509481987", "0.5").forEach {
            assertProblem { integer.validate(json(it)) }
        }
        val fractional = compile("""{"type":"number","multipleOf":0.1,"minimum":0,"maximum":1}""")
        fractional.validate(json("0.3"))
        fractional.validate(json("1"))
        assertProblem { fractional.validate(json("0.3000000000000000000000000000000000001")) }
        val exact = compile("""{"type":"integer","minimum":9007199254740993,"maximum":9007199254740993}""")
        exact.validate(json("9007199254740993.0"))
        assertProblem { exact.validate(json("9007199254740992")) }
        assertProblem { exact.validate(json("9007199254740994")) }
        val exclusive = compile("""{"exclusiveMinimum":1,"exclusiveMaximum":2}""")
        exclusive.validate(json("1.5"))
        assertProblem { exclusive.validate(json("1")) }
        assertProblem { exclusive.validate(json("2")) }
    }

    @Test
    fun rejectsCardinalityKeywordsThatWouldOverflowEvaluatorIntegers() {
        listOf(
            "minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties",
            "minContains", "maxContains"
        ).forEach { keyword ->
            assertProblem { compile("""{"$keyword":2147483648}""") }
            assertProblem { compile("""{"$keyword":1e128}""") }
        }
        compile("""{"type":"array","maxItems":2147483647}""").validate(json("[]"))
    }

    @Test
    fun metaschemaRejectsFractionalCardinalitiesEvenWhenDoubleWouldRoundThemToIntegers() {
        ToolSchemaDialect.entries.forEach { dialect ->
            listOf("minLength", "maxItems", "minProperties").forEach { keyword ->
                assertProblem {
                    compile("""{"${'$'}schema":"${dialect.uri}","$keyword":1.00000000000000000000000001}""")
                }
            }
        }
    }

    @Test
    fun rejectsBrokenUnicodeWithoutLeakingItsContents() {
        val validator = compile("{}")
        listOf("\uD800", "\uDC00", "\uD800x").forEach { invalid ->
            assertProblem { validator.validate(JsonPrimitive(invalid)) }
            assertProblem {
                ToolSchemaCompiler.compile(JsonObject(mapOf("description" to JsonPrimitive(invalid))))
            }
        }
    }

    @Test
    fun keepsSchemaConstantsArgumentsAndLoaderDetailsOutOfAllDiagnosticChains() {
        val schemaSecret = "fixture-schema-secret-7dbfe8"
        val argumentSecret = "fixture-argument-secret-88c920"
        val errors = listOf(
            assertProblem { compile("""{"const":"$schemaSecret","minLength":"$argumentSecret"}""") },
            assertProblem { compile("""{"const":"$schemaSecret"}""").validate(JsonPrimitive(argumentSecret)) },
            assertProblem { compile("""{"${'$'}ref":"file:///$schemaSecret/$argumentSecret"}""") },
            assertProblem { compile("""{"$schemaSecret":"$argumentSecret"}""") },
            assertProblem {
                compile("""{"${'$'}schema":"https://example.invalid/$schemaSecret/$argumentSecret"}""")
            }
        )
        errors.forEach { error ->
            val rendered = error.stackTraceToString() + error.problem.toString()
            assertFalse(rendered.contains(schemaSecret))
            assertFalse(rendered.contains(argumentSecret))
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertTrue(error.problem.message.any { it in '\u4E00'..'\u9FFF' })
        }
    }

    private fun sharedReferenceDag(levels: Int): JsonObject {
        val definitions = linkedMapOf<String, JsonElement>("n0" to schema("""{"type":"integer"}"""))
        repeat(levels) { index ->
            val ref = schema("""{"${'$'}ref":"#/${'$'}defs/n$index"}""")
            definitions["n${index + 1}"] = JsonObject(mapOf("allOf" to JsonArray(listOf(ref, ref))))
        }
        return JsonObject(
            mapOf("\$defs" to JsonObject(definitions), "\$ref" to JsonPrimitive("#/\$defs/n$levels"))
        )
    }

    private fun compile(text: String): ValidatedToolSchema = ToolSchemaCompiler.compile(schema(text))
    private fun schema(text: String): JsonObject = json(text) as JsonObject
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun assertProblem(
        code: ToolProblemCode = ToolProblemCode.SCHEMA,
        action: () -> Unit
    ): ToolException {
        try {
            action()
            fail("Expected a bounded, safe rejection")
        } catch (error: ToolException) {
            assertEquals(code, error.problem.code)
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            return error
        }
        error("unreachable")
    }
}
