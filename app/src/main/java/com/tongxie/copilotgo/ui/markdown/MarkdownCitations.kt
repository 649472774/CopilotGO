package com.tongxie.copilotgo.ui.markdown

import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalMarkdownCitations = staticCompositionLocalOf<Map<String, String>> { emptyMap() }
private val citationToken = Regex("\\[(S[1-9][0-9]{0,8})]")

/** Link only plain citation tokens supplied by the caller's actual-source map. */
internal fun resolveMarkdownCitations(
    parts: List<MarkdownInline>,
    sources: Map<String, String>
): List<MarkdownInline> {
    if (sources.isEmpty()) return parts
    return parts.flatMap { part ->
        if (part !is MarkdownInline.Text || !part.allowsCitations ||
            part.destination != null || InlineFormat.Code in part.formats
        ) {
            listOf(part)
        } else {
            buildList {
                var offset = 0
                for (match in citationToken.findAll(part.text)) {
                    val destination = sources[match.groupValues[1]]?.let(SafeMarkdownLink::destination)
                        ?.takeIf { it.startsWith("https://", ignoreCase = true) } ?: continue
                    if (match.range.first > offset) {
                        add(part.copy(text = part.text.substring(offset, match.range.first)))
                    }
                    add(part.copy(text = match.value, destination = destination))
                    offset = match.range.last + 1
                }
                if (offset < part.text.length || isEmpty()) add(part.copy(text = part.text.substring(offset)))
            }
        }
    }
}
