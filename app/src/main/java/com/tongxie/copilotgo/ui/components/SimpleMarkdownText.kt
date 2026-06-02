package com.tongxie.copilotgo.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
/**
 * 纯 Compose 实现的轻量 Markdown 渲染器。
 *
 * 设计目标：替换 `dev.jeziellago:compose-markdown`，因为该库内部用 AndroidView 包 Markwon TextView，
 * 每次 LazyColumn recomposition 都重新 inflate TextView，加上 AppCompat theme 不兼容警告，
 * 在历史消息较多 + 用户触发 state 变化时，会让主线程卡 13s+ 触发 ANR / 闪退（v0.1.5 实测）。
 *
 * 当前支持：
 * - 标题 # ## ###
 * - 代码块 ``` ```  （等宽 + 灰底 + 横向滚动）
 * - 行内代码 `code` （等宽 + 灰底）
 * - 加粗 **bold**
 * - 斜体 *italic*  （不与 ** 冲突）
 * - 链接 [text](url) — 仅显示为下划线文本（不可点击，保持简单）
 * - 无序列表 - * +
 * - 有序列表 1. 2.
 *
 * 关键：所有解析结果由 remember(markdown) 缓存，recomposition 时不重新 parse。
 */
@Composable
fun SimpleMarkdownText(
    markdown: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val codeBg = if (MaterialTheme.colorScheme.background.luminanceSimple() < 0.5f) {
        Color(0xFF2D2D2D)
    } else {
        Color(0xFFEEEEEE)
    }
    val inlineCodeBg = if (MaterialTheme.colorScheme.background.luminanceSimple() < 0.5f) {
        Color(0xFF3A3A3A)
    } else {
        Color(0xFFE0E0E0)
    }
    val textColor = MaterialTheme.colorScheme.onSurface
    val codeHeaderBg = if (codeBg.luminanceSimple() < 0.5f) {
        Color(0xFF252525)
    } else {
        Color(0xFFE2E2E2)
    }

    // 注意：不要在这里 fillMaxWidth()，否则会把外层气泡（widthIn(max=320.dp)）撑到上限，
    // 导致 user 短消息也变成一个超宽矩形。让 Column wrap content，气泡自适应内容宽度。
    Column(modifier = modifier) {
        blocks.forEachIndexed { idx, block ->
            when (block) {
                is MdBlock.Heading -> {
                    TextOrLatexLine(
                        text = block.text,
                        textStyle = style.copy(
                            fontSize = when (block.level) {
                                1 -> 22.sp
                                2 -> 19.sp
                                else -> 17.sp
                            },
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        ),
                        inlineCodeBg = codeBg,
                        textColor = textColor,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                is MdBlock.CodeBlock -> {
                    val context = LocalContext.current
                    val clipboardManager = LocalClipboardManager.current
                    var wrap by remember { mutableStateOf(false) }
                    val horizontalScrollState = rememberScrollState()
                    val highlightedCode = remember(block.code, wrap, codeBg) {
                        buildCodeAnnotated(block.code, codeBg)
                    }
                    val bodyModifier = Modifier
                        .background(codeBg)
                        .padding(8.dp)
                        .then(
                            if (wrap) Modifier.fillMaxWidth()
                            else Modifier.horizontalScroll(horizontalScrollState)
                        )

                    Column(
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(codeBg)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(codeHeaderBg)
                                .padding(start = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = block.lang.ifBlank { "code" },
                                modifier = Modifier.weight(1f),
                                style = style.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = textColor.copy(alpha = 0.65f)
                                )
                            )
                            TextButton(onClick = { wrap = !wrap }) {
                                Text(
                                    text = if (wrap) "不换行" else "换行",
                                    fontSize = 12.sp,
                                    color = textColor.copy(alpha = 0.75f)
                                )
                            }
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(block.code))
                                    Toast.makeText(context, "已复制代码", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "复制代码",
                                    tint = textColor.copy(alpha = 0.75f)
                                )
                            }
                        }
                        Text(
                            text = highlightedCode,
                            modifier = bodyModifier,
                            softWrap = wrap,
                            style = style.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = textColor
                            )
                        )
                    }
                }
                is MdBlock.ListItem -> {
                    TextOrLatexLine(
                        text = "${block.bullet} ${block.text}",
                        textStyle = style.copy(color = textColor),
                        inlineCodeBg = codeBg,
                        textColor = textColor,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                    )
                }
                is MdBlock.Paragraph -> {
                    if (block.text.isNotBlank()) {
                        TextOrLatexLine(
                            text = block.text,
                            textStyle = style.copy(color = textColor),
                            inlineCodeBg = codeBg,
                            textColor = textColor
                        )
                    }
                }
                is MdBlock.LatexDisplay -> {
                    // 块公式 $$...$$ —— 独立成块，display style 字号大一号
                    LatexFormula(
                        latex = block.latex,
                        display = true,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                is MdBlock.Blank -> {
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (idx < blocks.lastIndex) Spacer(Modifier.height(2.dp))
        }
    }
}

/**
 * 标题 / 段落 / 列表项的统一渲染入口：把行内 $...$ 切出来，文本段走 Markdown inline 解析，
 * LaTeX 段调 LatexFormula(display=false)。当一段同时含文字 + 公式时，自动拆成 Column 多行显示
 * （不嵌入 InlineTextContent，避免预测量/对齐复杂度）。
 */
@Composable
private fun TextOrLatexLine(
    text: String,
    textStyle: TextStyle,
    inlineCodeBg: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val segs = remember(text) { splitInlineLatex(text) }
    if (segs.size == 1 && segs[0] is InlineSeg.PlainText) {
        Text(
            text = buildInlineAnnotated((segs[0] as InlineSeg.PlainText).text, inlineCodeBg, textColor),
            style = textStyle,
            modifier = modifier
        )
    } else {
        Column(modifier = modifier) {
            segs.forEach { seg ->
                when (seg) {
                    is InlineSeg.PlainText -> if (seg.text.isNotBlank()) {
                        Text(
                            text = buildInlineAnnotated(seg.text, inlineCodeBg, textColor),
                            style = textStyle
                        )
                    }
                    is InlineSeg.LatexInline -> LatexFormula(latex = seg.latex, display = false)
                }
            }
        }
    }
}

private sealed class InlineSeg {
    data class PlainText(val text: String) : InlineSeg()
    data class LatexInline(val latex: String) : InlineSeg()
}

/**
 * 把文本按 $...$ 切成 [Text|Latex|Text|Latex|...] 段。
 * 规则：
 * - $ 必须成对，且 $...$ 内不能跨 $$（$$ 在块级 parser 已优先识别消费）
 * - 不识别空对 $$（已被 block parser 拦截）
 * - 未闭合的 $ 保留原文（流式中半截公式不会爆）
 */
private fun splitInlineLatex(text: String): List<InlineSeg> {
    if (!text.contains('$')) return listOf(InlineSeg.PlainText(text))
    val out = mutableListOf<InlineSeg>()
    val buf = StringBuilder()
    var i = 0
    val len = text.length
    while (i < len) {
        val c = text[i]
        if (c == '$') {
            // 寻找下一个 $（不能是 $$）
            var j = i + 1
            // 如果是 $$ 直接当普通文本（block 公式应该已经被上层拆出）
            if (j < len && text[j] == '$') {
                buf.append("\$\$")
                i = j + 1
                continue
            }
            // 简单约束：LaTeX 内容里若包含未转义的 $ 就停（找到第一个）
            while (j < len && text[j] != '$') j++
            if (j < len && j > i + 1) {
                // 闭合
                if (buf.isNotEmpty()) {
                    out.add(InlineSeg.PlainText(buf.toString()))
                    buf.clear()
                }
                out.add(InlineSeg.LatexInline(text.substring(i + 1, j)))
                i = j + 1
                continue
            }
            // 未闭合，原样保留
            buf.append('$')
            i++
        } else {
            buf.append(c)
            i++
        }
    }
    if (buf.isNotEmpty()) out.add(InlineSeg.PlainText(buf.toString()))
    return out
}

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val lang: String, val code: String) : MdBlock()
    data class ListItem(val bullet: String, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class LatexDisplay(val latex: String) : MdBlock()
    object Blank : MdBlock()
}

private fun buildCodeAnnotated(code: String, codeBg: Color): AnnotatedString {
    return runCatching {
        val darkCode = codeBg.luminanceSimple() < 0.5f
        val keywordColor = if (darkCode) Color(0xFFC792EA) else Color(0xFF5E35B1)
        val stringColor = if (darkCode) Color(0xFFC3E88D) else Color(0xFF2E7D32)
        val commentColor = if (darkCode) Color(0xFF9E9E9E) else Color(0xFF757575)
        val keywords = setOf(
            "for", "while", "if", "else", "return", "fun", "val", "var", "def", "class",
            "function", "const", "let", "import", "public", "private", "void", "int", "String",
            "true", "false", "null", "None"
        )

        buildAnnotatedString {
            var i = 0
            while (i < code.length) {
                val c = code[i]
                when {
                    c == '/' && i + 1 < code.length && code[i + 1] == '/' -> {
                        val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                        withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) {
                            append(code.substring(i, end))
                        }
                        i = end
                    }
                    c == '#' -> {
                        val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                        withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) {
                            append(code.substring(i, end))
                        }
                        i = end
                    }
                    c == '\'' || c == '"' -> {
                        val quote = c
                        var end = i + 1
                        var escaped = false
                        while (end < code.length) {
                            val ch = code[end]
                            if (!escaped && ch == quote) {
                                end++
                                break
                            }
                            escaped = !escaped && ch == '\\'
                            if (ch != '\\') escaped = false
                            end++
                        }
                        withStyle(SpanStyle(color = stringColor)) {
                            append(code.substring(i, end.coerceAtMost(code.length)))
                        }
                        i = end.coerceAtMost(code.length)
                    }
                    c.isLetter() || c == '_' -> {
                        var end = i + 1
                        while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                        val word = code.substring(i, end)
                        if (word in keywords) {
                            withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.SemiBold)) {
                                append(word)
                            }
                        } else {
                            append(word)
                        }
                        i = end
                    }
                    else -> {
                        append(c)
                        i++
                    }
                }
            }
        }
    }.getOrElse { AnnotatedString(code) }
}

private fun parseMarkdownBlocks(markdown: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = markdown.split('\n')
    var i = 0
    val paragraphBuf = StringBuilder()

    fun flushParagraph() {
        if (paragraphBuf.isNotEmpty()) {
            out.add(MdBlock.Paragraph(paragraphBuf.toString().trimEnd()))
            paragraphBuf.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        // 代码块
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim()
            val codeBuf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (codeBuf.isNotEmpty()) codeBuf.append('\n')
                codeBuf.append(lines[i])
                i++
            }
            if (i < lines.size) i++ // skip closing ```
            out.add(MdBlock.CodeBlock(lang, codeBuf.toString()))
            continue
        }

        // 块公式 $$...$$（可单行 $$...$$ 也可跨多行；流式中未闭合也优雅处理）
        if (trimmed.startsWith("$$")) {
            flushParagraph()
            val rest = trimmed.removePrefix("$$")
            // 单行同时含闭合 $$
            val singleLineClose = rest.indexOf("$$")
            if (singleLineClose >= 0) {
                val latex = rest.substring(0, singleLineClose).trim()
                if (latex.isNotEmpty()) out.add(MdBlock.LatexDisplay(latex))
                i++
                continue
            }
            // 跨行：收集直到下一行的 $$
            val latexBuf = StringBuilder(rest)
            i++
            var closed = false
            while (i < lines.size) {
                val l = lines[i]
                val idx2 = l.indexOf("$$")
                if (idx2 >= 0) {
                    if (latexBuf.isNotEmpty()) latexBuf.append('\n')
                    latexBuf.append(l.substring(0, idx2))
                    i++
                    closed = true
                    break
                } else {
                    if (latexBuf.isNotEmpty()) latexBuf.append('\n')
                    latexBuf.append(l)
                    i++
                }
            }
            val latex = latexBuf.toString().trim()
            if (latex.isNotEmpty()) {
                out.add(MdBlock.LatexDisplay(latex))
            }
            // 未闭合（流式中常见）也加进去显示
            if (!closed && latex.isEmpty()) {
                // 空 $$ 块，跳过
            }
            continue
        }

        // 标题
        val headingMatch = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            val level = headingMatch.groupValues[1].length
            out.add(MdBlock.Heading(level.coerceAtMost(3), headingMatch.groupValues[2]))
            i++
            continue
        }

        // 列表
        val bulletMatch = Regex("^([-*+])\\s+(.*)$").find(trimmed)
        if (bulletMatch != null) {
            flushParagraph()
            out.add(MdBlock.ListItem("•", bulletMatch.groupValues[2]))
            i++
            continue
        }
        val orderedMatch = Regex("^(\\d+)[.)]\\s+(.*)$").find(trimmed)
        if (orderedMatch != null) {
            flushParagraph()
            out.add(MdBlock.ListItem("${orderedMatch.groupValues[1]}.", orderedMatch.groupValues[2]))
            i++
            continue
        }

        // 空行
        if (trimmed.isEmpty()) {
            flushParagraph()
            // 不连续多个 Blank
            if (out.isNotEmpty() && out.last() !is MdBlock.Blank) {
                out.add(MdBlock.Blank)
            }
            i++
            continue
        }

        // 段落（合并相邻行）
        if (paragraphBuf.isNotEmpty()) paragraphBuf.append(' ')
        paragraphBuf.append(line.trim())
        i++
    }
    flushParagraph()
    return out
}

/**
 * 行内格式：**bold**、*italic*、`code`、[text](url)
 */
private fun buildInlineAnnotated(
    text: String,
    codeBg: Color,
    textColor: Color
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val len = text.length
    while (i < len) {
        // 行内代码 `code`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                        color = textColor
                    )
                ) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // **bold**
        if (i + 1 < len && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end > i + 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = textColor)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        // *italic*  （非 **）
        if (text[i] == '*' && (i + 1 >= len || text[i + 1] != '*')) {
            val end = text.indexOf('*', i + 1)
            if (end > i && (end + 1 >= len || text[end + 1] != '*')) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = textColor)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // [text](url)
        if (text[i] == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket > i && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen > closeBracket) {
                    val linkText = text.substring(i + 1, closeBracket)
                    withStyle(
                        SpanStyle(
                            color = Color(0xFF1E88E5),
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        )
                    ) {
                        append(linkText)
                    }
                    i = closeParen + 1
                    continue
                }
            }
        }
        append(text[i])
        i++
    }
}

private fun Color.luminanceSimple(): Float {
    return 0.2126f * red + 0.7152f * green + 0.0722f * blue
}
