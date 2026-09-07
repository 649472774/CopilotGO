package com.tongxie.copilotgo.data.tools.mcp

import com.tongxie.copilotgo.data.tools.ToolException
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import okio.Buffer
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class McpUtf8SourceTest {
    @Test
    fun readsValidAsciiCjkAndSupplementaryCharactersAcrossTinyReads() {
        val text = "{\"text\":\"A世界🙂\"}\n"
        val guarded = McpUtf8Source(Buffer().writeUtf8(text))
        val output = Buffer()
        while (guarded.read(output, 1) != -1L) Unit
        assertEquals(text, output.readUtf8())
    }

    @Test
    fun rejectsOverlongSurrogatesTruncatedAndOutOfRangeSequences() {
        listOf(
            byteArrayOf(0xc0.toByte(), 0x80.toByte()),
            byteArrayOf(0xe0.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xe4.toByte(), 0xb8.toByte()),
            byteArrayOf(0xc3.toByte(), 0x28)
        ).forEach { bytes ->
            try {
                McpUtf8Source(Buffer().write(bytes)).buffer().readUtf8()
                fail("Expected malformed UTF-8 rejection")
            } catch (e: ToolException) {
                assertEquals(ToolProblemCode.PROTOCOL, e.problem.code)
            }
        }
    }
}
