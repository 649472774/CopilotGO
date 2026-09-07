package com.tongxie.copilotgo.data.tools.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ToolResponseBoundsTest {
    @Test
    fun explicitGzipIsDecodedExactlyOnceAndWireHeadersRemainAvailable() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val bytes = gzip("fixture gzip response".toByteArray())
            fixture.server.enqueue(
                MockResponse().setBody(bytes).setHeader("Content-Type", "text/plain").setHeader("Content-Encoding", "gzip")
            )
            ToolHttpClient(fixture.provider()).withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) {
                assertEquals("gzip", it.headers["Content-Encoding"])
                assertEquals("fixture gzip response", it.source.readUtf8())
            }
            assertEquals("gzip", fixture.server.takeRequest().getHeader("Accept-Encoding"))
        }
    }

    @Test
    fun identityAndGzipBodiesAreBoundedEvenWithChunkedUnknownLength() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.provider())
            val random = ByteArray(1024).also { Random(7).nextBytes(it) }
            listOf(
                MockResponse().setChunkedBody(Buffer().write(random), 17).setHeader("Content-Type", "text/plain"),
                MockResponse().setChunkedBody(gzip(random), 17)
                    .setHeader("Content-Type", "application/json").setHeader("Content-Encoding", "gzip")
            ).forEach { response ->
                fixture.server.enqueue(response)
                expectNetworkFailure(ToolNetworkErrorCode.RESPONSE_TOO_LARGE) {
                    http.withResponse(
                        fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS,
                        limits = ToolNetworkLimits(maxCompressedBytes = 64, maxDecodedBytes = 4096)
                    ) { it.source.readByteArray() }
                }
            }
        }
    }

    @Test
    fun decompressedBombAndIdentityBodyCannotExceedDecodedBudget() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.provider())
            val body = "a".repeat(8192)
            val compressed = gzip(body.toByteArray())
            assertTrue(compressed.size < 256)
            listOf(
                MockResponse().setChunkedBody(compressed, 11).setHeader("Content-Encoding", "gzip"),
                MockResponse().setChunkedBody(body, 17)
            ).forEach { response ->
                fixture.server.enqueue(response.setHeader("Content-Type", "text/plain"))
                expectNetworkFailure(ToolNetworkErrorCode.RESPONSE_TOO_LARGE) {
                    http.withResponse(
                        fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS,
                        limits = ToolNetworkLimits(maxCompressedBytes = 16_384, maxDecodedBytes = 128)
                    ) { it.source.readByteArray() }
                }
            }
        }
    }

    @Test
    fun exactlyTheByteLimitSucceedsButOneMoreByteFailsWithoutExposingIt() {
        val source = BoundedToolSource(Buffer().writeUtf8("12345"), 5).buffer()
        assertEquals("12345", source.readUtf8())
        source.close()
        val beyond = BoundedToolSource(Buffer().writeUtf8("123456"), 5)
        val sink = Buffer()
        assertEquals(5L, beyond.read(sink, Long.MAX_VALUE))
        expectNetworkFailure(ToolNetworkErrorCode.RESPONSE_TOO_LARGE) { beyond.read(sink, 1) }
        assertEquals("12345", sink.readUtf8())
        beyond.close()
        val short = BoundedToolSource(Buffer().writeUtf8("12"), 10, expectedBytes = 3).buffer()
        expectNetworkFailure(ToolNetworkErrorCode.INVALID_RESPONSE) { short.readUtf8() }
        short.close()
    }

    @Test
    fun malformedTruncatedTrailingAndChecksumInvalidGzipAreSanitized() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.provider())
            val valid = gzip("fixture-secret".toByteArray()).readByteArray()
            val badCrc = valid.copyOf().apply { this[size - 8] = (this[size - 8].toInt() xor 1).toByte() }
            listOf(
                "fixture-secret not gzip".toByteArray(),
                valid.copyOf(valid.size - 4),
                valid + byteArrayOf(1),
                badCrc,
                valid + valid
            ).forEach { bytes ->
                fixture.server.enqueue(
                    MockResponse().setBody(Buffer().write(bytes))
                        .setHeader("Content-Type", "text/plain").setHeader("Content-Encoding", "gzip")
                )
                val failure = expectNetworkFailure(ToolNetworkErrorCode.INVALID_RESPONSE) {
                    http.withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) { it.source.readUtf8() }
                }
                assertFalse(failure.toString().contains("fixture-secret"))
            }
        }
    }

    @Test
    fun unsupportedOrStackedEncodingsAreNotTransparentlyDecoded(): Unit = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.provider())
            listOf("br", "deflate", "x-gzip", "gzip, identity", "", "gzip, gzip").forEach { encoding ->
                fixture.server.enqueue(fixture.response().setHeader("Content-Encoding", encoding))
                expectNetworkFailure(ToolNetworkErrorCode.UNSUPPORTED_CONTENT_ENCODING) {
                    http.withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) { it.source.readUtf8() }
                }
            }
            fixture.server.enqueue(fixture.response().addHeader("Content-Encoding", "gzip").addHeader("Content-Encoding", "identity"))
            expectNetworkFailure(ToolNetworkErrorCode.UNSUPPORTED_CONTENT_ENCODING) {
                http.withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) { it.source.readUtf8() }
            }
        }
    }

    @Test
    fun binaryMissingMalformedAndAmbiguousMediaTypesNeverReachTextConsumers() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.provider())
            val responses = listOf(
                MockResponse().setBody("fixture"),
                fixture.response().setHeader("Content-Type", "application/octet-stream"),
                fixture.response().setHeader("Content-Type", "image/png"),
                fixture.response().setHeader("Content-Type", "text/javascript"),
                fixture.response().setHeader("Content-Type", "not a media type"),
                fixture.response().addHeader("Content-Type", "application/json")
            )
            responses.forEach { response ->
                fixture.server.enqueue(response)
                expectNetworkFailure(ToolNetworkErrorCode.UNSUPPORTED_CONTENT_TYPE) {
                    http.withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) { it.source.readUtf8() }
                }
            }
        }
    }

    @Test
    fun emptyNotificationsAndHeadNeedNoContentTypeAndNeverAllocateFromLength() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.provider())
            listOf(200, 202, 204, 205).forEach { status ->
                fixture.server.enqueue(MockResponse().setResponseCode(status))
                http.withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) {
                    assertEquals(status, it.statusCode)
                    assertTrue(it.source.exhausted())
                }
            }
            fixture.server.enqueue(MockResponse().setHeader("Content-Length", Long.MAX_VALUE))
            http.withResponse(fixture.request().newBuilder().head().build(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) {
                assertTrue(it.source.exhausted())
            }
        }
    }

    @Test
    fun resetContentCannotExposeAnUnexpectedUntypedBody() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(205).setChunkedBody("unexpected", 2))
            expectNetworkFailure(ToolNetworkErrorCode.INVALID_RESPONSE) {
                ToolHttpClient(fixture.provider()).withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) {
                    it.source.readUtf8()
                }
            }
            Unit
        }
    }

    @Test
    fun advertisedOversizeIsRejectedBeforeTheCallbackWithoutReadingOrAllocatingTheBody() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            fixture.server.enqueue(fixture.response().setHeader("Content-Length", Long.MAX_VALUE))
            var callback = false
            expectNetworkFailure(ToolNetworkErrorCode.RESPONSE_TOO_LARGE) {
                ToolHttpClient(fixture.provider()).withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) {
                    callback = true
                    it.source.readByteArray()
                }
            }
            assertFalse(callback)
        }
    }

    @Test
    fun malformedAmbiguousAndTruncatedContentLengthsAreRejected() = runBlocking {
        ToolHttpsFixture().use { fixture ->
            val http = ToolHttpClient(fixture.provider())
            listOf(
                fixture.response().setHeader("Content-Length", "-1"),
                fixture.response().setHeader("Content-Length", "+7"),
                fixture.response().setHeader("Content-Length", "not-a-number"),
                fixture.response().setHeader("Content-Length", "99999999999999999999"),
                fixture.response().addHeader("Content-Length", "7"),
                MockResponse().setChunkedBody("fixture", 2).setHeader("Content-Type", "text/plain").setHeader("Content-Length", "7"),
                fixture.response("short").setHeader("Content-Length", "20").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
            ).forEach { response ->
                fixture.server.enqueue(response)
                expectNetworkFailure(ToolNetworkErrorCode.INVALID_RESPONSE) {
                    http.withResponse(fixture.request(), ToolNetworkPolicy.TRUSTED_LAN_HTTPS) { it.source.readUtf8() }
                }
            }
        }
    }

    private fun gzip(bytes: ByteArray): Buffer = Buffer().also { buffer ->
        GzipSink(buffer).buffer().use { it.write(bytes) }
    }
}
