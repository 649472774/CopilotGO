package com.tongxie.copilotgo.data.update

import com.tongxie.copilotgo.data.auth.withResponse
import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit

interface UpdateUrlPolicy {
    fun requireTrusted(url: HttpUrl)
    fun requireAsset(url: HttpUrl) = requireTrusted(url)
}

object GitHubUpdateUrls : UpdateUrlPolicy {
    private val hosts = setOf(
        "api.github.com", "github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com", "github-releases.githubusercontent.com"
    )

    override fun requireTrusted(url: HttpUrl) {
        if (!url.isHttps || url.host !in hosts || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.port != 443 || url.fragment != null
        ) {
            throw UpdateException("更新地址不受信任，已停止请求")
        }
    }

    override fun requireAsset(url: HttpUrl) {
        requireTrusted(url)
        if (url.host != "github.com" ||
            url.pathSegments.take(4) != listOf(Constants.GITHUB_REPO_OWNER, Constants.GITHUB_REPO_NAME, "releases", "download") ||
            url.pathSegments.size != 6 || url.pathSegments.last().isBlank()
        ) {
            throw UpdateException("安装包不是本项目的发布资源")
        }
    }
}

internal class UpdateTransport(
    private val provider: HttpClientProvider,
    private val policy: UpdateUrlPolicy,
    private val limits: UpdateLimits
) {
    val hasConfiguredProxy: Boolean
        get() = provider.client.proxy?.type()?.let { it != Proxy.Type.DIRECT } == true

    suspend fun <T> read(
        initialUrl: HttpUrl,
        route: UpdateRoute,
        timeoutMillis: Long,
        accept: String,
        consume: suspend (Response) -> T
    ): T {
        provider.awaitReady()
        val builder = provider.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
        if (route == UpdateRoute.DIRECT) {
            builder.proxy(Proxy.NO_PROXY).proxyAuthenticator(Authenticator.NONE)
        }
        val client = builder.build()
        var url = initialUrl
        repeat(limits.maxRedirects + 1) { redirectCount ->
            currentCoroutineContext().ensureActive()
            policy.requireTrusted(url)
            val request = Request.Builder().url(url)
                .header("Accept", accept)
                .header("Accept-Encoding", "identity")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .get().build()
            when (val result = client.newCall(request).withResponse { response ->
                if (response.code in REDIRECTS) {
                    if (redirectCount == limits.maxRedirects) throw UpdateException("更新地址重定向次数过多")
                    val location = response.header("Location") ?: throw UpdateException("更新地址重定向无效")
                    val next = url.resolve(location) ?: throw UpdateException("更新地址重定向无效")
                    policy.requireTrusted(next)
                    ReadResult.Redirect(next)
                } else {
                    when {
                        response.code == 404 -> throw UpdateException("发布资源不存在或已被移除，请重新检查更新")
                        response.code == 403 || response.code == 429 ->
                            throw UpdateException("更新服务限制了请求，请稍后重试或打开发布页")
                        !response.isSuccessful -> throw UpdateException("更新服务返回 HTTP ${response.code}")
                    }
                    ReadResult.Value(consume(response))
                }
            }) {
                is ReadResult.Value -> return result.value
                is ReadResult.Redirect -> url = result.url
            }
        }
        throw UpdateException("更新地址重定向次数过多")
    }

    private sealed interface ReadResult<out T> {
        data class Value<T>(val value: T) : ReadResult<T>
        data class Redirect(val url: HttpUrl) : ReadResult<Nothing>
    }

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        suspend fun readText(response: Response, maxBytes: Int): String {
            val body = response.body ?: throw UpdateException("更新服务返回空响应")
            if (body.contentLength() > maxBytes) throw UpdateException("更新服务的响应超过大小限制")
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > maxBytes - output.size()) throw UpdateException("更新服务的响应超过大小限制")
                    output.write(buffer, 0, count)
                }
            }
            if (output.size() == 0) throw UpdateException("更新服务返回空响应")
            return output.toString(Charsets.UTF_8.name())
        }
    }
}
