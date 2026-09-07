package com.tongxie.copilotgo.ui.components

import android.content.ActivityNotFoundException
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.ui.markdown.InlineContent
import com.tongxie.copilotgo.ui.markdown.InlineFormat
import com.tongxie.copilotgo.ui.markdown.LatexRender
import com.tongxie.copilotgo.ui.markdown.MarkdownBlock
import com.tongxie.copilotgo.ui.markdown.MarkdownInline
import com.tongxie.copilotgo.ui.markdown.SafeMarkdownLink
import com.tongxie.copilotgo.ui.markdown.TableAlignment
import com.tongxie.copilotgo.ui.markdown.rememberLatexRender
import com.tongxie.copilotgo.ui.markdown.rememberMarkdownDocument
import kotlinx.coroutines.CancellationException

/**
 * Native, selectable Markdown. Parsing is bounded and off Main, with conflated streaming updates.
 * The caller must copy/export the ORIGINAL markdown, not this renderer's explicitly marked preview.
 * Do not wrap this component in another SelectionContainer.
 */
@Composable
fun SimpleMarkdownText(
    markdown: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    onFeedback: (String) -> Unit = {}
) {
    val document = rememberMarkdownDocument(markdown, isStreaming)
    val textColor = if (style.color == Color.Unspecified) LocalContentColor.current else style.color
    val bodyStyle = style.copy(color = textColor)
    SelectionContainer(modifier) {
        // Deliberately wrap short messages, rather than forcing every bubble to its maximum width.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val parsed = document
            if (parsed == null && markdown.isNotEmpty()) {
                Text("正在整理内容…", style = MaterialTheme.typography.bodySmall)
            }
            parsed?.entries?.forEach { entry ->
                key(entry.start, entry.block::class) {
                    when (val block = entry.block) {
                        is MarkdownBlock.Paragraph -> RichParagraph(block.content, bodyStyle, onFeedback)
                        is MarkdownBlock.Heading -> {
                            val headingStyle = when (block.level) {
                                1 -> MaterialTheme.typography.headlineSmall
                                2 -> MaterialTheme.typography.titleLarge
                                3 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            }.copy(color = textColor, fontWeight = FontWeight.SemiBold)
                            RichParagraph(
                                block.content, headingStyle, onFeedback,
                                Modifier.padding(top = 8.dp, bottom = 2.dp).semantics { heading() }
                            )
                        }
                        is MarkdownBlock.ListItem -> Row(
                            Modifier.padding(start = (8 * block.depth.coerceAtMost(4)).dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(block.marker, style = bodyStyle, modifier = Modifier.widthIn(min = 24.dp, max = 80.dp))
                            RichParagraph(
                                block.content, bodyStyle, onFeedback,
                                Modifier.weight(1f, fill = false)
                            )
                        }
                        is MarkdownBlock.Quote -> Column(
                            Modifier.padding(start = (4 * block.depth.coerceAtMost(4)).dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "引用",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            RichParagraph(
                                block.content,
                                bodyStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                                onFeedback
                            )
                        }
                        is MarkdownBlock.Code -> MarkdownCode(block, onFeedback)
                        is MarkdownBlock.Table -> MarkdownTable(block, bodyStyle, onFeedback)
                        is MarkdownBlock.Math -> LatexFormula(
                            block.source,
                            onFeedback = onFeedback,
                            style = MaterialTheme.typography.titleMedium.copy(color = textColor)
                        )
                        is MarkdownBlock.Literal -> {
                            block.explanation?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            PlainChunks(block.chunks, bodyStyle.copy(fontFamily = FontFamily.Monospace))
                        }
                        MarkdownBlock.Rule -> HorizontalDivider(
                            Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
            if (parsed?.simplified == true) {
                Text(
                    "为保持阅读流畅，部分格式已按原文显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor
                )
            }
            if (parsed?.isPreview == true) {
                Text(
                    "内容较长，这里仅显示部分预览。请通过消息操作复制或导出全文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                    modifier = Modifier.testTag("markdown-preview-notice")
                )
            }
        }
    }
}

@Composable
private fun RichParagraph(
    contents: List<InlineContent>,
    style: TextStyle,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        contents.forEachIndexed { index, content ->
            key(index) { RichText(content, style, onFeedback) }
        }
    }
}

@Composable
private fun RichText(content: InlineContent, style: TextStyle, onFeedback: (String) -> Unit) {
    if (content.parts.none { it is MarkdownInline.Math }) {
        InlineText(content.parts, emptyMap(), style, onFeedback)
        return
    }
    val renders = LinkedHashMap<MarkdownInline.Math, LatexRender?>()
    content.parts.forEachIndexed { index, part ->
        if (part is MarkdownInline.Math) {
            key(index, part.source) {
                renders[part] = rememberLatexRender(latexRequest(part.source, false, style))
            }
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints {
        val maximumWidth = with(density) { maxWidth.toPx() }
        val lineHeight = with(density) {
            if (style.lineHeight.isSp) style.lineHeight.toPx() else MaterialTheme.typography.bodyLarge.lineHeight.toPx()
        }
        Column {
            var first = 0
            content.parts.forEachIndexed { index, part ->
                val image = if (part is MarkdownInline.Math) renders[part] as? LatexRender.Image else null
                if (part is MarkdownInline.Math && image != null &&
                    (image.width > maximumWidth || image.height > lineHeight * 3f)
                ) {
                    if (first < index) InlineText(content.parts.subList(first, index), renders, style, onFeedback)
                    LatexContent(part.source, false, image, style, onFeedback)
                    first = index + 1
                }
            }
            if (first < content.parts.size) {
                InlineText(content.parts.subList(first, content.parts.size), renders, style, onFeedback)
            }
            renders.values.filterIsInstance<LatexRender.Source>().firstOrNull()?.let {
                Text(
                    it.reason.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = style.color
                )
            }
        }
    }
}

@Composable
private fun InlineText(
    parts: List<MarkdownInline>,
    renders: Map<MarkdownInline.Math, LatexRender?>,
    style: TextStyle,
    onFeedback: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    val feedback by rememberUpdatedState(onFeedback)
    val linkStyles = remember(colors.primary, colors.secondaryContainer, colors.onSecondaryContainer) {
        TextLinkStyles(
            style = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
            focusedStyle = SpanStyle(color = colors.onSecondaryContainer, background = colors.secondaryContainer),
            pressedStyle = SpanStyle(color = colors.onSecondaryContainer, background = colors.secondaryContainer)
        )
    }
    val inlineImages = LinkedHashMap<String, InlineTextContent>()
    parts.forEachIndexed { index, part ->
        if (part is MarkdownInline.Math) {
            val image = renders[part] as? LatexRender.Image
            if (image != null) {
                inlineImages["math-$index"] = InlineTextContent(
                    Placeholder(
                        with(density) { image.width.toDp().toSp() },
                        with(density) { image.height.toDp().toSp() },
                        PlaceholderVerticalAlign.TextCenter
                    )
                ) {
                    Image(
                        image.bitmap,
                        contentDescription = "公式：${part.source}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
    val annotated = remember(parts, renders, colors, linkStyles, uriHandler) {
        buildAnnotatedString {
            parts.forEachIndexed { index, part ->
                when (part) {
                    is MarkdownInline.Math -> {
                        if (renders[part] is LatexRender.Image) {
                            appendInlineContent("math-$index", part.original)
                        } else {
                            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(part.original) }
                        }
                    }
                    is MarkdownInline.Text -> {
                        val formats = part.formats
                        val code = InlineFormat.Code in formats
                        val span = SpanStyle(
                            fontWeight = if (InlineFormat.Bold in formats) FontWeight.Bold else null,
                            fontStyle = if (InlineFormat.Italic in formats) FontStyle.Italic else null,
                            fontFamily = if (code) FontFamily.Monospace else null,
                            background = if (code) colors.surfaceContainerHighest else Color.Unspecified,
                            color = if (code) colors.onSurface else Color.Unspecified,
                            textDecoration = if (InlineFormat.Strike in formats) TextDecoration.LineThrough else null
                        )
                        withStyle(span) {
                            val url = part.destination
                            if (url != null) {
                                withLink(
                                    LinkAnnotation.Url(
                                        url,
                                        styles = linkStyles,
                                        linkInteractionListener = {
                                            val destination = SafeMarkdownLink.destination(url)
                                            if (destination == null) {
                                                feedback("此链接类型暂不支持。")
                                            } else {
                                                val failure = try {
                                                    uriHandler.openUri(destination)
                                                    null
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (_: ActivityNotFoundException) {
                                                    "暂时无法打开链接，请复制链接后重试。"
                                                } catch (_: IllegalArgumentException) {
                                                    "暂时无法打开链接，请复制链接后重试。"
                                                } catch (_: SecurityException) {
                                                    "暂时无法打开链接，请复制链接后重试。"
                                                }
                                                failure?.let { feedback(it) }
                                            }
                                        }
                                    )
                                ) { append(part.text) }
                            } else append(part.text)
                        }
                    }
                }
            }
        }
    }
    Text(annotated, style = style, inlineContent = inlineImages, softWrap = true)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkdownCode(block: MarkdownBlock.Code, onFeedback: (String) -> Unit) {
    var wrap by rememberSaveable { mutableStateOf(true) }
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.clip(MaterialTheme.shapes.small).background(colors.surfaceContainer),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DisableSelection {
            Column(
                Modifier.background(colors.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PlainChunks(
                    if (block.language.isBlank()) listOf("代码") else block.languageChunks,
                    MaterialTheme.typography.labelLarge.copy(color = colors.onSurfaceVariant)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            val message = when (copyText(clipboard, block.code)) {
                                TextCopyResult.Copied -> "已复制代码"
                                TextCopyResult.TooLong -> "代码较长，请通过消息操作导出全文。"
                                TextCopyResult.Unavailable -> "暂时无法复制，请稍后重试。"
                            }
                            onFeedback(message)
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("markdown-code-copy")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("复制代码", modifier = Modifier.weight(1f, fill = false))
                    }
                    FilterChip(
                        selected = wrap,
                        onClick = { wrap = !wrap },
                        label = { Text("自动换行") },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("markdown-code-wrap")
                    )
                }
                if (!wrap) Text(
                    "横向阅读；超长行仍会折行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
        val bodyModifier = if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())
        Column(bodyModifier.padding(12.dp)) {
            // Even horizontal reading has a finite layout width; malicious lines cannot overflow
            // Compose's constraint representation. Copy always retains the exact original code.
            PlainChunks(
                block.chunks,
                MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurface
                ),
                Modifier.widthIn(max = 2_048.dp)
            )
        }
        if (!block.closed) {
            DisableSelection {
                Text(
                    "代码块尚未闭合",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PlainChunks(chunks: List<String>, style: TextStyle, modifier: Modifier = Modifier) {
    Column(modifier) {
        chunks.forEachIndexed { index, chunk ->
            key(index) {
                Text(chunk.removeSuffix("\n").removeSuffix("\r"), style = style, softWrap = true)
            }
        }
    }
}

@Composable
private fun MarkdownTable(block: MarkdownBlock.Table, style: TextStyle, onFeedback: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val columnWidth = (160f * density.fontScale.coerceIn(1f, 1.75f)).dp
    val tableWidth = columnWidth * block.header.size
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DisableSelection {
            Text(
                "表格 · 可横向滚动",
                style = MaterialTheme.typography.labelMedium,
                color = style.color
            )
        }
        Column(
            Modifier.horizontalScroll(rememberScrollState()).semantics {
                collectionInfo = CollectionInfo(block.rows.size + 1, block.header.size)
            }.testTag("markdown-table")
        ) {
            (listOf(block.header) + block.rows).forEachIndexed { rowIndex, row ->
                Row(
                    Modifier.width(tableWidth).background(
                        if (rowIndex == 0) colors.surfaceContainerHigh else colors.surfaceContainer
                    )
                ) {
                    row.forEachIndexed { columnIndex, cell ->
                        val alignment = when (block.alignments[columnIndex]) {
                            TableAlignment.Start -> TextAlign.Start
                            TableAlignment.Center -> TextAlign.Center
                            TableAlignment.End -> TextAlign.End
                        }
                        Column(
                            Modifier.width(columnWidth).padding(12.dp).semantics {
                                collectionItemInfo = CollectionItemInfo(rowIndex, 1, columnIndex, 1)
                                if (rowIndex == 0) heading()
                            },
                            horizontalAlignment = when (block.alignments[columnIndex]) {
                                TableAlignment.Start -> Alignment.Start
                                TableAlignment.Center -> Alignment.CenterHorizontally
                                TableAlignment.End -> Alignment.End
                            }
                        ) {
                            RichText(
                                cell,
                                style.copy(
                                    color = colors.onSurface,
                                    textAlign = alignment,
                                    fontWeight = if (rowIndex == 0) FontWeight.SemiBold else style.fontWeight
                                ),
                                onFeedback
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.width(tableWidth), color = colors.outlineVariant)
            }
        }
    }
}
