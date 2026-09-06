package com.tongxie.copilotgo.ui.settings

import java.net.URI
import java.net.URISyntaxException

const val GITHUB_DEVICE_AUTHORIZATION_URL = "https://github.com/login/device"

fun trustedGitHubAuthorizationUrl(value: String): String? {
    if (value.length > 2048) return null
    val uri = try {
        URI(value)
    } catch (_: URISyntaxException) {
        return null
    }
    return GITHUB_DEVICE_AUTHORIZATION_URL.takeIf {
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawAuthority.equals(
                if (uri.port == 443) "github.com:443" else "github.com",
                ignoreCase = true
            ) &&
            (uri.rawPath == "/login/device" || uri.rawPath == "/login/device/") &&
            uri.rawQuery == null && uri.rawFragment == null
    }
}
