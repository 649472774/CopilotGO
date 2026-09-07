package com.tongxie.copilotgo.data.tools.mcp

import com.tongxie.copilotgo.data.tools.BoundedToolJson
import com.tongxie.copilotgo.data.tools.ToolException
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpParameterHeadersTest {
    @Test
    fun mirrorsOnlyStaticallyReachableExactPropertyPaths() {
        val headers = McpParameterHeaders.compile(schema("""
            {"type":"object","properties":{
              "region":{"type":"string","x-mcp-header":"Region"},
              "routing":{"type":"object","properties":{"tenant":{"type":"integer","x-mcp-header":"Tenant"}}},
              "enabled":{"type":"boolean","x-mcp-header":"Enabled"}
            }}
        """))
        assertEquals(
            mapOf("Mcp-Param-Region" to "us-west1", "Mcp-Param-Tenant" to "42", "Mcp-Param-Enabled" to "false"),
            headers.forArguments(schema("""{"region":"us-west1","routing":{"tenant":42.0},"enabled":false}"""))
        )
        assertTrue(headers.forArguments(schema("""{"region":null,"routing":{}}""")).isEmpty())
    }

    @Test
    fun encodesUnicodeWhitespaceControlsAndSentinelCollision() {
        assertEquals("ordinary", McpParameterHeaders.encode("ordinary"))
        assertEquals("=?base64?SGVsbG8sIOS4lueVjA==?=", McpParameterHeaders.encode("Hello, 世界"))
        assertEquals("=?base64?IHBhZGRlZCA=?=", McpParameterHeaders.encode(" padded "))
        assertEquals("=?base64?bGluZTEKbGluZTI=?=", McpParameterHeaders.encode("line1\nline2"))
        assertEquals("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=", McpParameterHeaders.encode("=?base64?literal?="))
        assertEquals("=?base64?CXRleHQJ?=", McpParameterHeaders.encode("\ttext\t"))
    }

    @Test
    fun rejectsHeadersUnderCompositionArraysReferencesOrInvalidNames() {
        listOf(
            """{"type":"object","x-mcp-header":"Root"}""",
            """{"allOf":[{"properties":{"tenant":{"type":"string","x-mcp-header":"Tenant"}}}]}""",
            """{"properties":{"values":{"type":"array","items":{"type":"string","x-mcp-header":"Value"}}}}""",
            """{"definitions":{"region":{"type":"string","x-mcp-header":"Region"}}}""",
            """{"properties":{"region":{"type":"string","x-mcp-header":"bad\r\nheader"}}}""",
            """{"properties":{"region":{"type":"number","x-mcp-header":"Region"}}}""",
            """{"properties":{"region":{"type":"object","x-mcp-header":"Region"}}}""",
            """{"properties":{"region":{"type":"string","x-mcp-header":""}}}""",
            """{"properties":{"a":{"type":"string","x-mcp-header":"Tenant"},"b":{"type":"string","x-mcp-header":"tenant"}}}"""
        ).forEach { definition ->
            expectProblem(ToolProblemCode.SCHEMA) { McpParameterHeaders.compile(schema(definition)) }
        }
    }

    @Test
    fun safeIntegerConversionIsExactBoundedAndDoesNotAllocateFromHugeExponents() {
        val headers = McpParameterHeaders.compile(schema(
            """{"properties":{"n":{"type":"integer","x-mcp-header":"N"}}}"""
        ))
        assertEquals("9007199254740991", headers.forArguments(schema("""{"n":9007199254740991}"""))["Mcp-Param-N"])
        assertEquals("-9007199254740991", headers.forArguments(schema("""{"n":-9007199254740991}"""))["Mcp-Param-N"])
        assertEquals("1000", headers.forArguments(schema("""{"n":1e3}"""))["Mcp-Param-N"])
        listOf("9007199254740992", "-9007199254740992", "0.1", "\"1\"", "1e2147483647", "1e-2147483647").forEach {
            expectProblem(ToolProblemCode.SCHEMA) { headers.forArguments(schema("""{"n":$it}""")) }
        }
    }

    @Test
    fun boundsCombinedHeaderSizeAndDoesNotTreatExampleDataAsAnnotation() {
        val headers = McpParameterHeaders.compile(schema("""
            {"properties":{"a":{"type":"string","x-mcp-header":"A"},"b":{"type":"string","x-mcp-header":"B"}},
             "examples":[{"x-mcp-header":"example literal"}]}
        """))
        expectProblem(ToolProblemCode.TOO_LARGE) {
            headers.forArguments(schema("""{"a":"${"a".repeat(4096)}","b":"${"b".repeat(4096)}"}"""))
        }
    }

    private fun schema(text: String): JsonObject = BoundedToolJson.objectValue(text)

    private fun expectProblem(code: ToolProblemCode, action: () -> Unit) {
        try {
            action()
            fail("Expected $code")
        } catch (e: ToolException) {
            assertEquals(code, e.problem.code)
        }
    }
}
