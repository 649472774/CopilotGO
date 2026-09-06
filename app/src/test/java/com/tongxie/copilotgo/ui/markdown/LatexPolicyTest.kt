package com.tongxie.copilotgo.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexPolicyTest {
    @Test
    fun acceptsCommonExpressionsAndBoundedMatrices() {
        listOf(
            "E = mc^2",
            "\\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}",
            "\\int_0^1 x^2\\, dx = \\frac{1}{3}",
            "\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}",
            "\\begin{pmatrix}a & b \\\\ c & d\\end{pmatrix}",
            "\\begin{array}{c|r}a & b \\\\ c & d\\end{array}",
            "\\text{price} = \\$5",
            "\\left\\{x \\in \\mathbb{R}\\right\\}",
            "\\boxed{\\mathscr{L}(x) = x^2}"
        ).forEach { assertNull(it, LatexPolicy.check(it)) }
    }

    @Test
    fun rejectsProgrammableMacrosExternalResourcesAndArbitrarySizingBeforeTex() {
        listOf(
            "\\newcommand{\\loop}{\\loop}\\loop",
            "\\def\\a{\\a}\\a",
            "\\input{example}",
            "\\includegraphics{example}",
            "\\scalebox{999999}{x}",
            "\\resizebox{999999pt}{999999pt}{x}",
            "\\rule{999999pt}{999999pt}",
            "\\hspace{999999pt}",
            "\\textcolor{red}{x}",
            "\\begin{unknown}x\\end{unknown}"
        ).forEach { assertEquals(it, LatexFallback.Unsupported, LatexPolicy.check(it)) }
    }

    @Test
    fun rejectsExpansionInArrayFormats() {
        assertEquals(
            LatexFallback.ComplexityLimit,
            LatexPolicy.check("\\begin{array}{*{999999}{c}}x\\end{array}")
        )
    }

    @Test
    fun rejectsLengthNestingAlignmentAndScriptOverruns() {
        assertEquals(LatexFallback.SourceLimit, LatexPolicy.check("x".repeat(LatexPolicy.SOURCE_CHARACTERS + 1)))
        val deep = "{".repeat(LatexPolicy.GROUP_DEPTH + 1) + "x" + "}".repeat(LatexPolicy.GROUP_DEPTH + 1)
        assertEquals(LatexFallback.ComplexityLimit, LatexPolicy.check(deep))
        assertEquals(LatexFallback.ComplexityLimit, LatexPolicy.check("a&".repeat(100)))
        assertEquals(LatexFallback.ComplexityLimit, LatexPolicy.check("x^".repeat(65) + "2"))
        assertEquals(LatexFallback.ComplexityLimit, LatexPolicy.check("\\alpha ".repeat(129)))
    }

    @Test
    fun malformedInputHasDeterministicFallback() {
        listOf("", "{x", "x}", "\\", "\\begin{matrix}x\\end{pmatrix}", "\\begin{matrix}x").forEach {
            assertEquals(it, LatexFallback.Malformed, LatexPolicy.check(it))
        }
    }

    @Test
    fun dimensionsAndTotalPixelsAreCheckedUsingLongArithmetic() {
        assertTrue(LatexPolicy.acceptsDimensions(1_024, 1_024))
        assertTrue(LatexPolicy.acceptsDimensions(4_096, 128))
        assertFalse(LatexPolicy.acceptsDimensions(4_096, 2_048))
        assertFalse(LatexPolicy.acceptsDimensions(4_097, 1))
        assertFalse(LatexPolicy.acceptsDimensions(1, 2_049))
        assertFalse(LatexPolicy.acceptsDimensions(0, 100))
        assertFalse(LatexPolicy.acceptsDimensions(-1, 100))
        assertFalse(LatexPolicy.acceptsDimensions(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun scalingSupportsTwoHundredPercentAndRejectsNonFiniteValues() {
        assertTrue(LatexPolicy.acceptsScale(18f * 3f * 2f, 3f, 2f))
        assertFalse(LatexPolicy.acceptsScale(Float.NaN, 1f, 1f))
        assertFalse(LatexPolicy.acceptsScale(Float.POSITIVE_INFINITY, 1f, 1f))
        assertFalse(LatexPolicy.acceptsScale(16f, 0f, 1f))
        assertFalse(LatexPolicy.acceptsScale(16f, 1f, Float.NaN))
    }

    @Test
    fun cacheKeyIncludesSourceStyleColorAndEveryScaleInput() {
        val key = LatexRequest("x", false, 0xff112233.toInt(), 48f, 3f, 1f)
        assertEquals(key, key.copy())
        listOf(
            key.copy(source = "y"),
            key.copy(display = true),
            key.copy(color = 0xff334455.toInt()),
            key.copy(sizePx = 96f),
            key.copy(density = 2f),
            key.copy(fontScale = 2f)
        ).forEach { assertFalse(key == it) }
    }
}
