package com.tongxie.copilotgo.ui.remote

import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.data.proxy.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteNetworkPolicyTest {
    private val config = ProxyConfig(enabled = true, host = "10.0.2.2", port = 7890)

    @Test
    fun explicit_system_mode_does_not_claim_to_apply_app_proxy() {
        val result = RemoteNetworkPolicy.decide(RemoteNetworkMode.SYSTEM, config, false, "unavailable", false)
        assertTrue(result is RemoteNetworkDecision.Ready)
        result as RemoteNetworkDecision.Ready
        assertEquals(RemoteProxyRoute.System, result.route)
        assertTrue(result.label.contains("独立"))
    }

    @Test
    fun placeholder_disabled_config_must_wait_for_durable_load() {
        val result = decide(ProxyConfig(enabled = false), initialized = false)
        assertTrue(result is RemoteNetworkDecision.Waiting)
    }

    @Test
    fun storage_error_blocks_without_echoing_details() {
        val result = decide(config, error = "private path or credential")
        assertTrue(result is RemoteNetworkDecision.Blocked)
        assertFalse(result.toString().contains("private path"))
    }

    @Test
    fun missing_config_blocks_instead_of_using_system_network() {
        assertTrue(decide(null) is RemoteNetworkDecision.Blocked)
    }

    @Test
    fun unsupported_override_blocks_enabled_proxy() {
        assertTrue(decide(config, supported = false) is RemoteNetworkDecision.Blocked)
    }

    @Test
    fun explicitly_disabled_app_proxy_uses_system_network() {
        val result = decide(config.copy(enabled = false), supported = false) as RemoteNetworkDecision.Ready
        assertEquals(RemoteProxyRoute.System, result.route)
        assertTrue(result.label.contains("已关闭"))
    }

    @Test
    fun credentialed_proxies_are_not_silently_downgraded() {
        listOf(
            config.copy(username = "name"), config.copy(password = "fixture"),
            config.copy(username = " ")
        ).forEach { assertTrue(decide(it) is RemoteNetworkDecision.Blocked) }
    }

    @Test
    fun http_and_socks_routes_have_no_direct_fallback() {
        val http = decide(config) as RemoteNetworkDecision.Ready
        val socks = decide(config.copy(type = ProxyType.SOCKS5)) as RemoteNetworkDecision.Ready
        assertEquals(RemoteProxyRoute.Override("http://10.0.2.2:7890"), http.route)
        assertEquals(RemoteProxyRoute.Override("socks://10.0.2.2:7890"), socks.route)
    }

    @Test
    fun ipv6_proxy_is_bracketed() {
        val result = decide(config.copy(host = "::1", port = 1080)) as RemoteNetworkDecision.Ready
        assertEquals(RemoteProxyRoute.Override("http://[::1]:1080"), result.route)
    }

    @Test
    fun malformed_endpoints_are_blocked() {
        listOf("", " example.com", "http://example.com", "user@example.com", "host/path", "host#tag", "host?x", "host\\x", "a..b")
            .forEach { assertTrue(it, decide(config.copy(host = it)) is RemoteNetworkDecision.Blocked) }
        listOf(-1, 0, 65_536).forEach { assertTrue(decide(config.copy(port = it)) is RemoteNetworkDecision.Blocked) }
    }

    private fun decide(
        value: ProxyConfig?,
        initialized: Boolean = true,
        error: String? = null,
        supported: Boolean = true
    ) = RemoteNetworkPolicy.decide(RemoteNetworkMode.APP_PROXY, value, initialized, error, supported)
}
