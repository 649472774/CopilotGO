package com.tongxie.copilotgo.data.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyConfigTest {

    private fun cfg(
        host: String = "127.0.0.1",
        port: Int = 7890,
        username: String = "",
        password: String = ""
    ) = ProxyConfig(
        enabled = true,
        type = ProxyType.HTTP,
        host = host,
        port = port,
        username = username,
        password = password
    )

    @Test
    fun requiresAuth_only_when_username_present() {
        assertFalse(cfg(username = "").requiresAuth)
        assertFalse(cfg(username = "   ").requiresAuth)
        assertTrue(cfg(username = "user").requiresAuth)
    }

    @Test
    fun valid_hosts_pass() {
        assertTrue(cfg(host = "127.0.0.1").isValid())
        assertTrue(cfg(host = "10.0.2.2").isValid())
        assertTrue(cfg(host = "localhost").isValid())
        assertTrue(cfg(host = "proxy.example.com").isValid())
    }

    @Test
    fun blank_host_invalid() {
        assertFalse(cfg(host = "").isValid())
        assertFalse(cfg(host = "   ").isValid())
    }

    @Test
    fun host_with_whitespace_scheme_or_path_invalid() {
        assertFalse(cfg(host = "127.0.0.1 ").isValid())
        assertFalse(cfg(host = "http://127.0.0.1").isValid())
        assertFalse(cfg(host = "127.0.0.1/path").isValid())
        assertFalse(cfg(host = "a b").isValid())
        assertFalse(cfg(host = "a..b").isValid())
    }

    @Test
    fun out_of_range_ports_invalid() {
        assertFalse(cfg(port = 0).isValid())
        assertFalse(cfg(port = -1).isValid())
        assertFalse(cfg(port = 65536).isValid())
        assertTrue(cfg(port = 1).isValid())
        assertTrue(cfg(port = 65535).isValid())
    }
}
