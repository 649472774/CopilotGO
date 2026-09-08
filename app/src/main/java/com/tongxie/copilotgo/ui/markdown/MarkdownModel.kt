package com.tongxie.copilotgo.ui.markdown

internal object MarkdownLimits {
    const val SOURCE_CHARACTERS = 65_536
    const val BLOCKS = 160
    const val INLINE_CHARACTERS = 4_096
    const val INLINE_NODES = 512
    const val INLINE_DEPTH = 8
    const val TABLE_COLUMNS = 12
    const val TABLE_ROWS = 64
    const val TABLE_CELL_CHARACTERS = 2_048
    const val TABLE_CELLS = 512
    const val FORMULAS = 24
}

internal enum class InlineFormat { Bold, Italic, Strike, Code }

internal sealed interface MarkdownInline {
    data class Text(
        val text: String,
        val formats: Set<InlineFormat> = emptySet(),
        val destination: String? = null,
        val allowsCitations: Boolean = true
    ) : MarkdownInline

    data class Math(val source: String, val original: String) : MarkdownInline
}

internal data class InlineContent(val parts: List<MarkdownInline>)

internal enum class TableAlignment { Start, Center, End }

internal sealed interface MarkdownBlock {
    data class Paragraph(val content: List<InlineContent>) : MarkdownBlock
    data class Heading(val level: Int, val content: List<InlineContent>) : MarkdownBlock
    data class ListItem(
        val marker: String,
        val depth: Int,
        val content: List<InlineContent>
    ) : MarkdownBlock
    data class Quote(val depth: Int, val content: List<InlineContent>) : MarkdownBlock
    data class Code(
        val language: String,
        val languageChunks: List<String>,
        val code: String,
        val chunks: List<String>,
        val closed: Boolean
    ) : MarkdownBlock
    data class Math(val source: String, val original: String) : MarkdownBlock
    data class Table(
        val header: List<InlineContent>,
        val alignments: List<TableAlignment>,
        val rows: List<List<InlineContent>>
    ) : MarkdownBlock
    data class Literal(val chunks: List<String>, val explanation: String? = null) : MarkdownBlock
    data object Rule : MarkdownBlock
}

internal data class MarkdownEntry(
    val start: Int,
    val end: Int,
    val block: MarkdownBlock,
    val formulas: Int = 0,
    val simplified: Boolean = false
)

internal data class MarkdownDocument(
    val entries: List<MarkdownEntry>,
    val sourceLength: Int,
    val renderedSourceEnd: Int,
    val isPreview: Boolean,
    val simplified: Boolean
)

internal class InlineParseContext(
    var formulasLeft: Int = MarkdownLimits.FORMULAS,
    var tableCellsLeft: Int = MarkdownLimits.TABLE_CELLS
) {
    var simplified: Boolean = false
}

/** Layout chunks are bounded too: a single very long paragraph must not monopolize Text layout. */
internal fun textChunks(text: String, limit: Int = MarkdownLimits.INLINE_CHARACTERS): List<String> {
    require(limit >= 2)
    if (text.isEmpty()) return listOf("")
    val chunks = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = safePrefixEnd(text, (start + limit).coerceAtMost(text.length))
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline >= start) end = newline + 1
        }
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}

internal fun safePrefixEnd(text: String, requestedEnd: Int): Int {
    val end = requestedEnd.coerceIn(0, text.length)
    return if (end in 1 until text.length &&
        text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()
    ) end - 1 else end
}
