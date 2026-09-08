package com.tongxie.copilotgo.data.tools.net

import com.tongxie.copilotgo.data.auth.withResponse
import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.ProtocolException
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Credential-isolated, non-replaying HTTPS transport. Explicit request credentials must belong to
 * this tool endpoint, never to the GitHub/Copilot account. `hasCredentials` also covers session IDs
 * and non-header credentials, and forbids cross-origin redirects even if the header looks harmless.
 *
 * Uses provider readiness before each hop, its exact proxy policy and its actual DNS resolver.
 * CONNECT/SOCKS (including any non-direct ProxySelector candidate) fail closed, never fall back.
 * A fresh private pool per hop and HTTP/1.1 prevent pooled/coalesced connections skipping DNS checks.
 * TLS trust, hostname verification and certificate pinning are not weakened or overridden.
 *
 * Callback and assertCurrent failures belong to the caller and retain their identity; transport
 * failures are sanitized. All callbacks and body reads run inside Call.withResponse's IO lifetime.
 */
class ToolHttpClient(private val provider: HttpClientProvider) {
    suspend fun <T> withResponse(
        request: Request,
        policy: ToolNetworkPolicy = ToolNetworkPolicy.PUBLIC_HTTPS,
        limits: ToolNetworkLimits = ToolNetworkLimits(),
        allowRedirects: Boolean = false,
        hasCredentials: Boolean = false,
        assertCurrent: () -> Unit = {},
        block: suspend (ToolHttpResponse) -> T
    ): T {
        val requestSent = AtomicBoolean(false)
        return try {
            withContext(Dispatchers.IO) {
                try {
                    withTimeout(limits.callTimeoutMillis) {
                        exchange(request, policy, limits, allowRedirects, hasCredentials, assertCurrent, requestSent, block)
                    }
                } catch (e: CallerFailure) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    // Preserve dispatch uncertainty while removing raw causes/suppressed transport errors.
                    throw ToolNetworkException(
                        when (e) {
                            is ToolNetworkException -> e.code
                            is ProtocolException -> ToolNetworkErrorCode.INVALID_RESPONSE
                            else -> ToolNetworkErrorCode.NETWORK_ERROR
                        },
                        requestSent.get() || (e as? ToolNetworkException)?.requestMayHaveBeenSent == true
                    )
                }
            }
        } catch (e: CallerFailure) {
            // Unwrap on the caller's dispatcher, after coroutine stack-trace recovery boundaries.
            throw e.original
        }
    }

    private suspend fun <T> exchange(
        request: Request,
        policy: ToolNetworkPolicy,
        limits: ToolNetworkLimits,
        allowRedirects: Boolean,
        hasCredentials: Boolean,
        assertCurrent: () -> Unit,
        requestSent: AtomicBoolean,
        block: suspend (ToolHttpResponse) -> T
    ): T {
        val context = currentCoroutineContext()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.callTimeoutMillis)
        val initialUrl = ToolUrlGuard.parse(request.url.toString(), policy)
        var next = isolatedRequest(request, initialUrl, hasCredentials)
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            callerCheck(assertCurrent)
            // This applies the latest proxy snapshot, not merely the provider's initial builder.
            provider.awaitReady()
            callerCheck(assertCurrent)
            val base = provider.client
            val result = hop(next, base, policy, limits, deadline, assertCurrent, requestSent) { response, headers, cancel ->
                if (response.code in 300..399 && response.code != 304) {
                    HopResult.Redirect(
                        redirectRequest(next, response.code, headers, policy, limits, redirects, allowRedirects, hasCredentials)
                    )
                } else {
                    val leasedBody = ToolResponseBody(response, headers, limits) {
                        context.ensureActive()
                        callerCheck(assertCurrent)
                        if (provider.client !== base) networkFailure(ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE)
                        if (System.nanoTime() - deadline >= 0) networkFailure(ToolNetworkErrorCode.NETWORK_ERROR)
                    }
                    var failed = false
                    try {
                        callerCheck(assertCurrent)
                        val value = try {
                            block(ToolHttpResponse(response.code, response.request.url, headers, leasedBody.source))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ToolNetworkException) {
                            throw e
                        } catch (e: CallerFailure) {
                            throw e
                        } catch (e: Exception) {
                            throw CallerFailure(e)
                        }
                        callerCheck(assertCurrent)
                        HopResult.Value(value)
                    } catch (e: Throwable) {
                        failed = true
                        throw e
                    } finally {
                        cancel()
                        if (failed) {
                            try {
                                leasedBody.close()
                            } catch (_: Exception) {
                                // Preserve the original caller/cancellation failure, without raw
                                // close exceptions appearing as suppressed errors destined for UI.
                            }
                        } else {
                            leasedBody.close()
                        }
                    }
                }
            }
            when (result) {
                is HopResult.Value -> return result.value
                is HopResult.Redirect -> {
                    next = result.request
                    redirects++
                }
            }
        }
    }

    private suspend fun <T> hop(
        request: Request,
        base: OkHttpClient,
        policy: ToolNetworkPolicy,
        limits: ToolNetworkLimits,
        deadline: Long,
        assertCurrent: () -> Unit,
        requestSent: AtomicBoolean,
        block: suspend (okhttp3.Response, Headers, () -> Unit) -> T
    ): T {
        if (base.proxy != null && base.proxy!!.type() != Proxy.Type.DIRECT) {
            networkFailure(ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE)
        }
        val context = currentCoroutineContext()
        val active = AtomicBoolean(true)
        val assertUsable = {
            if (!active.get() || !context.isActive || System.nanoTime() - deadline >= 0) {
                networkFailure(ToolNetworkErrorCode.NETWORK_ERROR)
            }
            if (provider.client !== base) networkFailure(ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE)
        }
        val dns = GuardedToolDns(base.dns, request.url, policy, assertUsable)
        val connectionListener = ToolConnectionListener(dns, request.url.port)
        val pool = ConnectionPool(0, 1, TimeUnit.SECONDS)
        val wireHeaders = AtomicReference<Headers>()
        val exchanged = AtomicBoolean(false)
        try {
            assertUsable()
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
            val client = base.newBuilder().apply {
                interceptors().clear()
                networkInterceptors().clear()
                authenticator(Authenticator.NONE)
                cookieJar(CookieJar.NO_COOKIES)
                cache(null)
                eventListener(connectionListener)
                dns(dns)
                proxySelector(DirectOnlyToolProxySelector(base.proxySelector))
                // Preserve the configured proxy/authenticator, but never use one for origin auth.
                proxyAuthenticator(base.proxyAuthenticator)
                connectionPool(pool)
                protocols(listOf(Protocol.HTTP_1_1))
                followRedirects(false)
                followSslRedirects(false)
                retryOnConnectionFailure(false)
                callTimeout(remaining, TimeUnit.MILLISECONDS)
                connectTimeout(boundedTimeout(base.connectTimeoutMillis, remaining), TimeUnit.MILLISECONDS)
                readTimeout(boundedTimeout(base.readTimeoutMillis, remaining), TimeUnit.MILLISECONDS)
                writeTimeout(boundedTimeout(base.writeTimeoutMillis, remaining), TimeUnit.MILLISECONDS)
                addNetworkInterceptor { chain ->
                    assertUsable()
                    connectionListener.assertConnection(
                        chain.connection() ?: networkFailure(ToolNetworkErrorCode.UNSAFE_DNS)
                    )
                    if (!exchanged.compareAndSet(false, true)) networkFailure(ToolNetworkErrorCode.NETWORK_ERROR)
                    requestSent.set(true)
                    val response = chain.proceed(chain.request())
                    wireHeaders.set(response.headers)
                    // OkHttp can retry 503 + Retry-After: 0 even when retryOnConnectionFailure is
                    // false. Suppress that internal follow-up, retaining wire headers for callers.
                    if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
                }
            }.build()
            val call = client.newCall(request)
            return try {
                call.withResponse { response ->
                    try {
                        context.ensureActive()
                        assertUsable()
                        callerCheck(assertCurrent)
                        val headers = (wireHeaders.get() ?: response.headers).newBuilder()
                            .removeAll("Set-Cookie").removeAll("Set-Cookie2").build()
                        block(response, headers) { call.cancel() }
                    } finally {
                        // Also covers redirects and validation failures before a body is leased.
                        call.cancel()
                    }
                }
            } finally {
                call.cancel()
            }
        } finally {
            active.set(false)
            pool.evictAll()
        }
    }

    private fun redirectRequest(
        original: Request,
        status: Int,
        headers: Headers,
        policy: ToolNetworkPolicy,
        limits: ToolNetworkLimits,
        redirects: Int,
        allowRedirects: Boolean,
        hasCredentials: Boolean
    ): Request {
        if (!allowRedirects || policy == ToolNetworkPolicy.TRUSTED_LAN_HTTPS ||
            original.method !in setOf("GET", "HEAD") || status !in setOf(301, 302, 303, 307, 308) ||
            redirects >= limits.maxRedirects
        ) networkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT)
        val location = headers.values("Location").singleOrNull()
            ?: networkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT)
        val next = try {
            if (location.isEmpty() || location.length > ToolUrlGuard.MAX_URL_CHARS ||
                location.any { it <= ' ' || it.isWhitespace() || it == '\\' || it == '#' || it == '\u007f' }
            ) networkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT)
            when {
                location.startsWith("//") -> ToolUrlGuard.parse("https:$location", policy)
                ABSOLUTE_REFERENCE.containsMatchIn(location) -> ToolUrlGuard.parse(location, policy)
                else -> ToolUrlGuard.parse(
                    original.url.resolve(location)?.toString()
                        ?: networkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT),
                    policy
                )
            }
        } catch (_: ToolNetworkException) {
            networkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT)
        }
        val sameOrigin = original.url.scheme == next.scheme &&
            original.url.host == next.host && original.url.port == next.port
        if (hasCredentials && !sameOrigin) networkFailure(ToolNetworkErrorCode.UNSAFE_REDIRECT)
        if (sameOrigin) return isolatedRequest(original, next, hasCredentials)
        // A public page redirect does not receive origin-specific/custom headers or identifiers.
        return isolatedRequest(
            Request.Builder().url(next).method(original.method, null).apply {
                original.header("Accept")?.let { header("Accept", it) }
            }.build(),
            next, false
        )
    }

    private fun isolatedRequest(original: Request, url: HttpUrl, hasCredentials: Boolean): Request {
        if (original.method in setOf("CONNECT", "TRACE") || original.body?.isDuplex() == true) {
            networkFailure(ToolNetworkErrorCode.UNSAFE_URL)
        }
        return Request.Builder().url(url).method(original.method, original.body?.let(::OneShotToolBody)).apply {
            original.headers.forEach { (name, value) ->
                val lower = name.lowercase(Locale.ROOT)
                if (acceptsExplicitHeader(lower) &&
                    (hasCredentials || lower in PUBLIC_HEADERS || lower.startsWith("mcp-param-"))
                ) addHeader(name, value)
            }
            header("Accept-Encoding", "gzip")
            header("User-Agent", "CopilotGO-Tools")
            header("Cache-Control", "no-store")
            header("Connection", "close")
        }.build()
    }

    private fun boundedTimeout(configured: Int, remaining: Long): Long =
        if (configured == 0) remaining else minOf(configured.toLong(), remaining)

    companion object {
        internal fun acceptsExplicitHeader(name: String): Boolean {
            val lower = name.lowercase(Locale.ROOT)
            return lower !in FORBIDDEN_HEADERS && FORBIDDEN_PREFIXES.none(lower::startsWith)
        }

        private val ABSOLUTE_REFERENCE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private val PUBLIC_HEADERS = setOf(
            "accept", "accept-language", "content-type", "mcp-protocol-version", "mcp-method", "mcp-name"
        )
        private val FORBIDDEN_HEADERS = setOf(
            "cookie", "cookie2", "proxy-authorization", "proxy-authenticate", "host", "connection",
            "proxy-connection", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade",
            "expect", "accept-encoding", "content-length", "user-agent", "referer", "origin",
            "cache-control", "editor-version", "editor-plugin-version", "x-request-id",
            "x-initiator", "x-session-id", "x-client-id"
        )
        private val FORBIDDEN_PREFIXES = setOf("github-", "x-github-", "copilot-", "x-copilot-", "vscode-", "openai-")
    }
}

private class OneShotToolBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
}

private class CallerFailure(val original: Exception) : RuntimeException(null, null, false, false)

private fun callerCheck(check: () -> Unit) {
    try {
        check()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CallerFailure) {
        throw e
    } catch (e: Exception) {
        throw CallerFailure(e)
    }
}

private sealed interface HopResult<out T> {
    data class Redirect(val request: Request) : HopResult<Nothing>
    data class Value<T>(val value: T) : HopResult<T>
}
