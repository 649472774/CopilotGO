package com.tongxie.copilotgo.data.tools.net

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Installed on the real route selector, not consulted as a preflight. CONNECT and SOCKS target
 * resolution can happen remotely without OkHttp's Dns. Until an in-route guarded tunnel exists,
 * reject any non-direct candidate (even when a selector also offers DIRECT as a fallback).
 */
internal class DirectOnlyToolProxySelector(private val delegate: ProxySelector) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        val proxies = try {
            delegate.select(uri)
        } catch (_: Exception) {
            networkFailure(ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE)
        }
        if (proxies.isNullOrEmpty() || proxies.any { it.type() != Proxy.Type.DIRECT }) {
            networkFailure(ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE)
        }
        return proxies.toList()
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
        // A failed guarded request must not change a selector's state into a direct fallback.
    }
}

internal class GuardedToolDns(
    private val delegate: Dns,
    private val url: HttpUrl,
    private val policy: ToolNetworkPolicy,
    private val assertUsable: () -> Unit
) : Dns {
    @Volatile
    private var approved: Set<InetAddress> = emptySet()
    private val literal = ToolUrlGuard.literalAddress(url.host)

    override fun lookup(hostname: String): List<InetAddress> {
        assertUsable()
        if (!hostname.equals(url.host, ignoreCase = true)) networkFailure(ToolNetworkErrorCode.UNSAFE_DNS)
        val addresses = try {
            delegate.lookup(hostname).also {
                if (it.size !in 1..64) networkFailure(ToolNetworkErrorCode.UNSAFE_DNS)
            }.toList()
        } catch (_: Exception) {
            networkFailure(ToolNetworkErrorCode.UNSAFE_DNS)
        }
        assertUsable()
        if (addresses.any { !ToolUrlGuard.isAllowedAddress(it, policy) }) networkFailure(ToolNetworkErrorCode.UNSAFE_DNS)
        approved = addresses.toSet()
        return addresses
    }

    fun assertAddress(address: InetAddress?) {
        assertUsable()
        if (address == null || !ToolUrlGuard.isAllowedAddress(address, policy) ||
            if (literal != null) address != literal else address !in approved
        ) networkFailure(ToolNetworkErrorCode.UNSAFE_DNS)
    }
}

/** Checks literal routes too: OkHttp may optimize literal addresses without invoking Dns. */
internal class ToolConnectionListener(private val dns: GuardedToolDns, private val port: Int) : EventListener() {
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        if (proxy.type() != Proxy.Type.DIRECT) networkFailure(ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE)
        if (inetSocketAddress.port != port) networkFailure(ToolNetworkErrorCode.UNSAFE_DNS)
        dns.assertAddress(inetSocketAddress.address)
    }

    fun assertConnection(connection: Connection) {
        if (connection.route().proxy.type() != Proxy.Type.DIRECT) {
            networkFailure(ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE)
        }
        val route = connection.route().socketAddress
        val remote = connection.socket().remoteSocketAddress as? InetSocketAddress
            ?: networkFailure(ToolNetworkErrorCode.UNSAFE_DNS)
        if (route.port != port || remote.port != port) networkFailure(ToolNetworkErrorCode.UNSAFE_DNS)
        dns.assertAddress(route.address)
        dns.assertAddress(remote.address)
    }
}
