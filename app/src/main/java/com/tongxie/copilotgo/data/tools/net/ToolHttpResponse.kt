package com.tongxie.copilotgo.data.tools.net

import okhttp3.Headers
import okhttp3.HttpUrl
import okio.BufferedSource

/**
 * A borrowed response. Read/parse [source] inside ToolHttpClient.withResponse's IO callback only.
 * The client closes and invalidates the source on every exit; neither response nor source may
 * be retained for deferred reading. Headers describe the wire representation, including gzip.
 */
class ToolHttpResponse(
    val statusCode: Int,
    val url: HttpUrl,
    val headers: Headers,
    val source: BufferedSource
) {
    override fun toString(): String = "ToolHttpResponse(statusCode=$statusCode, content=<redacted>)"
}
