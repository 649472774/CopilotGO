package com.tongxie.copilotgo.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class BoundedToolJsonTest {
    @Test
    fun rejectsAmbiguousDuplicateKeysIncludingEscapedId() {
        listOf(
            """{"id":"good","id":"bad"}""",
            """{"id":"good","\u0069d":"bad"}""",
            """{"outer":{"key":1,"key":2}}"""
        ).forEach { expectProblem(ToolProblemCode.PROTOCOL) { BoundedToolJson.parse(it) } }
    }

    @Test
    fun sameKeyInDifferentObjectsAndEscapedQuotesAreValid() {
        val json = """{"a":{"id":"one","text":"quote: \" and \\ end"},"b":{"id":"two"}}"""
        assertEquals(json, BoundedToolJson.parse(json).toString())
    }

    @Test
    fun boundsNestingScalarNodeCountAndUtf8BytesBeforeAllocation() {
        expectProblem(ToolProblemCode.TOO_LARGE) {
            BoundedToolJson.parse("[".repeat(33) + "0" + "]".repeat(33))
        }
        expectProblem(ToolProblemCode.TOO_LARGE) {
            BoundedToolJson.parse("[" + List(17_000) { "0" }.joinToString(",") + "]")
        }
        expectProblem(ToolProblemCode.TOO_LARGE) { BoundedToolJson.parse("\"世界\"", maxBytes = 5) }
    }

    @Test
    fun schemaDigestsAreCanonicalButNotInsensitiveToConstraintChanges() {
        val first = BoundedToolJson.parse("""{"type":"object","required":["query"]}""")
        val reordered = BoundedToolJson.parse("""{"required":["query"],"type":"object"}""")
        val different = BoundedToolJson.parse("""{"required":[],"type":"object"}""")
        assertEquals(BoundedToolJson.digest(first), BoundedToolJson.digest(reordered))
        assertNotEquals(BoundedToolJson.digest(first), BoundedToolJson.digest(different))
    }

    private fun expectProblem(code: ToolProblemCode, action: () -> Unit) {
        try {
            action()
            fail("Expected $code")
        } catch (e: ToolException) {
            assertEquals(code, e.problem.code)
        }
    }
}
