package com.tongxie.copilotgo.ui.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URISyntaxException

internal enum class RemoteDestination { COPILOT, GITHUB, EXTERNAL, BLOCKED }

internal data class RemoteAddress(
    val destination: RemoteDestination,
    val url: String,
    val origin: String
) {
    val isEmbedded: Boolean
        get() = destination == RemoteDestination.COPILOT || destination == RemoteDestination.GITHUB
}

internal object RemoteNavigationPolicy {
    fun classify(rawUrl: String?): RemoteAddress {
        val blocked = RemoteAddress(RemoteDestination.BLOCKED, "", "不支持的地址")
        if (rawUrl.isNullOrBlank() || rawUrl.length > 16_384) return blocked
        if (rawUrl.any { it.isISOControl() || it == '\\' } || rawUrl != rawUrl.trim()) return blocked

        val uri = try {
            URI(rawUrl)
        } catch (_: URISyntaxException) {
            return blocked
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return blocked
        val url = rawUrl.toHttpUrlOrNull() ?: return blocked
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return blocked
        val origin = origin(url)
        if (url.host != "github.com" || url.port != 443) {
            return RemoteAddress(RemoteDestination.EXTERNAL, url.toString(), origin)
        }
        val copilot = url.encodedPath == "/copilot" || url.encodedPath.startsWith("/copilot/")
        return RemoteAddress(
            if (copilot) RemoteDestination.COPILOT else RemoteDestination.GITHUB,
            url.toString(),
            origin
        )
    }

    private fun origin(url: HttpUrl): String {
        val host = if (':' in url.host) "[${url.host}]" else url.host
        val port = if (url.port == 443) "" else ":${url.port}"
        return "https://$host$port"
    }
}
