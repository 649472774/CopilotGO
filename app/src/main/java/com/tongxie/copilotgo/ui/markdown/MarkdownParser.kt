package com.tongxie.copilotgo.ui.markdown

/**
 * A single-consumer parser. Only the bounded preview is retained, never the full conversation.
 * The final two blocks are provisional: appends can finish a fence or turn the preceding line
 * into a table header. Earlier blocks (including their inline models) retain their identity.
 */
internal class MarkdownParser {
    private var previousSource = ""
    private var previous: MarkdownDocument? = null
    var reusedBlocks: Int = 0
        private set

    fun parse(source: String): MarkdownDocument {
        val limit = safePrefixEnd(source, MarkdownLimits.SOURCE_CHARACTERS)
        val bounded = source.substring(0, limit)
        val old = previous
        if (old != null && bounded == previousSource) {
            reusedBlocks = old.entries.size
            return old.copy(
                sourceLength = source.length,
                isPreview = old.renderedSourceEnd < source.length
            ).also { previous = it }
        }
        val appending = old != null && bounded.startsWith(previousSource)
        val retained = if (appending) old!!.entries.dropLast(2) else emptyList()
        reusedBlocks = retained.size
        val start = if (appending && old!!.entries.size > retained.size) {
            old.entries[retained.size].start
        } else 0
        val entries = ArrayList(retained)
        val context = InlineParseContext(
            MarkdownLimits.FORMULAS - retained.sumOf { it.formulas },
            MarkdownLimits.TABLE_CELLS - retained.sumOf {
                when (val block = it.block) {
                    is MarkdownBlock.Table -> block.header.size * (block.rows.size + 1)
                    else -> 0
                }
            }
        )
        var position = start
        while (position < bounded.length && entries.size < MarkdownLimits.BLOCKS) {
            val current = line(bounded, position)
            if (current.text.isBlank()) {
                position = current.next
                continue
            }
            val beforeMath = context.formulasLeft
            context.simplified = false
            val parsed = parseBlock(bounded, current, context)
            entries += MarkdownEntry(
                start = current.start,
                end = parsed.end,
                block = parsed.block,
                formulas = beforeMath - context.formulasLeft,
                simplified = context.simplified
            )
            position = parsed.end
        }
        // Whitespace after the last permitted block is not hidden user content.
        while (position < bounded.length && bounded[position].isWhitespace()) position++
        val document = MarkdownDocument(
            entries = entries,
            sourceLength = source.length,
            renderedSourceEnd = position,
            isPreview = position < source.length,
            simplified = entries.any { it.simplified }
        )
        previousSource = bounded
        previous = document
        return document
    }

    private data class Line(val start: Int, val next: Int, val text: String)
    private data class Parsed(val block: MarkdownBlock, val end: Int)
    private data class Fence(val marker: Char, val count: Int, val language: String)
    private data class ListPrefix(val marker: String, val depth: Int, val body: String)
    private data class TableCells(val cells: List<String>, val hasPipe: Boolean, val tooWide: Boolean)
    private data class TableHeader(val cells: TableCells, val separator: Line, val alignments: List<TableAlignment>)

    private fun line(source: String, from: Int): Line {
        val end = source.indexOf('\n', from).let { if (it < 0) source.length else it }
        return Line(
            from,
            if (end < source.length) end + 1 else end,
            source.substring(from, end).removeSuffix("\r")
        )
    }

    private fun parseBlock(source: String, current: Line, context: InlineParseContext): Parsed {
        val trimmed = current.text.trimStart()
        fence(trimmed)?.let { opening ->
            var cursor = current.next
            var close: Line? = null
            while (cursor < source.length) {
                val next = line(source, cursor)
                val candidate = next.text.trim()
                val count = candidate.takeWhile { it == opening.marker }.length
                if (count >= opening.count && candidate.substring(count).isBlank()) {
                    close = next
                    break
                }
                cursor = next.next
            }
            val body = source.substring(current.next, close?.start ?: source.length)
            return Parsed(
                MarkdownBlock.Code(opening.language, textChunks(opening.language), body, textChunks(body), close != null),
                close?.next ?: source.length
            )
        }
        if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
            val opening = current.start + current.text.length - trimmed.length
            val marker = if (trimmed.startsWith("$$")) "$$" else "\\]"
            val end = mathClose(source, opening + 2, marker)
            if (end >= 0) {
                val original = source.substring(opening, end + 2)
                val body = source.substring(opening + 2, end).trim()
                if (body.isNotEmpty() && context.formulasLeft > 0) {
                    context.formulasLeft--
                    return Parsed(MarkdownBlock.Math(body, original), end + 2)
                }
                if (body.isNotEmpty()) context.simplified = true
                return Parsed(MarkdownBlock.Literal(textChunks(original)), end + 2)
            }
            return Parsed(
                MarkdownBlock.Literal(textChunks(source.substring(current.start))),
                source.length
            )
        }
        tableHeader(source, current)?.let { header ->
            return parseTable(source, current, header, context)
        }
        heading(trimmed)?.let { (level, body) ->
            return Parsed(MarkdownBlock.Heading(level, inlines(body, context)), current.next)
        }
        if (isRule(trimmed)) return Parsed(MarkdownBlock.Rule, current.next)
        listPrefix(current.text)?.let { prefix ->
            return Parsed(
                MarkdownBlock.ListItem(prefix.marker, prefix.depth, inlines(prefix.body, context)),
                current.next
            )
        }
        if (trimmed.startsWith('>')) {
            var cursor = 0
            var depth = 0
            while (cursor < trimmed.length && trimmed[cursor] == '>') {
                depth++
                cursor++
                if (cursor < trimmed.length && trimmed[cursor] == ' ') cursor++
            }
            return Parsed(
                MarkdownBlock.Quote(depth.coerceAtMost(8), inlines(trimmed.substring(cursor), context)),
                current.next
            )
        }
        var cursor = current.next
        while (cursor < source.length) {
            val next = line(source, cursor)
            if (next.text.isBlank() || beginsBlock(next.text) || tableHeader(source, next) != null) break
            cursor = next.next
        }
        val body = source.substring(current.start, cursor).trimEnd('\r', '\n')
        return Parsed(MarkdownBlock.Paragraph(inlines(body, context)), cursor)
    }

    private fun inlines(text: String, context: InlineParseContext): List<InlineContent> {
        val chunks = textChunks(text)
        if (chunks.size > 1) context.simplified = true
        return chunks.map { MarkdownInlineParser.parse(it.removeSuffix("\n").removeSuffix("\r"), context) }
    }

    private fun fence(text: String): Fence? {
        val marker = text.firstOrNull() ?: return null
        if (marker != '`' && marker != '~') return null
        val count = text.takeWhile { it == marker }.length
        if (count < 3) return null
        val language = text.substring(count).trim()
        if (marker == '`' && language.contains('`')) return null
        return Fence(marker, count, language)
    }

    private fun heading(text: String): Pair<Int, String>? {
        val count = text.takeWhile { it == '#' }.length
        if (count !in 1..6 || count == text.length || !text[count].isWhitespace()) return null
        var body = text.substring(count).trim()
        val closing = body.takeLastWhile { it == '#' }.length
        if (closing > 0 && body.length > closing && body[body.length - closing - 1].isWhitespace()) {
            body = body.dropLast(closing).trimEnd()
        }
        return count to body
    }

    private fun isRule(text: String): Boolean {
        val marker = text.firstOrNull() ?: return false
        return marker in "*-_" && text.count { it == marker } >= 3 &&
            text.all { it == marker || it.isWhitespace() }
    }

    private fun listPrefix(text: String): ListPrefix? {
        val indentation = text.takeWhile { it == ' ' || it == '\t' }.length
        val trimmed = text.substring(indentation)
        if (trimmed.isEmpty()) return null
        if (trimmed[0] in "-*+" && trimmed.length > 1 && trimmed[1].isWhitespace()) {
            return ListPrefix("•", (indentation / 2).coerceAtMost(8), trimmed.substring(2).trimStart())
        }
        val digits = trimmed.takeWhile { it in '0'..'9' }.length
        if (digits in 1..9 && digits + 1 < trimmed.length &&
            trimmed[digits] in ".)" && trimmed[digits + 1].isWhitespace()
        ) {
            return ListPrefix(
                trimmed.substring(0, digits + 1),
                (indentation / 2).coerceAtMost(8),
                trimmed.substring(digits + 2).trimStart()
            )
        }
        return null
    }

    private fun beginsBlock(text: String): Boolean {
        val trimmed = text.trimStart()
        return fence(trimmed) != null || heading(trimmed) != null ||
            isRule(trimmed) || listPrefix(text) != null ||
            trimmed.startsWith('>') || trimmed.startsWith("$$") || trimmed.startsWith("\\[")
    }

    private fun mathClose(source: String, from: Int, marker: String): Int {
        var cursor = from
        while (cursor + marker.length <= source.length) {
            if (source.startsWith(marker, cursor)) return cursor
            cursor += if (source[cursor] == '\\' && cursor + 1 < source.length) 2 else 1
        }
        return -1
    }

    private fun tableHeader(source: String, current: Line): TableHeader? {
        if (!current.text.contains('|') || current.next >= source.length) return null
        val separator = line(source, current.next)
        val separatorText = separator.text.trim()
        if (!separatorText.contains('-') ||
            separatorText.any { it !in "|-: \t" }
        ) return null
        val cells = tableCells(current.text)
        if (!cells.hasPipe) return null
        val delimiters = tableCells(separator.text)
        val alignments = delimiters.cells.map { alignment(it) ?: return null }
        if (cells.tooWide || delimiters.tooWide) {
            return TableHeader(cells.copy(tooWide = true), separator, emptyList())
        }
        if (cells.cells.size != alignments.size || cells.cells.isEmpty()) return null
        return TableHeader(cells, separator, alignments)
    }

    private fun alignment(cell: String): TableAlignment? {
        val text = cell.trim()
        val middle = text.removePrefix(":").removeSuffix(":")
        if (middle.length < 3 || middle.any { it != '-' }) return null
        return when {
            text.startsWith(':') && text.endsWith(':') -> TableAlignment.Center
            text.endsWith(':') -> TableAlignment.End
            else -> TableAlignment.Start
        }
    }

    private fun parseTable(
        source: String,
        first: Line,
        header: TableHeader,
        context: InlineParseContext
    ): Parsed {
        var cursor = header.separator.next
        val rows = ArrayList<List<String>>()
        val columns = header.cells.cells.size
        var limited = header.cells.tooWide ||
            header.cells.cells.any { it.length > MarkdownLimits.TABLE_CELL_CHARACTERS }
        while (cursor < source.length) {
            val next = line(source, cursor)
            if (next.text.isBlank()) break
            val cells = tableCells(next.text)
            if (!cells.hasPipe) break
            if (rows.size >= MarkdownLimits.TABLE_ROWS || cells.tooWide ||
                cells.cells.size > columns ||
                cells.cells.any { it.length > MarkdownLimits.TABLE_CELL_CHARACTERS }
            ) limited = true
            if (!limited) rows += cells.cells + List((columns - cells.cells.size).coerceAtLeast(0)) { "" }
            cursor = next.next
        }
        val cellCount = columns * (rows.size + 1)
        if (limited || cellCount > context.tableCellsLeft) {
            context.simplified = true
            return Parsed(
                MarkdownBlock.Literal(
                    textChunks(source.substring(first.start, cursor)),
                    "表格超出排版限制，以下显示 Markdown 原文。"
                ),
                cursor
            )
        }
        context.tableCellsLeft -= cellCount
        return Parsed(
            MarkdownBlock.Table(
                header.cells.cells.map { MarkdownInlineParser.parse(it, context) },
                header.alignments,
                rows.map { row -> row.map { MarkdownInlineParser.parse(it, context) } }
            ),
            cursor
        )
    }

    private fun tableCells(raw: String): TableCells {
        val text = raw.trim()
        val cells = ArrayList<String>()
        var start = 0
        var cursor = 0
        var codeTicks = 0
        var sawPipe = false
        var trailingPipe = false
        while (cursor < text.length) {
            when (text[cursor]) {
                '\\' -> {
                    cursor += 2
                    trailingPipe = false
                    continue
                }
                '`' -> {
                    var count = 1
                    while (cursor + count < text.length && text[cursor + count] == '`') count++
                    if (codeTicks == 0) codeTicks = count else if (codeTicks == count) codeTicks = 0
                    cursor += count
                    trailingPipe = false
                    continue
                }
                '|' -> if (codeTicks == 0) {
                    sawPipe = true
                    if (cursor != 0) cells += text.substring(start, cursor).trim()
                    start = cursor + 1
                    trailingPipe = true
                    if (cells.size > MarkdownLimits.TABLE_COLUMNS) {
                        return TableCells(cells, true, true)
                    }
                    cursor++
                    continue
                }
            }
            trailingPipe = false
            cursor++
        }
        if (!trailingPipe) cells += text.substring(start).trim()
        return TableCells(cells, sawPipe, cells.size > MarkdownLimits.TABLE_COLUMNS)
    }
}
