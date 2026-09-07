package com.tongxie.copilotgo.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteNavigationPolicyTest {
    @Test
    fun exact_https_origin_allows_copilot_and_github_login() {
        assertEquals(RemoteDestination.COPILOT, classify("https://github.com/copilot").destination)
        assertEquals(RemoteDestination.COPILOT, classify("https://github.com/copilot/c/thread?q=hello#end").destination)
        assertEquals(RemoteDestination.GITHUB, classify("https://github.com/login?return_to=%2Fcopilot").destination)
        assertEquals(RemoteDestination.GITHUB, classify("https://github.com/copilot-not-a-chat").destination)
    }

    @Test
    fun case_and_default_port_are_normalized() {
        val result = classify("https://GITHUB.COM:443/copilot")
        assertTrue(result.isEmbedded)
        assertEquals("https://github.com", result.origin)
        assertEquals("https://github.com/copilot", result.url)
    }

    @Test
    fun origin_spoofing_is_never_embedded() {
        listOf(
            "https://github.com.evil.example/copilot",
            "https://evilgithub.com/copilot",
            "https://github.com./copilot",
            "https://github.com:8443/copilot",
            "https://githubusercontent.com/copilot",
            "https://github.com@evil.example/copilot",
            "https://evil.example@github.com/copilot",
            "https://@github.com/copilot",
            "https://%00github.com/copilot"
        ).forEach { assertFalse(it, classify(it).isEmbedded) }
    }

    @Test
    fun user_information_is_rejected_even_for_an_external_browser() {
        assertEquals(RemoteDestination.BLOCKED, classify("https://name:credential@outside.example").destination)
        assertEquals(RemoteDestination.BLOCKED, classify("https://@outside.example").destination)
    }

    @Test
    fun external_address_displays_origin_without_query_or_fragment() {
        val result = classify("https://outside.example:8443/sign-in?state=private#private")
        assertEquals(RemoteDestination.EXTERNAL, result.destination)
        assertEquals("https://outside.example:8443", result.origin)
        assertTrue(result.url.contains("state=private"))
    }

    @Test
    fun international_host_is_displayed_as_ascii_not_a_github_lookalike() {
        val result = classify("https://g\u0456thub.com/copilot")
        assertEquals(RemoteDestination.EXTERNAL, result.destination)
        assertTrue(result.origin.contains("xn--"))
    }

    @Test
    fun ipv6_origin_includes_brackets() {
        assertEquals("https://[2001:db8::1]:444", classify("https://[2001:db8::1]:444/file").origin)
    }

    @Test
    fun unsafe_schemes_and_malformed_urls_are_blocked() {
        listOf(
            "http://github.com/copilot", "javascript:alert(1)", "file:///private/file",
            "content://app.private/item", "data:text/html,hello", "blob:https://github.com/id",
            "intent://github.com/#Intent;scheme=https;end", "ftp://github.com",
            "//github.com/copilot", "https://github.com\\@outside.example",
            " https://github.com", "https://github.com\n", "https://github.com/%",
            "", "https://" + "a".repeat(17_000)
        ).forEach { assertEquals(it, RemoteDestination.BLOCKED, classify(it).destination) }
        assertEquals(RemoteDestination.BLOCKED, RemoteNavigationPolicy.classify(null).destination)
    }

    private fun classify(url: String) = RemoteNavigationPolicy.classify(url)
}
