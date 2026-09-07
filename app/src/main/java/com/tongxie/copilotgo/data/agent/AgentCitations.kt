package com.tongxie.copilotgo.data.agent

internal data class GroundedAgentText(val text: String, val removedUnsupportedReferences: Boolean)

internal object AgentCitations {
    private val citation = Regex("\\[S[0-9]+]")
    private val link = Regex("\\[([^]\\r\\n]*)]\\((https?://[^\\s)]+)\\)")
    private val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")

    fun ground(text: String, sources: List<SourceReference>): GroundedAgentText {
        val ids = sources.map { "[${it.id}]" }.toSet()
        val urls = sources.map { it.url }.toSet()
        var removed = false
        fun prose(value: String): String {
            val references = citation.replace(value) {
                if (it.value in ids) it.value else {
                    removed = true
                    ""
                }
            }
            return link.replace(references) {
                if (it.groupValues[2] in urls) it.value else {
                    removed = true
                    it.groupValues[1]
                }
            }
        }
        fun inline(value: String): String = buildString {
            var position = 0
            while (position < value.length) {
                val start = value.indexOf('`', position)
                if (start < 0) {
                    append(prose(value.substring(position)))
                    break
                }
                var end = start
                while (end < value.length && value[end] == '`') end++
                val delimiter = value.substring(start, end)
                var close = value.indexOf(delimiter, end)
                while (close >= 0 && (value.getOrNull(close - 1) == '`' || value.getOrNull(close + delimiter.length) == '`')) {
                    close = value.indexOf(delimiter, close + delimiter.length)
                }
                if (close < 0) {
                    append(prose(value.substring(position, end)))
                    position = end
                } else {
                    append(prose(value.substring(position, start)))
                    append(value.substring(start, close + delimiter.length))
                    position = close + delimiter.length
                }
            }
        }
        val grounded = buildString {
            var position = 0
            var openFence: String? = null
            while (position < text.length) {
                val end = text.indexOf('\n', position).let { if (it < 0) text.length else it + 1 }
                val line = text.substring(position, end)
                val marker = fence.matchEntire(line.trimEnd('\r', '\n'))
                val current = openFence
                if (current != null) {
                    append(line)
                    if (marker != null && marker.groupValues[1].first() == current.first() &&
                        marker.groupValues[1].length >= current.length && marker.groupValues[2].isBlank()
                    ) openFence = null
                } else if (marker != null) {
                    openFence = marker.groupValues[1]
                    append(line)
                } else if (line.startsWith("    ") || line.startsWith("\t")) {
                    append(line)
                } else append(inline(line))
                position = end
            }
        }
        return GroundedAgentText(grounded, removed)
    }
}
