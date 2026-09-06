package com.tongxie.copilotgo.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.ui.markdown.LatexRender
import com.tongxie.copilotgo.ui.markdown.LatexRequest
import com.tongxie.copilotgo.ui.markdown.MarkdownLimits
import com.tongxie.copilotgo.ui.markdown.rememberLatexRender
import com.tongxie.copilotgo.ui.markdown.safePrefixEnd

/** The enclosing Markdown renderer owns selection; bitmap lifetimes are managed by a bounded pool. */
@Composable
fun LatexFormula(
    latex: String,
    display: Boolean = true,
    modifier: Modifier = Modifier,
    onFeedback: (String) -> Unit = {},
    style: TextStyle = if (display) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
) {
    val request = latexRequest(latex, display, style)
    LatexContent(latex, display, rememberLatexRender(request), style, onFeedback, modifier)
}

@Composable
internal fun latexRequest(source: String, display: Boolean, style: TextStyle): LatexRequest {
    val density = LocalDensity.current
    val fontSize = if (style.fontSize.isSp) style.fontSize else MaterialTheme.typography.bodyLarge.fontSize
    val sizePx = with(density) { fontSize.toPx() }
    val color = if (style.color == androidx.compose.ui.graphics.Color.Unspecified) {
        LocalContentColor.current
    } else style.color
    return remember(source, display, color, sizePx, density.density, density.fontScale) {
        LatexRequest(source, display, color.toArgb(), sizePx, density.density, density.fontScale)
    }
}

@Composable
internal fun LatexContent(
    latex: String,
    display: Boolean,
    render: LatexRender?,
    style: TextStyle,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var sourceVisible by rememberSaveable(latex) { mutableStateOf(false) }
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val image = render as? LatexRender.Image
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (image != null && !sourceVisible) {
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Image(
                    bitmap = image.bitmap,
                    contentDescription = "公式：$latex",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(
                        with(density) { image.width.toDp() },
                        with(density) { image.height.toDp() }
                    )
                )
            }
        } else {
            val end = safePrefixEnd(latex, MarkdownLimits.INLINE_CHARACTERS)
            val preview = latex.substring(0, end)
            val delimiter = if (display) "\$\$" else "\$"
            Text(
                text = "$delimiter$preview$delimiter",
                style = style.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.semantics { contentDescription = "公式源码：$preview" }
            )
            val explanation = when {
                end < latex.length -> "公式过长，仅显示部分源码；可通过消息操作导出全文。"
                render is LatexRender.Source -> render.reason.explanation
                render == null -> "正在排版公式…"
                else -> null
            }
            if (explanation != null) {
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = style.color
                )
            }
        }
        DisableSelection {
            if (image != null) {
                TextButton(
                    onClick = { sourceVisible = !sourceVisible },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(if (sourceVisible) "显示公式" else "查看公式源码")
                }
            } else {
                TextButton(
                    onClick = {
                        val message = when (copyText(clipboard, latex)) {
                            TextCopyResult.Copied -> "已复制公式源码"
                            TextCopyResult.TooLong -> "公式源码较长，请通过消息操作导出全文。"
                            TextCopyResult.Unavailable -> "暂时无法复制，请稍后重试。"
                        }
                        onFeedback(message)
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("复制公式源码")
                }
            }
        }
    }
}
