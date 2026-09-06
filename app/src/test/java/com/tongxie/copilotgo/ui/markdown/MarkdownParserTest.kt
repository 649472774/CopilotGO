package com.tongxie.copilotgo.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MarkdownParserTest {
    @Test
    fun escapedDollarsAndCurrencyAreNotMath() {
        val source = "cost \$5 and \$10; escaped \\\$x\\\$; US\$20."
        val parsed = MarkdownInlineParser.parse(source)
        assertEquals("cost \$5 and \$10; escaped \$x\$; US\$20.", plain(parsed))
        assertFalse(parsed.parts.any { it is MarkdownInline.Math })
    }

    @Test
    fun mathRecognizesExpressionsAndExplicitNumericFormulas() {
        val parsed = MarkdownInlineParser.parse("结果 \$x^2 + 1\$，\$5\$ 与 \\(a+b\\)。")
        assertEquals(listOf("x^2 + 1", "5", "a+b"), parsed.parts.filterIsInstance<MarkdownInline.Math>().map { it.source })
    }

    @Test
    fun dollarsInsideCodeSpansRemainLiteral() {
        val parsed = MarkdownInlineParser.parse("`\$5 and \$10`，``a ` \$x\$``，\\`literal\\`")
        assertFalse(parsed.parts.any { it is MarkdownInline.Math })
        val code = parsed.parts.filterIsInstance<MarkdownInline.Text>().filter { InlineFormat.Code in it.formats }
        assertEquals(listOf("\$5 and \$10", "a ` \$x\$"), code.map { it.text })
        assertTrue(plain(parsed).endsWith("`literal`"))
    }

    @Test
    fun fencesPreserveCodeAndRequireMatchingClosers() {
        val source = "````kotlin\nval price = \"\$5\"\n```\n````\n\n~~~text\n\$x\$\n~~~\n"
        val codes = MarkdownParser().parse(source).entries.map { it.block }.filterIsInstance<MarkdownBlock.Code>()
        assertEquals(2, codes.size)
        assertEquals("val price = \"\$5\"\n```\n", codes[0].code)
        assertEquals("\$x\$\n", codes[1].code)
        assertTrue(codes.all { it.closed })
        assertEquals(codes[0].code, codes[0].chunks.joinToString(""))
    }

    @Test
    fun unfinishedSyntaxRemainsReadable() {
        listOf("*unfinished", "**unfinished", "[source](https://", "`pending", "\$x +", "~~pending").forEach {
            assertEquals(it, plain(MarkdownInlineParser.parse(it)))
        }
        val code = MarkdownParser().parse("```kotlin\nval a = 1").entries.single().block as MarkdownBlock.Code
        assertFalse(code.closed)
        assertEquals("val a = 1", code.code)
        val math = MarkdownParser().parse("\$\$\nx + 1").entries.single().block as MarkdownBlock.Literal
        assertEquals("\$\$\nx + 1", math.chunks.joinToString(""))
    }

    @Test
    fun displayMathDoesNotDiscardTextFollowingItsClosingDelimiter() {
        val document = MarkdownParser().parse("\$\$x^2\$\$ 后面的文字\n\n\\[y\\] 也保留")
        val blocks = document.entries.map { it.block }
        assertEquals(listOf("x^2", "y"), blocks.filterIsInstance<MarkdownBlock.Math>().map { it.source })
        assertEquals(
            listOf(" 后面的文字", " 也保留"),
            blocks.filterIsInstance<MarkdownBlock.Paragraph>().map { paragraphText(it) }
        )
        assertFalse(document.isPreview)
    }

    @Test
    fun supportsHeadingsListsQuotesRulesAndNestedInlineFormatting() {
        val source = "# 标题\n\n- 项目 **粗体和 *斜体***\n12) 编号\n> 引用 ~~删除~~\n\n---"
        val blocks = MarkdownParser().parse(source).entries.map { it.block }
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertEquals("•", (blocks[1] as MarkdownBlock.ListItem).marker)
        assertEquals("12)", (blocks[2] as MarkdownBlock.ListItem).marker)
        assertTrue(blocks[3] is MarkdownBlock.Quote)
        assertEquals(MarkdownBlock.Rule, blocks[4])
        val inline = MarkdownInlineParser.parse("**粗体** 和 *斜体* 和 ~~删除~~ 和 ***两者***")
        val formats = inline.parts.filterIsInstance<MarkdownInline.Text>().associate { it.text to it.formats }
        assertTrue(InlineFormat.Bold in formats.getValue("粗体"))
        assertTrue(InlineFormat.Italic in formats.getValue("斜体"))
        assertTrue(InlineFormat.Strike in formats.getValue("删除"))
        assertEquals(setOf(InlineFormat.Bold, InlineFormat.Italic), formats["两者"])
        val nested = MarkdownInlineParser.parse("**粗体和 *斜体***").parts.filterIsInstance<MarkdownInline.Text>()
        assertEquals(listOf("粗体和 ", "斜体"), nested.map { it.text })
        assertEquals(setOf(InlineFormat.Bold, InlineFormat.Italic), nested.last().formats)
    }

    @Test
    fun linksSupportBalancedParenthesesAndReadableUnsafeDestinations() {
        val content = MarkdownInlineParser.parse(
            "[**来源**](https://example.test/a_(b) \"说明\") <https://example.test/x> " +
                "https://example.test/end. [禁用](javascript:alert(1))"
        )
        val links = content.parts.filterIsInstance<MarkdownInline.Text>().filter { it.destination != null }
        assertEquals(
            listOf("https://example.test/a_(b)", "https://example.test/x", "https://example.test/end"),
            links.map { it.destination }
        )
        assertTrue(InlineFormat.Bold in links.first().formats)
        assertTrue(plain(content).endsWith("[禁用](javascript:alert(1))"))
    }

    @Test
    fun tablesRespectEscapedPipesCodeSpansAndAlignment() {
        val source = "| 名称 | 值 | 右侧 |\n| :--- | :---: | ---: |\n| a\\|b | `x|y` | 42 |\n| 少一格 | 数据 |\n"
        val table = MarkdownParser().parse(source).entries.single().block as MarkdownBlock.Table
        assertEquals(listOf(TableAlignment.Start, TableAlignment.Center, TableAlignment.End), table.alignments)
        assertEquals(listOf("a|b", "x|y", "42"), table.rows.first().map { plain(it) })
        assertEquals(listOf("少一格", "数据", ""), table.rows[1].map { plain(it) })
    }

    @Test
    fun doubledBackslashesDoNotEscapeTableSeparators() {
        val source = "| A | B |\n| --- | --- |\n| slash\\\\| value |\n"
        val table = MarkdownParser().parse(source).entries.single().block as MarkdownBlock.Table
        assertEquals(listOf("slash\\", "value"), table.rows.single().map { plain(it) })
    }

    @Test
    fun oversizedTablesFallBackWithoutDroppingCellsOrRows() {
        val columns = MarkdownLimits.TABLE_COLUMNS + 1
        val wide = (1..columns).joinToString("|", "|", "|\n") { "列$it" } +
            (1..columns).joinToString("|", "|", "|\n") { "---" } + "| 尾部内容 |\n"
        val raw = MarkdownParser().parse(wide).entries.single().block as MarkdownBlock.Literal
        assertEquals(wide, raw.chunks.joinToString(""))
        assertNotNull(raw.explanation)
        val tall = "| A | B |\n| --- | --- |\n" + (1..65).joinToString("") { "| $it | 值 |\n" }
        val tallRaw = MarkdownParser().parse(tall).entries.single().block as MarkdownBlock.Literal
        assertEquals(tall, tallRaw.chunks.joinToString(""))
    }

    @Test
    fun sourceAndBlockCapsAreExplicitAndDoNotMutateTheInput() {
        val source = "阅读".repeat(MarkdownLimits.SOURCE_CHARACTERS)
        val document = MarkdownParser().parse(source)
        assertTrue(document.isPreview)
        assertEquals(source.length, document.sourceLength)
        assertEquals(MarkdownLimits.SOURCE_CHARACTERS, document.renderedSourceEnd)
        assertEquals("阅读".repeat(MarkdownLimits.SOURCE_CHARACTERS), source)
        val many = (1..500).joinToString("\n") { "# 标题 $it" }
        val capped = MarkdownParser().parse(many)
        assertTrue(capped.isPreview)
        assertEquals(MarkdownLimits.BLOCKS, capped.entries.size)
        assertTrue(capped.renderedSourceEnd < many.length)
    }

    @Test
    fun formulaQuotaUsesVisibleRawSourceForTheRemainder() {
        val source = (1..40).joinToString(" ") { "\$x_$it\$" }
        val document = MarkdownParser().parse(source)
        assertEquals(MarkdownLimits.FORMULAS, document.entries.sumOf { it.formulas })
        assertTrue(document.simplified)
        assertFalse(document.isPreview)
        val block = document.entries.single().block as MarkdownBlock.Paragraph
        assertTrue(paragraphText(block).contains("\$x_40\$"))
    }

    @Test(timeout = 5_000)
    fun hostileUnmatchedDelimitersHaveBoundedWorkAndKeepTheirText() {
        val samples = listOf(
            "[".repeat(4_096),
            "\\$".repeat(2_048),
            "`a[".repeat(1_300),
            "<".repeat(4_096)
        )
        samples.forEach { sample ->
            val parsed = MarkdownInlineParser.parse(sample)
            assertTrue(plain(parsed).isNotEmpty())
            assertTrue(parsed.parts.size <= MarkdownLimits.INLINE_NODES + 2)
        }
        val million = "[".repeat(1_000_000)
        val document = MarkdownParser().parse(million)
        assertTrue(document.isPreview)
        assertEquals(MarkdownLimits.SOURCE_CHARACTERS, document.renderedSourceEnd)
    }

    @Test
    fun chunksAndPreviewNeverSplitSurrogatePairs() {
        val text = "a".repeat(MarkdownLimits.SOURCE_CHARACTERS - 1) + "😀suffix"
        val document = MarkdownParser().parse(text)
        assertEquals(MarkdownLimits.SOURCE_CHARACTERS - 1, document.renderedSourceEnd)
        val pieces = textChunks("a😀b😀c", 2)
        assertEquals("a😀b😀c", pieces.joinToString(""))
        assertTrue(pieces.none { it.firstOrNull()?.isLowSurrogate() == true })
        assertTrue(pieces.none { it.lastOrNull()?.isHighSurrogate() == true })
    }

    @Test
    fun appendCacheMatchesFreshParsingForEveryPartialInput() {
        val samples = listOf(
            "# 题目\n\n正文 **粗体** 和 \$x\$。\n\n```kotlin\nval a = 1\n```\n\n末尾",
            "说明\n\n| A | B |\n| --- | ---: |\n| a\\|b | `x|y` |\n| 末 | 行 |\n",
            "| A | B |\n---|---\n| 后 | 行 |\n",
            "\$\$\nx^2 + y^2\n\$\$ 尾部\n\n> 引用\n\n- 列表\n",
            "段落\n\n~~~\n\$x\$\n~~~\n\n[来源](https://example.test/a_(b))",
            "[部分](https://example.test)\n\n___\n\n\\[x\\] 尾\n\n````\n```\n````"
        )
        samples.forEach { full ->
            val parser = MarkdownParser()
            for (end in 0..full.length) {
                val prefix = full.substring(0, end)
                assertEquals("Prefix $end of $full", MarkdownParser().parse(prefix), parser.parse(prefix))
            }
        }
    }

    @Test
    fun completedBlocksRetainIdentityAndReplacementInvalidatesCache() {
        val parser = MarkdownParser()
        val source = "# One\n\n第一段\n\n## Two\n\n第二段\n\n最后"
        val before = parser.parse(source)
        val appended = parser.parse("${source}更新")
        assertTrue(parser.reusedBlocks > 0)
        assertSame(before.entries.first(), appended.entries.first())
        val replacement = "# 替换\n\n不同内容"
        assertEquals(MarkdownParser().parse(replacement), parser.parse(replacement))
        assertEquals(0, parser.reusedBlocks)
    }

    @Test
    fun boundedCacheRemainsCorrectForEditsAndAppendsOverThePreviewLimit() {
        val parser = MarkdownParser()
        val source = "# 稳定\n\n" + "z".repeat(MarkdownLimits.SOURCE_CHARACTERS)
        parser.parse(source)
        assertEquals(MarkdownParser().parse(source + "end"), parser.parse(source + "end"))
        assertEquals(MarkdownParser().parse(source.take(100)), parser.parse(source.take(100)))
        val random = Random(73)
        var value = ""
        repeat(200) {
            value = if (random.nextInt(5) == 0 && value.isNotEmpty()) value.dropLast(1)
            else value + listOf("\n", "|", "---", "a", "*", "\$", "`", "[", ">", " ")[random.nextInt(10)]
            assertEquals(MarkdownParser().parse(value), parser.parse(value))
        }
    }

    private fun plain(content: InlineContent): String = content.parts.joinToString("") {
        when (it) {
            is MarkdownInline.Text -> it.text
            is MarkdownInline.Math -> it.original
        }
    }

    private fun paragraphText(block: MarkdownBlock.Paragraph) = block.content.joinToString("\n") { plain(it) }
}
