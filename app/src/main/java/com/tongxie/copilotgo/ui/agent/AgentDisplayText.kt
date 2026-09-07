package com.tongxie.copilotgo.ui.agent

import com.tongxie.copilotgo.ui.markdown.SafeMarkdownLink

internal data class AgentTextPreview(val text: String, val truncated: Boolean)

/** Escape directional/control characters rather than letting untrusted text disguise an action. */
internal fun agentTextPreview(value: String, maximumCharacters: Int): AgentTextPreview {
    require(maximumCharacters > 0)
    val output = StringBuilder(minOf(value.length, maximumCharacters))
    var offset = 0
    while (offset < value.length) {
        val character = value[offset]
        val escaped = when {
            (character.isISOControl() && character != '\n' && character != '\t') ||
                character in '\u202a'..'\u202e' || character in '\u2066'..'\u2069' ||
                character == '\u200e' || character == '\u200f' || character == '\u061c' ->
                "\\u${character.code.toString(16).padStart(4, '0')}"
            Character.isHighSurrogate(character) && offset + 1 < value.length &&
                Character.isLowSurrogate(value[offset + 1]) -> value.substring(offset, offset + 2)
            else -> character.toString()
        }
        if (output.length + escaped.length > maximumCharacters) break
        output.append(escaped)
        offset += if (escaped.length == 2 && Character.isHighSurrogate(character)) 2 else 1
    }
    return AgentTextPreview(output.toString(), offset < value.length)
}

internal fun agentSourceDestination(value: String): String? =
    SafeMarkdownLink.destination(value)?.takeIf { it.startsWith("https://", ignoreCase = true) }
