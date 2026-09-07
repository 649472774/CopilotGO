package com.tongxie.copilotgo.data.tools

import com.tongxie.copilotgo.data.agent.AgentToolResult
import com.tongxie.copilotgo.data.agent.SourceKind
import com.tongxie.copilotgo.data.agent.SourceReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultBoundsTest {
    @Test
    fun boundsEntireEscapedUnicodeResultNotOnlyContentBytes() {
        val result = AgentToolResult(
            "quoted \"value\"\n界🙂".repeat(4000),
            sources = listOf(SourceReference("https://example.com/source", "Real fixture source", SourceKind.FETCHED_PAGE))
        )
        val bounded = boundAgentToolResult(result)
        assertTrue(encodedResultBytes(bounded) <= 24 * 1024)
        assertTrue(bounded.truncated)
        assertEquals(result.sources, bounded.sources)
        assertFalse(bounded.content.contains('\ufffd'))
    }

    @Test
    fun excessiveSourceMetadataIsPrunedWithoutChangingOrInventingUrls() {
        val result = AgentToolResult(
            "Actual tool text",
            sources = (1..32).map {
                SourceReference("https://example.com/$it/${"a".repeat(1800)}", "Title", SourceKind.TOOL_RESOURCE, excerpt = "x".repeat(1000))
            }
        )
        val bounded = boundAgentToolResult(result)
        assertTrue(encodedResultBytes(bounded) <= 24 * 1024)
        assertTrue(bounded.truncated)
        assertTrue(bounded.sources.isNotEmpty())
        assertTrue(bounded.sources.size < result.sources.size)
        assertTrue(bounded.sources.all { it in result.sources })
    }

    @Test
    fun preservesSmallSuccessAndUncertainErrorExactly() {
        val small = AgentToolResult("confirmed", sources = listOf(
            SourceReference("https://example.com/", "Example", SourceKind.SEARCH_HIT)
        ))
        assertEquals(small, boundAgentToolResult(small))
        val unknown = AgentToolResult("not confirmed", isError = true, outcomeUnknown = true)
        assertEquals(unknown, boundAgentToolResult(unknown))
    }
}
