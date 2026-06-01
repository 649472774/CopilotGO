package com.tongxie.copilotgo.data.proxy

import kotlinx.serialization.Serializable

enum class ProxyType { HTTP, SOCKS5 }

@Serializable
data class ProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "127.0.0.1",
    val port: Int = 7890
) {
    fun isValid(): Boolean = host.isNotBlank() && port in 1..65535
}
