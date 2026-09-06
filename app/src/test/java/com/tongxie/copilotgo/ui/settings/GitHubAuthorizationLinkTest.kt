package com.tongxie.copilotgo.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubAuthorizationLinkTest {
    @Test
    fun github_device_authorization_is_canonicalized() {
        listOf(
            "https://github.com/login/device",
            "https://github.com/login/device/",
            "HTTPS://GITHUB.COM/login/device",
            "https://github.com:443/login/device"
        ).forEach { assertEquals(GITHUB_DEVICE_AUTHORIZATION_URL, trustedGitHubAuthorizationUrl(it)) }
    }

    @Test
    fun arbitrary_schemes_authorities_paths_and_backend_parameters_are_rejected() {
        listOf(
            "", " https://github.com/login/device", "https://github.com/login/device\n",
            "http://github.com/login/device", "javascript:alert(1)", "intent://github.com/login/device",
            "file:///login/device", "content://github.com/login/device", "//github.com/login/device",
            "https://github.com.example/login/device", "https://notgithub.com/login/device",
            "https://github.com@other.example/login/device", "https://user@github.com/login/device",
            "https://github.com:444/login/device", "https://github.com:0443/login/device",
            "https://github.com./login/device", "https://github%2ecom/login/device",
            "https://github.com\\@other.example/login/device", "https://github.com/other",
            "https://github.com/%6cogin/device", "https://github.com/login/device?user_code=controlled",
            "https://github.com/login/device#fragment", "https://github.com/login/device?next=https://other.example",
            "https://github.com/登录/😀", "https://github.com/login/device" + "a".repeat(4096)
        ).forEach { assertNull(trustedGitHubAuthorizationUrl(it)) }
    }
}
