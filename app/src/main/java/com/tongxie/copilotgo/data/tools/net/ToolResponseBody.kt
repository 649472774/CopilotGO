package com.tongxie.copilotgo.data.tools.net

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.GzipSource
import okio.Source
import okio.buffer
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale

internal class BoundedToolSource(
    source: Source,
    private val maxBytes: Long,
    private val expectedBytes: Long? = null
) : ForwardingSource(source) {
    private var received = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0) { "Invalid byte count" }
        if (byteCount == 0L) return 0L
        val remaining = maxBytes - received
        if (remaining == 0L) {
            val probe = Buffer()
            if (super.read(probe, 1) != -1L) {
                networkFailure(
                    if (expectedBytes == received) ToolNetworkErrorCode.INVALID_RESPONSE
                    else ToolNetworkErrorCode.RESPONSE_TOO_LARGE
                )
            }
            checkComplete()
            return -1
        }
        val count = super.read(sink, minOf(byteCount, remaining))
        if (count == -1L) checkComplete() else received += count
        return count
    }

    private fun checkComplete() {
        if (expectedBytes != null && received != expectedBytes) networkFailure(ToolNetworkErrorCode.INVALID_RESPONSE)
    }
}

internal class ToolResponseBody(
    response: Response,
    headers: Headers,
    limits: ToolNetworkLimits,
    private val assertReadable: () -> Unit
) {
    @Volatile
    private var open = true
    val source: BufferedSource

    init {
        val noBody = response.request.method == "HEAD" || response.code in setOf(204, 205, 304)
        val length = contentLength(headers)
        val encoding = contentEncoding(headers)
        if (!noBody && length != null &&
            (length > limits.maxCompressedBytes || encoding == "identity" && length > limits.maxDecodedBytes)
        ) networkFailure(ToolNetworkErrorCode.RESPONSE_TOO_LARGE)
        val body = response.body ?: networkFailure(ToolNetworkErrorCode.INVALID_RESPONSE)
        val compressed = BoundedToolSource(
            body.source(), if (noBody) 0 else limits.maxCompressedBytes, if (noBody) 0 else length
        )
        val gzip = !noBody && encoding == "gzip"
        val decoded: Source = if (gzip) GzipSource(compressed.buffer()) else compressed
        val bounded = BoundedToolSource(decoded, limits.maxDecodedBytes)
        val types = headers.values("Content-Type")
        val type = types.singleOrNull()?.toMediaTypeOrNull()
        val supported = type != null && when {
            type.type == "application" && (type.subtype == "json" || type.subtype.endsWith("+json")) -> true
            type.type == "application" && type.subtype == "xhtml+xml" -> true
            type.type == "text" && type.subtype in setOf("plain", "html", "event-stream") -> true
            else -> false
        }
        source = object : ForwardingSource(bounded) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                checkReadable()
                if (byteCount == 0L) return 0
                if (!noBody && types.isNotEmpty() && !supported) {
                    networkFailure(ToolNetworkErrorCode.UNSUPPORTED_CONTENT_TYPE)
                }
                return try {
                    // Empty 202/HEAD/204 responses do not need a media type. Missing media types
                    // on a nonempty body fail before any byte is exposed to a text/JSON consumer.
                    if (!noBody && types.isEmpty()) {
                        val probe = Buffer()
                        if (super.read(probe, 1) == -1L) -1L
                        else networkFailure(ToolNetworkErrorCode.UNSUPPORTED_CONTENT_TYPE)
                    } else {
                        super.read(sink, byteCount)
                    }
                } catch (e: IOException) {
                    checkReadable()
                    throw sanitizedReadFailure(e, gzip)
                }
            }
        }.buffer()
    }

    fun close() {
        open = false
        source.close()
    }

    private fun checkReadable() {
        if (!open) networkFailure(ToolNetworkErrorCode.NETWORK_ERROR)
        assertReadable()
    }

    private fun contentLength(headers: Headers): Long? {
        val lengths = headers.values("Content-Length")
        val transfers = headers.values("Transfer-Encoding")
        if (transfers.isNotEmpty() &&
            (lengths.isNotEmpty() || transfers.size != 1 || !transfers[0].equals("chunked", ignoreCase = true))
        ) networkFailure(ToolNetworkErrorCode.INVALID_RESPONSE)
        if (lengths.isEmpty()) return null
        val value = lengths.singleOrNull() ?: networkFailure(ToolNetworkErrorCode.INVALID_RESPONSE)
        if (value.isEmpty() || value.length > 19 || value.any { it !in '0'..'9' }) {
            networkFailure(ToolNetworkErrorCode.INVALID_RESPONSE)
        }
        return value.toLongOrNull() ?: networkFailure(ToolNetworkErrorCode.INVALID_RESPONSE)
    }

    private fun contentEncoding(headers: Headers): String {
        val values = headers.values("Content-Encoding")
        if (values.isEmpty()) return "identity"
        val value = values.singleOrNull()?.lowercase(Locale.ROOT)
        if (value != "gzip" && value != "identity") networkFailure(ToolNetworkErrorCode.UNSUPPORTED_CONTENT_ENCODING)
        return value
    }
}

private fun sanitizedReadFailure(error: IOException, gzip: Boolean): ToolNetworkException =
    ToolNetworkException(
        when (error) {
            is ToolNetworkException -> error.code
            is SocketTimeoutException, is SocketException -> ToolNetworkErrorCode.NETWORK_ERROR
            is ProtocolException, is EOFException -> ToolNetworkErrorCode.INVALID_RESPONSE
            else -> if (gzip) ToolNetworkErrorCode.INVALID_RESPONSE else ToolNetworkErrorCode.NETWORK_ERROR
        }
    )
