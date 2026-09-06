package com.tongxie.copilotgo.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderBudgetTest {
    @Test fun manySmallTablesShareOneRenderingBudgetAndKeepTheirSource() {
        val source = (0..19).joinToString("\n\n", transform = ::table)
        val document = MarkdownParser().parse(source)
        val cells = document.entries.sumOf {
            when (val block = it.block) {
                is MarkdownBlock.Table -> block.header.size * (block.rows.size + 1)
                else -> 0
            }
        }
        assertTrue(cells <= MarkdownLimits.TABLE_CELLS)
        assertTrue(document.simplified)
        assertFalse(document.isPreview)
        assertTrue(document.entries.any {
            val literal = it.block as? MarkdownBlock.Literal
            literal != null && literal.chunks.joinToString("").contains("r19-4")
        })
    }

    @Test fun incrementalTableBudgetMatchesFreshParsing() {
        val parser = MarkdownParser()
        val prefix = (0..9).joinToString("\n\n", transform = ::table)
        parser.parse(prefix)
        val full = prefix + "\n\n" + (10..15).joinToString("\n\n", transform = ::table)
        val incremental = parser.parse(full)
        val fresh = MarkdownParser().parse(full)
        assertTrue(parser.reusedBlocks > 0)
        assertEquals(fresh.entries, incremental.entries)
        assertEquals(fresh.simplified, incremental.simplified)
        assertEquals(fresh.renderedSourceEnd, incremental.renderedSourceEnd)
    }

    private fun table(index: Int): String = buildString {
        append("|")
        append((0..11).joinToString("|") { "h$it" })
        append("|\n|")
        append((0..11).joinToString("|") { "---" })
        append("|\n")
        repeat(5) { row ->
            append("|")
            append((0..11).joinToString("|") { "r$index-$row" })
            append("|\n")
        }
    }
}
