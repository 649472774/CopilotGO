package com.tongxie.copilotgo.ui.markdown

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal object SafeMarkdownLink {
    const val MAX_CHARACTERS = 2_048

    fun destination(value: String): String? {
        if (value.isEmpty() || value.length > MAX_CHARACTERS ||
            value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }
        ) return null
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.schemeSpecificPart?.any { it.isISOControl() } == true) return null
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> {
                if (uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                    uri.port !in -1..65_535
                ) return null
            }
            "mailto" -> {
                val address = uri.rawSchemeSpecificPart.substringBefore('?')
                if (!uri.isOpaque || !address.contains('@') ||
                    address.startsWith('@') || address.endsWith('@') ||
                    address.contains('/') || address.contains('#')
                ) return null
            }
            else -> return null
        }
        return uri.toASCIIString()
    }
}
