package com.tongxie.copilotgo.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MarkdownCitationsTest {
    private val sources = mapOf("S1" to "https://example.com/actual-page")

    @Test fun onlyKnownTokensGainLinksAndFormattingRemainsIntact() {
        val format = setOf(InlineFormat.Bold)
        val parts = listOf(MarkdownInline.Text("Actual [S1], unknown [S99].", format))
        assertEquals(
            listOf(
                MarkdownInline.Text("Actual ", format),
                MarkdownInline.Text("[S1]", format, sources.getValue("S1")),
                MarkdownInline.Text(", unknown [S99].", format)
            ),
            resolveMarkdownCitations(parts, sources)
        )
    }

    @Test fun inlineCodeMathAndExistingLinksAreNotRewritten() {
        val parts = listOf(
            MarkdownInline.Text("[S1]", setOf(InlineFormat.Code)),
            MarkdownInline.Math("S1", "$" + "S1" + "$"),
            MarkdownInline.Text("[S1]", destination = "https://example.com/explicit-link")
        )
        assertEquals(parts, resolveMarkdownCitations(parts, sources))
    }

    @Test fun ordinaryMarkdownWithoutRuntimeSourcesIsUnchanged() {
        val parts = listOf(MarkdownInline.Text("[S1]"))
        assertSame(parts, resolveMarkdownCitations(parts, emptyMap()))
    }

    @Test fun unsafeDestinationsDoNotGainCitationActions() {
        val parts = listOf(MarkdownInline.Text("[S1]"))
        val resolved = resolveMarkdownCitations(parts, mapOf("S1" to "https://user:secret@example.com/result"))
        assertEquals(parts, resolved)
        assertNull((resolved.single() as MarkdownInline.Text).destination)
    }
}
