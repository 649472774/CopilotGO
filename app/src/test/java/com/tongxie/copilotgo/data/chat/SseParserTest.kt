package com.tongxie.copilotgo.data.chat

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class SseParserTest {

    private fun source(text: String) = Buffer().writeUtf8(text)

    @Test
    fun parses_data_lines_skipping_blank_and_done() = runTest {
        val sse = """
            data: {"a":1}

            data: {"a":2}
            
            data: [DONE]
            data: {"never":true}
        """.trimIndent()
        val lines = SseParser.lines(source(sse)).toList()
        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), lines)
    }

    @Test
    fun ignores_non_data_prefix_lines() = runTest {
        val sse = "event: ping\nid: 42\ndata: hello\n\n"
        val lines = SseParser.lines(source(sse)).toList()
        assertEquals(listOf("hello"), lines)
    }

    @Test
    fun handles_no_space_after_colon() = runTest {
        val sse = "data:{\"x\":3}\n\n"
        val lines = SseParser.lines(source(sse)).toList()
        assertEquals(listOf("""{"x":3}"""), lines)
    }

    @Test
    fun joins_multiline_event_data_and_preserves_error_name() = runTest {
        val events = SseParser.events(source(
            "\uFEFF: keepalive\r\nevent: error\r\nid: 42\r\ndata: {\"error\":\r\ndata: {\"code\":\"busy\"}}\r\n\r\n"
        )).toList()
        assertEquals(listOf(SseEvent("error", "{\"error\":\n{\"code\":\"busy\"}}", "42")), events)
    }

    @Test
    fun dispatches_error_even_without_data() = runTest {
        assertEquals(listOf(SseEvent("error", "")), SseParser.events(source("event: error\n\n")).toList())
    }
}
