package com.tongxie.copilotgo.data.tools.mcp

import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.toolFailure
import okio.Buffer
import okio.ForwardingSource
import okio.Source

/** Reject malformed UTF-8 before JSON/SSE decoding can replace invalid bytes with U+FFFD. */
internal class McpUtf8Source(delegate: Source) : ForwardingSource(delegate) {
    private var continuation = 0
    private var minimum = 0x80
    private var maximum = 0xbf
    private val pending = Buffer()

    override fun read(sink: Buffer, byteCount: Long): Long {
        val start = sink.size
        val count = super.read(sink, byteCount)
        if (count == -1L) {
            if (continuation != 0) invalid()
            return count
        }
        sink.copyTo(pending, start, count)
        while (!pending.exhausted()) {
            val byte = pending.readByte().toInt() and 0xff
            if (continuation > 0) {
                if (byte !in minimum..maximum) invalid()
                continuation--
                minimum = 0x80
                maximum = 0xbf
            } else {
                when (byte) {
                    in 0..0x7f -> Unit
                    in 0xc2..0xdf -> continuation = 1
                    0xe0 -> { continuation = 2; minimum = 0xa0 }
                    in 0xe1..0xec, in 0xee..0xef -> continuation = 2
                    0xed -> { continuation = 2; maximum = 0x9f }
                    0xf0 -> { continuation = 3; minimum = 0x90 }
                    in 0xf1..0xf3 -> continuation = 3
                    0xf4 -> { continuation = 3; maximum = 0x8f }
                    else -> invalid()
                }
            }
        }
        return count
    }

    private fun invalid(): Nothing = toolFailure(ToolProblemCode.PROTOCOL, "MCP 消息不是有效 UTF-8 文本")
}
