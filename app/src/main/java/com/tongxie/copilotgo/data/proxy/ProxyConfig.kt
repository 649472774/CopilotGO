package com.tongxie.copilotgo.data.proxy

import kotlinx.serialization.Serializable

enum class ProxyType { HTTP, SOCKS5 }

@Serializable
data class ProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "127.0.0.1",
    val port: Int = 7890,
    val username: String = "",
    val password: String = ""
) {
    val requiresAuth: Boolean
        get() = username.isNotBlank()

    fun isValid(): Boolean {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty() || trimmedHost != host || port !in 1..65535) return false
        if (trimmedHost.contains(Regex("\\s")) || trimmedHost.contains("/") || trimmedHost.contains("://")) {
            return false
        }
        return trimmedHost.split('.').all { it.isNotEmpty() }
    }
}
