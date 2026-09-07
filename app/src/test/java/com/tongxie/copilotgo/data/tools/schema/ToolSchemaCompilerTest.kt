package com.tongxie.copilotgo.data.tools.schema

import com.tongxie.copilotgo.data.tools.ToolException
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolSchemaCompilerTest {
    @Test
    fun enforcesTheRealExaDraft07Profile() {
        val definition = schema("""
            {"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object",
             "properties":{"query":{"type":"string","minLength":1},"numResults":{"type":"number"}},
             "required":["query"],"additionalProperties":false}
        """)
        val validator = ToolSchemaCompiler.compile(definition)
        assertEquals(definition, validator.definition)
        validator.validate(json("""{"query":"Android 文档","numResults":4}"""))
        validator.validate(json("""{"query":"q","numResults":0.5}"""))
        listOf(
            """{}""", """{"query":""}""", """{"query":42}""",
            """{"query":"q","extra":true}""", """{"query":"q","numResults":"4"}""", """[]"""
        ).forEach { value -> assertProblem { validator.validate(json(value)) } }
    }

    @Test
    fun acceptsOnlyOfficialDialectsWithAnOptionalEmptyFragment() {
        listOf(
            "http://json-schema.org/draft-07/schema",
            "http://json-schema.org/draft-07/schema#",
            "https://json-schema.org/draft/2020-12/schema",
            "https://json-schema.org/draft/2020-12/schema#"
        ).forEach { dialect ->
            val validator = compile("""{"${'$'}schema":"$dialect","type":"string","minLength":1}""")
            validator.validate(json(""""ok""""))
            assertProblem { validator.validate(json("\"\"")) }
        }
    }

    @Test
    fun rejectsUnknownDialectsAndMixedNestedDialects() {
        listOf(
            "https://example.invalid/private-schema",
            "http://json-schema.org/draft-04/schema#",
            "https://json-schema.org/draft/2019-09/schema",
            "https://json-schema.org/draft-07/schema#",
            "http://json-schema.org/draft/2020-12/schema",
            "https://json-schema.org/draft/2020-12/schema##",
            "https://json-schema.org/draft/2020-12/schema?token=fixture-secret",
            "https://example.invalid/?schema=http://json-schema.org/draft-07/schema#"
        ).forEach { dialect ->
            assertProblem { compile("""{"${'$'}schema":"$dialect"}""") }
        }
        assertProblem { compile("""{"${'$'}schema":true}""") }
        assertProblem { compile("""{"${'$'}schema":null}""") }
        assertProblem {
            compile("""
                {"properties":{"nested":{"${'$'}schema":"http://json-schema.org/draft-07/schema#"}}}
            """)
        }
        compile("""
            {"properties":{"nested":{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema#",
              "type":"integer"}}}
        """).validate(json("""{"nested":1}"""))
    }

    @Test
    fun metaschemaValidationRejectsMalformedKeywordValuesInBothDialects() {
        val invalid = listOf(
            """"type":"strnig"""", """"type":false""", """"type":[]""",
            """"type":["string","string"]""", """"required":"query"""",
            """"required":[42]""", """"required":["query","query"]""",
            """"minLength":"1"""", """"minLength":-1""", """"maxItems":1.5""",
            """"minProperties":null""", """"uniqueItems":"true"""",
            """"exclusiveMinimum":true""", """"exclusiveMaximum":"0"""",
            """"multipleOf":0""", """"multipleOf":-1""", """"enum":"value"""",
            """"allOf":[]""", """"anyOf":[]""", """"oneOf":[]""",
            """"properties":[]""", """"properties":{"query":null}""",
            """"items":"string"""", """"additionalProperties":1""",
            """"propertyNames":null""", """"not":3""", """"if":"object"""",
            """"then":null""", """"else":[]""", """"title":42""",
            """"description":false""", """"examples":{}""",
            """"readOnly":"true"""", """"writeOnly":0""", """"deprecated":"false"""",
            """"${'$'}comment":1"""
        )
        ToolSchemaDialect.entries.forEach { dialect ->
            invalid.forEach { fields ->
                assertProblem { compile("""{"${'$'}schema":"${dialect.uri}",$fields}""") }
            }
        }
    }

    @Test
    fun metaschemaChecksUnusedAndCompatibilityDefinitionSubschemas() {
        listOf("definitions", "${'$'}defs").forEach { keyword ->
            ToolSchemaDialect.entries.forEach { dialect ->
                assertProblem {
                    compile("""
                        {"${'$'}schema":"${dialect.uri}","$keyword":{"unused":{"minLength":"invalid"}}}
                    """)
                }
            }
        }
        assertProblem { compile("""{"anyOf":[true,{"maxLength":-1}]}""") }
        assertProblem { compile("""{"if":false,"then":{"required":[3]}}""") }
        assertProblem {
            compile("""
                {"${'$'}schema":"http://json-schema.org/draft-07/schema#",
                 "definitions":{"target":true},"${'$'}ref":"#/definitions/target",
                 "examples":false}
            """)
        }
    }

    @Test
    fun defaultsTo2020AndEnforcesPrefixItemsItemsAndUnevaluatedItems() {
        val tuple = compile("""
            {"type":"array","prefixItems":[{"type":"string"},{"type":"integer"}],
             "items":false,"minItems":2}
        """)
        tuple.validate(json("""["ok",1]"""))
        listOf("""[]""", """["ok"]""", """[1,"ok"]""", """["ok",1,true]""").forEach {
            assertProblem { tuple.validate(json(it)) }
        }
        val unevaluated = compile("""
            {"type":"array","allOf":[{"prefixItems":[{"const":"ok"}]}],"unevaluatedItems":false}
        """)
        unevaluated.validate(json("""["ok"]"""))
        assertProblem { unevaluated.validate(json("""["ok",1]""")) }
    }

    @Test
    fun enforcesUnevaluatedPropertiesAcrossReferencesAndComposition() {
        val validator = compile("""
            {"type":"object","${'$'}defs":{"base":{"properties":{"name":{"type":"string"}}}},
             "allOf":[{"${'$'}ref":"#/${'$'}defs/base"},{"properties":{"age":{"type":"integer"}}}],
             "required":["name"],"unevaluatedProperties":false}
        """)
        validator.validate(json("""{"name":"Ada","age":42}"""))
        validator.validate(json("""{"name":"Ada"}"""))
        assertProblem { validator.validate(json("""{"name":"Ada","extra":true}""")) }
        assertProblem { validator.validate(json("""{"name":"Ada","age":"42"}""")) }
        val conditional = compile("""
            {"type":"object","anyOf":[{"properties":{"a":{"type":"integer"}},"required":["a"]},
                                     {"properties":{"b":{"type":"integer"}},"required":["b"]}],
             "unevaluatedProperties":false}
        """)
        conditional.validate(json("""{"a":1,"b":2}"""))
        assertProblem { conditional.validate(json("""{"a":1,"b":"wrong"}""")) }
    }

    @Test
    fun enforcesDependentRequiredDependentSchemasAndConditionals() {
        val validator = compile("""
            {"type":"object",
             "properties":{"kind":{"enum":["basic","advanced"]},"detail":{"type":"string","minLength":1},
                           "payment":{"type":"string"},"billing":{"type":"string"},"count":{"type":"integer"}},
             "required":["kind"],"dependentRequired":{"payment":["billing"]},
             "dependentSchemas":{"count":{"properties":{"count":{"minimum":1}}}},
             "if":{"properties":{"kind":{"const":"advanced"}},"required":["kind"]},
             "then":{"required":["detail"]},"else":{"not":{"required":["detail"]}},
             "additionalProperties":false}
        """)
        validator.validate(json("""{"kind":"advanced","detail":"yes","payment":"p","billing":"b","count":1}"""))
        validator.validate(json("""{"kind":"basic"}"""))
        listOf(
            """{"kind":"advanced"}""", """{"kind":"advanced","detail":""}""",
            """{"kind":"basic","detail":"no"}""", """{"kind":"basic","payment":"p"}""",
            """{"kind":"basic","count":0}"""
        ).forEach { assertProblem { validator.validate(json(it)) } }
        listOf(
            """"dependentRequired":{"x":true}""", """"dependentRequired":{"x":[1]}""",
            """"dependentRequired":{"x":["y","y"]}""", """"dependentSchemas":{"x":null}""",
            """"prefixItems":[]""", """"prefixItems":{}""", """"minContains":-1""",
            """"maxContains":1.5"""
        ).forEach { assertProblem { compile("{$it}") } }
    }

    @Test
    fun enforcesDraft07TupleDependenciesAndConditionals() {
        val validator = compile("""
            {"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object",
             "properties":{"tuple":{"type":"array","items":[{"type":"string"},{"type":"integer"}],
                                   "additionalItems":false,"minItems":2},
                           "a":{"type":"integer"},"b":{"type":"integer"},"switch":{"type":"boolean"}},
             "dependencies":{"a":["b"],"b":{"properties":{"b":{"minimum":1}}}},
             "if":{"properties":{"switch":{"const":true}},"required":["switch"]},
             "then":{"required":["tuple"]},"else":{"not":{"required":["tuple"]}}}
        """)
        validator.validate(json("""{"switch":true,"tuple":["ok",1],"a":1,"b":2}"""))
        validator.validate(json("""{"switch":false,"b":2}"""))
        listOf(
            """{"switch":true}""", """{"switch":true,"tuple":["ok",1,2]}""",
            """{"switch":true,"tuple":[1,"ok"]}""", """{"switch":false,"tuple":["ok",1]}""",
            """{"a":1}""", """{"b":0}"""
        ).forEach { assertProblem { validator.validate(json(it)) } }
        assertProblem {
            compile("""{"${'$'}schema":"http://json-schema.org/draft-07/schema#","dependencies":{"a":[1]}}""")
        }
    }

    @Test
    fun enforcesCompositionBooleanSchemasAndModernReferenceSiblings() {
        val validator = compile("""
            {"${'$'}defs":{"number":{"type":"number"}},"${'$'}ref":"#/${'$'}defs/number",
             "allOf":[{"minimum":1},{"maximum":10}],"not":{"const":5},
             "anyOf":[{"multipleOf":2},{"multipleOf":3}]}
        """)
        listOf("2", "3", "6", "10").forEach { validator.validate(json(it)) }
        listOf("0", "5", "7", "11", """"2"""").forEach { assertProblem { validator.validate(json(it)) } }
        val oneOf = compile("""{"oneOf":[{"type":"integer"},{"minimum":0,"type":"number"}]}""")
        oneOf.validate(json("-1"))
        oneOf.validate(json("0.5"))
        assertProblem { oneOf.validate(json("1")) }
        compile("""{"allOf":[true]}""").validate(json("null"))
        assertProblem { compile("""{"allOf":[false]}""").validate(json("null")) }
        assertProblem {
            compile("""
                {"${'$'}schema":"http://json-schema.org/draft-07/schema#",
                 "definitions":{"number":{"type":"number"}},"${'$'}ref":"#/definitions/number","minimum":1}
            """)
        }
    }

    @Test
    fun supportsAcyclicLocalPointersIncludingEscapedPropertyNames() {
        val validator = compile("""
            {"${'$'}defs":{"a/b~c":{"type":"string","minLength":2}},
             "type":"object","properties":{"first":{"${'$'}ref":"#/${'$'}defs/a~1b~0c"},
                                           "second":{"${'$'}ref":"#/properties/first"}}}
        """)
        validator.validate(json("""{"first":"ok","second":"yes"}"""))
        assertProblem { validator.validate(json("""{"second":"x"}""")) }
        assertProblem { validator.validate(json("""{"first":2}""")) }
        compile("""
            {"${'$'}schema":"http://json-schema.org/draft-07/schema#",
             "${'$'}defs":{"value":{"type":"integer"}},"${'$'}ref":"#/${'$'}defs/value"}
        """).validate(json("1"))
    }

    @Test
    fun enforcesObjectNamesSizeEnumAndUnicodeStringLengths() {
        val validator = compile("""
            {"type":"object","minProperties":1,"maxProperties":2,"propertyNames":{"maxLength":4},
             "additionalProperties":{"type":["string","null"],"minLength":1,"maxLength":2}}
        """)
        validator.validate(json("""{"a":"😀","b":null}"""))
        listOf("""{}""", """{"large":"x"}""", """{"a":""}""", """{"a":"abc"}""", """{"a":"x","b":"y","c":"z"}""")
            .forEach { assertProblem { validator.validate(json(it)) } }
        val enumeration = compile("""{"enum":[{"enabled":true},null,1]}""")
        enumeration.validate(json("""{"enabled":true}"""))
        enumeration.validate(json("null"))
        enumeration.validate(json("1.0"))
        assertProblem { enumeration.validate(json("""{"enabled":false}""")) }
        compile("""{"type":"string","maxLength":1}""").validate(JsonPrimitive("😀"))
        assertProblem { compile("""{"type":"string","minLength":2}""").validate(JsonPrimitive("😀")) }
    }

    @Test
    fun usesTheChosenDraftsActualEnumMetaschemaRules() {
        val never = compile("""{"enum":[]}""")
        assertProblem { never.validate(json("null")) }
        compile("""{"enum":[1,1.0]}""").validate(json("1"))
        assertProblem {
            compile("""{"${'$'}schema":"http://json-schema.org/draft-07/schema#","enum":[]}""")
        }
        assertProblem {
            compile("""{"${'$'}schema":"http://json-schema.org/draft-07/schema#","enum":[1,1.0]}""")
        }
    }

    @Test
    fun enforcesContainsAndUniqueItemsWithoutCoercion() {
        val validator = compile("""
            {"type":"array","minItems":2,"maxItems":5,"uniqueItems":true,
             "contains":{"type":"integer"},"minContains":1,"maxContains":2}
        """)
        validator.validate(json("""[1,"x"]"""))
        validator.validate(json("""[1,2,"x"]"""))
        listOf("""["1","2"]""", """[1,2,3]""", """[1,1.0]""", """[1]""", """[1,"a","b","c","d","e"]""")
            .forEach { assertProblem { validator.validate(json(it)) } }
        val unique = compile("""{"type":"array","uniqueItems":true}""")
        assertProblem { unique.validate(json("""[{"a":1,"b":2},{"b":2.0,"a":1.0}]""")) }
    }

    @Test
    fun treatsKnownAnnotationsAndExampleObjectsAsDataNotSchemas() {
        ToolSchemaDialect.entries.forEach { dialect ->
            val validator = compile("""
                {"${'$'}schema":"${dialect.uri}","title":"演示工具","description":"说明",
                 "${'$'}comment":"annotation","deprecated":false,"readOnly":false,"writeOnly":false,
                 "type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"}},
                 "default":{"${'$'}ref":"file:///fixture-only","pattern":"(a+)+$","unknown":true},
                 "examples":[{"${'$'}schema":"not-a-dialect","${'$'}id":"ignored","format":"regex",
                              "${'$'}vocabulary":{"urn:fixture:unsupported":true}}],
                 "const":{"region":"ok"}}
            """)
            validator.validate(json("""{"region":"ok"}"""))
            assertProblem { validator.validate(json("""{"region":"wrong"}""")) }
        }
    }

    @Test
    fun rejectsUnknownRequiredVocabulariesAndUnknownAssertionKeywords() {
        compile("""
            {"${'$'}vocabulary":{"https://json-schema.org/draft/2020-12/vocab/core":true,
                                "urn:fixture:optional":false},"type":"integer"}
        """).validate(json("1"))
        listOf(
            """{"${'$'}vocabulary":{"urn:fixture:unknown":true}}""",
            """{"${'$'}vocabulary":{"urn:fixture:unknown":"false"}}""",
            """{"${'$'}vocabulary":{"not-an-absolute-uri":false}}""",
            """{"${'$'}vocabulary":[]}""",
            """{"${'$'}vocabulary":{"https://json-schema.org/draft/2020-12/vocab/format-assertion":true}}""",
            """{"unknownAssertion":true}""", """{"nullable":true}""", """{"discriminator":{}}""",
            """{"${'$'}vocabulary":{"urn:fixture:optional":false},"unknownAssertion":true}""",
            """{"items":[] }""", """{"additionalItems":false}""", """{"dependencies":{"a":["b"]}}""",
            """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","prefixItems":[true]}""",
            """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","unevaluatedProperties":false}"""
        ).forEach { assertProblem { compile(it) } }
    }

    @Test
    fun snapshotsDefinitionSoCallerMutationsCannotChangeValidationOrAdvertisedConstraints() {
        val typeFields = mutableMapOf<String, JsonElement>("type" to JsonPrimitive("integer"))
        val fields = mutableMapOf<String, JsonElement>(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf("n" to JsonObject(typeFields)))
        )
        val validator = ToolSchemaCompiler.compile(JsonObject(fields))
        typeFields["type"] = JsonPrimitive("string")
        fields.clear()
        validator.validate(json("""{"n":1}"""))
        assertProblem { validator.validate(json("""{"n":"one"}""")) }
        assertEquals(schema("""{"type":"object","properties":{"n":{"type":"integer"}}}"""), validator.definition)
    }

    private fun compile(text: String): ValidatedToolSchema = ToolSchemaCompiler.compile(schema(text))
    private fun schema(text: String): JsonObject = json(text) as JsonObject
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun assertProblem(action: () -> Unit): ToolException {
        try {
            action()
            fail("Expected a safe schema rejection")
        } catch (error: ToolException) {
            assertEquals(ToolProblemCode.SCHEMA, error.problem.code)
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            return error
        }
        error("unreachable")
    }
}
