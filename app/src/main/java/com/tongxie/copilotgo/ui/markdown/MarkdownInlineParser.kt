package com.tongxie.copilotgo.ui.markdown

internal object MarkdownInlineParser {
    fun parse(
        text: String,
        context: InlineParseContext = InlineParseContext()
    ): InlineContent = Scanner(text, context).parse()

    private class Scanner(
        private val text: String,
        private val context: InlineParseContext
    ) {
        // Every look-ahead spends this shared budget; unmatched delimiters cannot cause quadratic work.
        private var operations = text.length.coerceAtMost(MarkdownLimits.INLINE_CHARACTERS) * 16 + 32
        private var nodesLeft = MarkdownLimits.INLINE_NODES

        fun parse() = InlineContent(range(0, text.length, emptySet(), null, 0))

        private fun tick(): Boolean = operations-- > 0

        private fun range(
            start: Int,
            end: Int,
            formats: Set<InlineFormat>,
            destination: String?,
            depth: Int
        ): List<MarkdownInline> {
            val result = ArrayList<MarkdownInline>()
            val plain = StringBuilder()
            fun flush() {
                if (plain.isNotEmpty()) {
                    result += MarkdownInline.Text(plain.toString(), formats, destination)
                    plain.setLength(0)
                    nodesLeft--
                }
            }
            var i = start
            while (i < end && nodesLeft > 1 && tick()) {
                val c = text[i]
                if (c == '\\' && i + 1 < end) {
                    if (text[i + 1] == '(' && destination == null) {
                        val close = closingSequence(i + 2, end, "\\)")
                        if (close > i + 2) {
                            flush()
                            addMath(result, i, i + 2, close, close + 2, formats)
                            i = close + 2
                            continue
                        }
                    }
                    if (isEscapable(text[i + 1])) {
                        plain.append(text[i + 1])
                        i += 2
                        continue
                    }
                }
                if (c == '`') {
                    val count = runLength(i, end, '`')
                    val close = closingBackticks(i + count, end, count)
                    if (close >= i + count) {
                        flush()
                        var code = text.substring(i + count, close).replace('\n', ' ')
                        if (code.startsWith(' ') && code.endsWith(' ') && code.any { it != ' ' }) {
                            code = code.substring(1, code.length - 1)
                        }
                        result += MarkdownInline.Text(code, formats + InlineFormat.Code, destination)
                        nodesLeft--
                        i = close + count
                        continue
                    }
                    plain.append(text, i, i + count)
                    i += count
                    continue
                }
                if (c == '$' && destination == null) {
                    val count = if (i + 1 < end && text[i + 1] == '$') 2 else 1
                    val from = i + count
                    val close = if (from < end && !text[from].isWhitespace()) {
                        closingDollar(from, end, count)
                    } else -1
                    if (close > from && !text[close - 1].isWhitespace() &&
                        (close + count == end || !text[close + count].isDigit()) &&
                        !looksLikeCurrency(from, close)
                    ) {
                        flush()
                        addMath(result, i, from, close, close + count, formats)
                        i = close + count
                        continue
                    }
                    plain.append(text, i, i + count)
                    i += count
                    continue
                }
                if (c == '[' && destination == null && (i == 0 || text[i - 1] != '!')) {
                    val bracket = closingBracket(i + 1, end)
                    if (bracket > i && bracket + 1 < end && text[bracket + 1] == '(') {
                        val close = closingParenthesis(bracket + 2, end)
                        if (close > bracket) {
                            val url = linkDestination(text.substring(bracket + 2, close))
                            if (url == null || depth >= MarkdownLimits.INLINE_DEPTH) {
                                plain.append(text, i, close + 1)
                            } else {
                                flush()
                                result += range(i + 1, bracket, formats, url, depth + 1)
                            }
                            i = close + 1
                            continue
                        }
                    }
                }
                if (c == '<' && destination == null) {
                    val close = closingSequence(i + 1, end, ">")
                    if (close > i + 1) {
                        val label = text.substring(i + 1, close)
                        val url = SafeMarkdownLink.destination(label)
                        if (url != null) {
                            flush()
                            result += MarkdownInline.Text(label, formats, url)
                            nodesLeft--
                            i = close + 1
                            continue
                        }
                    }
                }
                if (destination == null && startsUrl(i, end)) {
                    var close = i
                    while (close < end && !text[close].isWhitespace() &&
                        text[close] !in "<>`[]\"" && tick()
                    ) close++
                    close = trimUrlEnd(i, close)
                    val label = text.substring(i, close)
                    val url = SafeMarkdownLink.destination(label)
                    if (url != null) {
                        flush()
                        result += MarkdownInline.Text(label, formats, url)
                        nodesLeft--
                        i = close
                        continue
                    }
                }
                if ((c == '*' || c == '_' || c == '~') && depth < MarkdownLimits.INLINE_DEPTH) {
                    val count = runLength(i, end, c)
                    val usable = (c != '~' && count in 1..3) || (c == '~' && count == 2)
                    val canOpen = i + count < end && !text[i + count].isWhitespace() &&
                        !(c == '_' && i > start && text[i - 1].isLetterOrDigit())
                    if (usable && canOpen) {
                        val close = closingEmphasis(i + count, end, c, count)
                        if (close > i + count) {
                            flush()
                            val extra = when {
                                c == '~' -> setOf(InlineFormat.Strike)
                                count == 3 -> setOf(InlineFormat.Bold, InlineFormat.Italic)
                                count == 2 -> setOf(InlineFormat.Bold)
                                else -> setOf(InlineFormat.Italic)
                            }
                            result += range(i + count, close, formats + extra, destination, depth + 1)
                            i = close + count
                            continue
                        }
                    }
                    plain.append(text, i, i + count)
                    i += count
                    continue
                }
                plain.append(c)
                i++
            }
            if (i < end) {
                plain.append(text, i, end)
                context.simplified = true
            }
            flush()
            return result
        }

        private fun addMath(
            result: MutableList<MarkdownInline>,
            start: Int,
            bodyStart: Int,
            bodyEnd: Int,
            end: Int,
            formats: Set<InlineFormat>
        ) {
            val original = text.substring(start, end)
            if (context.formulasLeft > 0) {
                context.formulasLeft--
                result += MarkdownInline.Math(text.substring(bodyStart, bodyEnd), original)
            } else {
                context.simplified = true
                result += MarkdownInline.Text(original, formats)
            }
            nodesLeft--
        }

        private fun closingSequence(from: Int, end: Int, sequence: String): Int {
            var i = from
            while (i + sequence.length <= end && tick()) {
                if (text.startsWith(sequence, i)) return i
                i += if (text[i] == '\\' && i + 1 < end) 2 else 1
            }
            return -1
        }

        private fun runLength(from: Int, end: Int, marker: Char): Int {
            var i = from
            while (i < end && text[i] == marker && tick()) i++
            return (i - from).coerceAtLeast(1)
        }

        private fun closingBackticks(from: Int, end: Int, count: Int): Int {
            var i = from
            while (i < end && tick()) {
                if (text[i] == '`') {
                    val run = runLength(i, end, '`')
                    if (run == count) return i
                    i += run
                } else i++
            }
            return -1
        }

        private fun closingDollar(from: Int, end: Int, count: Int): Int {
            var i = from
            while (i < end && tick()) {
                when (text[i]) {
                    '\n' -> return -1
                    '\\' -> i += 2
                    '$' -> {
                        val run = if (i + 1 < end && text[i + 1] == '$') 2 else 1
                        if (run == count) return i
                        return -1
                    }
                    else -> i++
                }
            }
            return -1
        }

        private fun looksLikeCurrency(from: Int, end: Int): Boolean {
            if (!text[from].isDigit()) return false
            var letters = false
            var mathematical = false
            for (i in from until end) {
                if (!tick()) return true
                letters = letters || text[i].isLetter()
                mathematical = mathematical || text[i] in "\\^_{}=+-*/<>"
            }
            return letters && !mathematical
        }

        private fun closingBracket(from: Int, end: Int): Int {
            var nested = 0
            var i = from
            while (i < end && tick()) {
                when (text[i]) {
                    '\\' -> i++
                    '[' -> if (++nested > MarkdownLimits.INLINE_DEPTH) return -1
                    ']' -> if (nested-- == 0) return i
                }
                i++
            }
            return -1
        }

        private fun closingParenthesis(from: Int, end: Int): Int {
            var nested = 0
            var i = from
            var quote: Char? = null
            while (i < end && tick()) {
                val c = text[i]
                when {
                    c == '\\' -> i++
                    quote != null -> if (c == quote) quote = null
                    (c == '"' || c == '\'') && i > from && text[i - 1].isWhitespace() -> quote = c
                    c == '(' -> if (++nested > MarkdownLimits.INLINE_DEPTH) return -1
                    c == ')' -> if (nested-- == 0) return i
                    c == '\n' -> return -1
                }
                i++
            }
            return -1
        }

        private fun closingEmphasis(from: Int, end: Int, marker: Char, count: Int): Int {
            var i = from
            while (i < end && tick()) {
                if (text[i] == '\\') {
                    i += 2
                    continue
                }
                if (text[i] == '`') {
                    val ticks = runLength(i, end, '`')
                    val close = closingBackticks(i + ticks, end, ticks)
                    i = if (close >= 0) close + ticks else i + ticks
                    continue
                }
                if (text[i] == marker) {
                    val run = runLength(i, end, marker)
                    val matchingRun = run == count || (run == 3 && count < 3 && marker != '~')
                    if (matchingRun && i > from && !text[i - 1].isWhitespace() &&
                        !(marker == '_' && i + run < end && text[i + run].isLetterOrDigit())
                    ) return i + run - count
                    i += run
                } else i++
            }
            return -1
        }

        private fun startsUrl(from: Int, end: Int): Boolean =
            listOf("https://", "http://", "mailto:").any {
                from + it.length <= end && text.regionMatches(from, it, 0, it.length, ignoreCase = true)
            } && (from == 0 || !text[from - 1].isLetterOrDigit())

        private fun trimUrlEnd(from: Int, end: Int): Int {
            var result = end
            while (result > from && text[result - 1] in ".,!?:;") result--
            var opens = 0
            var closes = 0
            for (i in from until result) {
                if (text[i] == '(') opens++
                if (text[i] == ')') closes++
            }
            while (result > from && text[result - 1] == ')' && closes > opens) {
                closes--
                result--
            }
            return result
        }
    }

    private fun linkDestination(raw: String): String? {
        val trimmed = raw.trim()
        val url: String
        val suffix: String
        if (trimmed.startsWith('<')) {
            val close = trimmed.indexOf('>')
            if (close < 0) return null
            url = trimmed.substring(1, close)
            suffix = trimmed.substring(close + 1).trim()
        } else {
            val space = trimmed.indexOfFirst { it.isWhitespace() }
            url = if (space < 0) trimmed else trimmed.substring(0, space)
            suffix = if (space < 0) "" else trimmed.substring(space).trim()
        }
        if (suffix.isNotEmpty() &&
            !(suffix.length >= 2 && suffix.first() in "\"'" && suffix.last() == suffix.first())
        ) return null
        val unescaped = StringBuilder()
        var i = 0
        while (i < url.length) {
            if (url[i] == '\\' && i + 1 < url.length && isEscapable(url[i + 1])) i++
            unescaped.append(url[i++])
        }
        return SafeMarkdownLink.destination(unescaped.toString())
    }

    private fun isEscapable(c: Char): Boolean = c in "\\`*_{}[]()#+-.!|>$~"
}
