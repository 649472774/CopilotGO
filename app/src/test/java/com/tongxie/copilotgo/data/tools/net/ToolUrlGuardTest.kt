package com.tongxie.copilotgo.data.tools.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class ToolUrlGuardTest {
    @Test
    fun acceptsCanonicalPublicHttpsWithoutResolvingNames() {
        listOf(
            "https://example.com/path?q=one",
            "HTTPS://EXAMPLE.COM:443/path",
            "https://bücher.de/",
            "https://8.8.8.8/",
            "https://[2606:4700:4700::1111]/",
            "https://[::ffff:8.8.8.8]/"
        ).forEach { assertTrue(ToolUrlGuard.parse(it).isHttps) }
        assertEquals("example.com", ToolUrlGuard.parse("HTTPS://EXAMPLE.COM/").host)
    }

    @Test
    fun rejectsSchemeUserInfoFragmentWhitespaceInvalidAuthorityAndOversizedUrls() {
        listOf(
            "", "http://example.com/", "file:///etc/passwd", "ftp://example.com/",
            "//example.com/", "https:example.com", "https:///example.com", " https://example.com/",
            "https://example.com/\nsecret", "https://example.com/\u007f", "https://example.com/a b",
            "https://user:fixture-secret@example.com/", "https://@example.com/",
            "https://fixture-secret@example.com/", "https://%40@example.com/",
            "https://example.com/#", "https://example.com/#fixture-secret",
            "https://example.com\\@127.0.0.1/", "https://%65xample.com/",
            "https://example.com./", "https://-example.com/", "https://example..com/",
            "https://a_b.example.com/", "https://example.com:0/", "https://example.com:65536/",
            "https://[fe80::1%25eth0]/", "https://[not-ipv6]/",
            "https://example.com/" + "a".repeat(ToolUrlGuard.MAX_URL_CHARS)
        ).forEach { value ->
            val failure = expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_URL) { ToolUrlGuard.parse(value) }
            assertFalse(failure.toString().contains("fixture-secret"))
        }
    }

    @Test
    fun rejectsInetAtonObfuscationInBothPolicies() {
        val hosts = listOf(
            "2130706433", "017700000001", "0x7f000001", "0X7F000001", "127.1", "127.0.1",
            "0177.0.0.1", "127.000.000.001", "0x7f.0x0.0x0.0x1", "127.0.0.0x1",
            "0", "0x0", "4294967295", "999.1.1.1", "1.2.3.4.5", "08.08.08.08"
        )
        ToolNetworkPolicy.entries.forEach { policy ->
            hosts.forEach { host ->
                expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_URL) {
                    ToolUrlGuard.parse("https://$host/", policy)
                }
            }
        }
    }

    @Test
    fun publicNamesCannotTargetLocalOrMetadataSuffixes() {
        listOf(
            "localhost", "a.localhost", "localhost.localdomain", "router", "service.local",
            "service.internal", "service.intranet", "service.lan", "host.home.arpa",
            "name.test", "invalid", "name.onion", "1.0.0.127.in-addr.arpa",
            "metadata.google.internal", "metadata.goog", "instance-data.ec2.internal"
        ).forEach {
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_URL) { ToolUrlGuard.parse("https://$it/") }
        }
        listOf("metadata", "metadata.google.internal", "metadata.goog", "metadata.tencentyun.com").forEach {
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_URL) {
                ToolUrlGuard.parse("https://$it/", ToolNetworkPolicy.TRUSTED_LAN_HTTPS)
            }
        }
    }

    @Test
    fun publicIpv4ExcludesPrivateLocalReservedDocumentationAndCloudPlatformAddresses() {
        listOf(
            "0.0.0.0", "0.1.2.3", "10.0.0.1", "100.64.0.1", "100.100.100.200", "100.127.255.255",
            "127.0.0.1", "127.255.255.254", "169.254.1.2", "169.254.169.254", "169.254.170.2",
            "172.16.0.1", "172.31.255.254", "192.0.0.9", "192.0.2.1", "192.88.99.1", "192.168.1.1",
            "198.18.0.1", "198.19.255.254", "198.51.100.1", "203.0.113.1", "224.0.0.1",
            "239.255.255.250", "240.0.0.1", "255.255.255.255", "168.63.129.16"
        ).forEach { literal ->
            assertFalse(literal, ToolUrlGuard.isPublicAddress(address(literal)))
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_URL) { ToolUrlGuard.parse("https://$literal/") }
        }
        listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "192.169.1.1", "198.20.1.1").forEach {
            assertTrue(it, ToolUrlGuard.isPublicAddress(address(it)))
        }
    }

    @Test
    fun publicIpv6RejectsTransitionLocalMulticastAndDocumentationSpace() {
        listOf(
            "::", "::1", "::127.0.0.1", "::ffff:127.0.0.1", "::ffff:169.254.169.254",
            "::ffff:168.63.129.16", "fc00::1", "fd12::1", "fd00:ec2::254",
            "fe80::1", "fec0::1", "ff02::1", "64:ff9b::7f00:1", "64:ff9b:1::a00:1",
            "2001::1", "2001:2::1", "2001:10::1", "2001:20::1", "2001:db8::1",
            "2002:7f00:1::1", "3fff::1", "5f00::1"
        ).forEach {
            assertFalse(it, ToolUrlGuard.isPublicAddress(address(it)))
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_URL) { ToolUrlGuard.parse("https://[$it]/") }
        }
        listOf("2001:4860:4860::8888", "2606:4700:4700::1111").forEach {
            assertTrue(it, ToolUrlGuard.isPublicAddress(address(it)))
        }
        val mapped = ByteArray(16).apply {
            this[10] = 0xff.toByte()
            this[11] = 0xff.toByte()
            this[12] = 127
            this[15] = 1
        }
        assertFalse(ToolUrlGuard.isPublicAddress(Inet6Address.getByAddress(null, mapped, -1)))
        mapped[12] = 8
        mapped[13] = 8
        mapped[14] = 8
        mapped[15] = 8
        assertTrue(ToolUrlGuard.isPublicAddress(Inet6Address.getByAddress(null, mapped, -1)))
        val globalWithScope = Inet6Address.getByAddress(null, address("2606:4700::1111").address, 2)
        assertFalse(ToolUrlGuard.isPublicAddress(globalWithScope))
    }

    @Test
    fun trustedLanIsNarrowAndStillHttpsOnly() {
        listOf(
            "https://localhost/", "https://printer/", "https://service.local/",
            "https://service.internal/", "https://127.0.0.1/", "https://10.0.0.1/",
            "https://172.16.0.1/", "https://192.168.1.1/", "https://[::1]/", "https://[fd12::1]/"
        ).forEach { assertTrue(ToolUrlGuard.parse(it, ToolNetworkPolicy.TRUSTED_LAN_HTTPS).isHttps) }
        listOf(
            "http://localhost/", "https://0.0.0.0/", "https://169.254.169.254/",
            "https://100.100.100.200/", "https://168.63.129.16/", "https://224.0.0.1/",
            "https://192.0.2.1/", "https://[fe80::1]/", "https://[::]/",
            "https://[ff02::1]/", "https://[fd00:ec2::254]/", "https://[64:ff9b::a00:1]/"
        ).forEach {
            expectNetworkFailure(ToolNetworkErrorCode.UNSAFE_URL) {
                ToolUrlGuard.parse(it, ToolNetworkPolicy.TRUSTED_LAN_HTTPS)
            }
        }
    }

    @Test
    fun limitsRejectUnboundedAndNonPositiveValues() {
        assertEquals(1_048_576L, ToolNetworkLimits().maxCompressedBytes)
        val invalid = listOf<() -> Unit>(
            { ToolNetworkLimits(maxCompressedBytes = 0) },
            { ToolNetworkLimits(maxCompressedBytes = -1) },
            { ToolNetworkLimits(maxCompressedBytes = Long.MAX_VALUE) },
            { ToolNetworkLimits(maxDecodedBytes = 0) },
            { ToolNetworkLimits(maxDecodedBytes = 16L * 1024 * 1024 + 1) },
            { ToolNetworkLimits(callTimeoutMillis = 0) },
            { ToolNetworkLimits(callTimeoutMillis = 120_001) },
            { ToolNetworkLimits(maxRedirects = -1) },
            { ToolNetworkLimits(maxRedirects = 11) }
        )
        invalid.forEach { create ->
            try {
                create()
                fail("Invalid limit was accepted")
            } catch (_: IllegalArgumentException) {
                // Expected: constructor validation does not include the supplied value.
            }
        }
    }

    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)
}

internal inline fun expectNetworkFailure(
    expected: ToolNetworkErrorCode,
    block: () -> Unit
): ToolNetworkException {
    try {
        block()
        throw AssertionError("Expected guarded network failure: $expected")
    } catch (error: ToolNetworkException) {
        assertEquals(expected, error.code)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        return error
    }
}
