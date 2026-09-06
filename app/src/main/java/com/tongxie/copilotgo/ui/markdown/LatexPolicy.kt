package com.tongxie.copilotgo.ui.markdown

internal enum class LatexFallback(val explanation: String) {
    SourceLimit("公式较长，已显示源码。"),
    ComplexityLimit("公式较复杂，已显示源码。"),
    Unsupported("此公式语法暂不支持，已显示源码。"),
    Malformed("公式尚未完整或格式有误，已显示源码。"),
    PixelLimit("公式尺寸超出显示限制，已显示源码。"),
    Capacity("为控制内存用量，已显示公式源码。"),
    RenderFailed("暂时无法排版此公式，已显示源码。")
}

/**
 * JLatexMath is not a cancellable interpreter. Reject programmable macros, external resources,
 * arbitrary dimensions and unbounded environments BEFORE entering it, rather than timing it out.
 */
internal object LatexPolicy {
    const val SOURCE_CHARACTERS = 2_048
    const val GROUP_DEPTH = 12
    const val COMMANDS = 128
    const val MAX_WIDTH = 4_096
    const val MAX_HEIGHT = 2_048
    const val FORMULA_PIXELS = 1_048_576L
    const val RETAINED_PIXELS = 4_194_304L
    const val CACHE_ENTRIES = 32

    private val commands = """
        frac dfrac tfrac cfrac binom dbinom tbinom sqrt root of over atop choose
        left right middle big Big bigg Bigg bigl bigr Bigl Bigr biggl biggr Biggl Biggr
        sum prod coprod int iint iiint iiiint oint oiint oiiint lim limsup liminf
        sin cos tan cot sec csc arcsin arccos arctan sinh cosh tanh coth
        log ln lg exp det dim gcd max min sup inf arg deg Pr mod bmod pmod
        alpha beta gamma delta epsilon varepsilon zeta eta theta vartheta iota kappa
        lambda mu nu xi omicron pi varpi rho varrho sigma varsigma tau upsilon phi varphi chi psi omega
        Gamma Delta Theta Lambda Xi Pi Sigma Upsilon Phi Psi Omega
        infty partial nabla ell hbar imath jmath wp Re Im emptyset varnothing
        forall exists nexists neg land lor wedge vee cap cup uplus sqcap sqcup
        bigcap bigcup bigsqcup bigvee bigwedge biguplus
        in notin ni subset supset subseteq supseteq nsubseteq nsupseteq
        le leq ge geq ne neq equiv approx sim simeq cong propto asymp ll gg
        prec succ preceq succeq perp parallel mid nmid models vdash dashv
        cdot cdots ldots vdots ddots dots times div pm mp ast star circ bullet
        oplus ominus otimes oslash odot bigoplus bigotimes bigodot
        to mapsto gets rightarrow leftarrow leftrightarrow Rightarrow Leftarrow Leftrightarrow
        longrightarrow longleftarrow longleftrightarrow Longrightarrow Longleftarrow Longleftrightarrow
        hookrightarrow hookleftarrow uparrow downarrow updownarrow Uparrow Downarrow Updownarrow
        nearrow searrow swarrow nwarrow
        langle rangle lvert rvert lVert rVert vert Vert lbrace rbrace lceil rceil lfloor rfloor
        overline underline overbrace underbrace overset underset stackrel
        hat widehat bar vec tilde widetilde dot ddot dddot breve check acute grave
        mathrm mathit mathbf mathsf mathtt mathbb mathcal mathfrak mathscr mathnormal boldsymbol pmb
        mathop mathrel mathbin mathord mathopen mathclose mathpunct boxed cancel bcancel xcancel cancelto
        text textrm textit textbf textsf texttt textnormal operatorname
        rm bf it sf tt cal mit
        displaystyle textstyle scriptstyle scriptscriptstyle limits nolimits
        quad qquad enspace thinspace medspace thickspace negthinspace
        phantom hphantom vphantom smash substack
        prime backprime angle triangle square Box Diamond dotsc dotsb dotsm dotsi dotso
        degree lnot iff implies because therefore
        begin end
    """.trimIndent().split(Regex("\\s+")).toSet()

    private val environments = setOf(
        "matrix", "pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix",
        "smallmatrix", "aligned", "gathered", "cases", "array"
    )

    fun check(source: String): LatexFallback? {
        if (source.length > SOURCE_CHARACTERS) return LatexFallback.SourceLimit
        if (source.isBlank()) return LatexFallback.Malformed
        var groups = 0
        var commandCount = 0
        var alignments = 0
        var rows = 0
        var scripts = 0
        val environmentStack = ArrayList<String>()
        var i = 0
        while (i < source.length) {
            when (source[i]) {
                '{' -> if (++groups > GROUP_DEPTH) return LatexFallback.ComplexityLimit
                '}' -> if (--groups < 0) return LatexFallback.Malformed
                '&' -> if (++alignments > 96) return LatexFallback.ComplexityLimit
                '^', '_' -> if (++scripts > 64) return LatexFallback.ComplexityLimit
                '\n' -> if (++rows > 64) return LatexFallback.ComplexityLimit
                '\\' -> {
                    if (++commandCount > COMMANDS) return LatexFallback.ComplexityLimit
                    i++
                    if (i == source.length) return LatexFallback.Malformed
                    if (source[i].isLetter()) {
                        val start = i
                        while (i < source.length && source[i].isLetter()) i++
                        val command = source.substring(start, i)
                        if (command !in commands) return LatexFallback.Unsupported
                        if (command == "begin" || command == "end") {
                            while (i < source.length && source[i].isWhitespace()) i++
                            if (i >= source.length || source[i] != '{') return LatexFallback.Malformed
                            val close = source.indexOf('}', i + 1)
                            if (close < 0) return LatexFallback.Malformed
                            val environment = source.substring(i + 1, close)
                            if (environment !in environments) return LatexFallback.Unsupported
                            if (command == "begin") {
                                if (environmentStack.size >= 4) return LatexFallback.ComplexityLimit
                                environmentStack += environment
                            } else {
                                if (environmentStack.lastOrNull() != environment) return LatexFallback.Malformed
                                environmentStack.removeAt(environmentStack.lastIndex)
                            }
                            i = close + 1
                            if (environment == "array" && command == "begin") {
                                while (i < source.length && source[i].isWhitespace()) i++
                                if (i >= source.length || source[i] != '{') return LatexFallback.Malformed
                                val formatEnd = source.indexOf('}', i + 1)
                                if (formatEnd < 0) return LatexFallback.Malformed
                                val format = source.substring(i + 1, formatEnd)
                                if (format.length > 24 || format.any { it !in "lcr| " } ||
                                    format.count { it in "lcr" } !in 1..12
                                ) return LatexFallback.ComplexityLimit
                                i = formatEnd + 1
                            }
                        }
                        continue
                    }
                    if (source[i] == '\\' && ++rows > 64) return LatexFallback.ComplexityLimit
                    if (source[i] !in "\\{}$%#&_!,:; |") return LatexFallback.Unsupported
                }
            }
            i++
        }
        return if (groups == 0 && environmentStack.isEmpty()) null else LatexFallback.Malformed
    }

    fun acceptsDimensions(width: Int, height: Int): Boolean =
        width in 1..MAX_WIDTH && height in 1..MAX_HEIGHT &&
            width.toLong() * height.toLong() <= FORMULA_PIXELS

    fun acceptsScale(sizePx: Float, density: Float, fontScale: Float): Boolean =
        sizePx.isFinite() && sizePx in 1f..256f &&
            density.isFinite() && density > 0f && fontScale.isFinite() && fontScale > 0f
}
