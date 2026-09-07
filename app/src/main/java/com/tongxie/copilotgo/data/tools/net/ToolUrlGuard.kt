package com.tongxie.copilotgo.data.tools.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Syntax/literal checks only; this object never resolves a DNS name. ToolHttpClient separately
 * validates every address returned by the resolver that OkHttp actually uses to connect.
 */
object ToolUrlGuard {
    internal const val MAX_URL_CHARS = 8192

    fun parse(value: String, policy: ToolNetworkPolicy = ToolNetworkPolicy.PUBLIC_HTTPS): HttpUrl {
        if (value.length !in 1..MAX_URL_CHARS ||
            !value.startsWith("https://", ignoreCase = true) ||
            value.any { it <= ' ' || it == '\u007f' || it.isWhitespace() || it == '\\' || it == '#' }
        ) unsafeUrl()
        val authority = value.substring(8).substringBefore('/').substringBefore('?')
        // Check before HttpUrl can normalize empty userinfo or percent-encoded hosts away.
        if (authority.isEmpty() || '@' in authority || '%' in authority) unsafeUrl()
        val url = value.toHttpUrlOrNull() ?: unsafeUrl()
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
            unsafeUrl()
        }
        validateHost(url.host, policy)
        return url
    }

    fun isPublicAddress(address: InetAddress): Boolean {
        if (address is Inet6Address && address.scopeId != 0) return false
        val bytes = address.address
        val ipv4 = embeddedIpv4(bytes)
        if (ipv4 != null) return publicIpv4(ipv4)
        if (bytes.size != 16 || forbiddenMetadata(bytes)) return false
        val b = bytes.map { it.toInt() and 0xff }
        // Only globally allocated unicast, excluding transition/protocol and documentation space.
        if (b[0] and 0xe0 != 0x20) return false
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] <= 0x01) return false // 2001::/23
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) return false
        if (b[0] == 0x20 && b[1] == 0x02) return false // 6to4 may embed a private IPv4 address.
        if (b[0] == 0x3f && b[1] == 0xff && b[2] and 0xf0 == 0) return false // 3fff::/20
        return true
    }

    internal fun isAllowedAddress(address: InetAddress, policy: ToolNetworkPolicy): Boolean {
        if (isPublicAddress(address)) return true
        if (policy != ToolNetworkPolicy.TRUSTED_LAN_HTTPS ||
            address is Inet6Address && address.scopeId != 0
        ) return false
        val bytes = address.address
        val ipv4 = embeddedIpv4(bytes)
        if (ipv4 != null) {
            val a = ipv4[0].toInt() and 0xff
            val b = ipv4[1].toInt() and 0xff
            return a == 10 || a == 127 || a == 172 && b in 16..31 || a == 192 && b == 168
        }
        if (bytes.size != 16 || forbiddenMetadata(bytes)) return false
        val loopback = bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()
        return loopback || bytes[0].toInt() and 0xfe == 0xfc
    }

    internal fun validateHost(host: String, policy: ToolNetworkPolicy) {
        if (host.length !in 1..253 || host.endsWith('.') || '%' in host ||
            METADATA_HOSTS.any { host == it || host.endsWith(".$it") }
        ) unsafeUrl()
        val literal = literalAddress(host)
        if (literal != null) {
            if (!isAllowedAddress(literal, policy)) unsafeUrl()
            return
        }
        val labels = host.split('.')
        // Never delegate legacy inet_aton forms (integer, hex, octal or shortened IPv4) to DNS.
        if (labels.all { NUMERIC_LABEL.matches(it) } || labels.last().all { it in '0'..'9' }) unsafeUrl()
        if (labels.any { it.length !in 1..63 || !HOST_LABEL.matches(it) }) unsafeUrl()
        if (policy == ToolNetworkPolicy.PUBLIC_HTTPS &&
            (labels.size < 2 || LOCAL_SUFFIXES.any { host == it || host.endsWith(".$it") })
        ) unsafeUrl()
    }

    internal fun literalAddress(host: String): InetAddress? {
        if (':' in host) {
            // HttpUrl has already validated/canonicalized IPv6. A colon literal never uses DNS.
            if (host.any { it !in "0123456789abcdefABCDEF:" }) unsafeUrl()
            return try {
                InetAddress.getByName(host)
            } catch (_: Exception) {
                unsafeUrl()
            }
        }
        if (host.any { it !in '0'..'9' && it != '.' }) return null
        val octets = host.split('.')
        if (octets.size != 4 || octets.any {
                it.isEmpty() || it.length > 3 || it.length > 1 && it[0] == '0' ||
                    (it.toIntOrNull() ?: -1) !in 0..255
            }
        ) unsafeUrl()
        return InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray())
    }

    private fun embeddedIpv4(bytes: ByteArray): ByteArray? = when {
        bytes.size == 4 -> bytes
        bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte() -> bytes.copyOfRange(12, 16)
        else -> null
    }

    private fun publicIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        val d = bytes[3].toInt() and 0xff
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 100 && b in 64..127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 192 && b == 0 && (c == 0 || c == 2) -> false
            a == 192 && b == 88 && c == 99 -> false
            a == 198 && b in 18..19 -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            a == 168 && b == 63 && c == 129 && d == 16 -> false // Azure platform/metadata endpoint.
            else -> true
        }
    }

    private fun forbiddenMetadata(bytes: ByteArray): Boolean =
        bytes.size == 16 && bytes[0] == 0xfd.toByte() && bytes[1] == 0.toByte() &&
            bytes[2] == 0x0e.toByte() && bytes[3] == 0xc2.toByte() // AWS fd00:ec2::/32.

    private fun unsafeUrl(): Nothing = networkFailure(ToolNetworkErrorCode.UNSAFE_URL)

    private val NUMERIC_LABEL = Regex("(?:[0-9]+|0[xX][0-9a-fA-F]+)")
    private val HOST_LABEL = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
    private val LOCAL_SUFFIXES = setOf(
        "localhost", "local", "internal", "intranet", "lan", "home", "home.arpa",
        "localdomain", "test", "invalid", "onion", "arpa"
    )
    private val METADATA_HOSTS = setOf(
        "metadata", "instance-data", "metadata.google.internal", "metadata.goog",
        "instance-data.ec2.internal", "metadata.azure.internal", "metadata.aws.internal",
        "metadata.tencentyun.com"
    )
}
