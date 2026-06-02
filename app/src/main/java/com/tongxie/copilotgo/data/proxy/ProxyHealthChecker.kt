package com.tongxie.copilotgo.data.proxy

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.executeAsync
import com.tongxie.copilotgo.data.net.HttpClientProvider
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 代理健康检查：真正打 Copilot 鉴权端点（GFW 必墙），区分真假代理。
 *
 * 旧实现：HEAD https://api.githubcopilot.com —— 国内大部分网络能解析 + TCP 通到 GFW
 * 拦截层，因此即使代理坏了也常常"成功"。
 *
 * 新实现：GET {apiBase}/models（如果已登录就带 Bearer token；未登录就裸 GET）
 *   - HTTP 2xx → 代理 OK + token 有效
 *   - HTTP 401/403 → 代理 OK 但 token 失效（仍是 success=true，提示重新登录）
 *   - SocketTimeout / ConnectException → 代理未生效 / 被墙 / Clash 没开
 *   - UnknownHostException → DNS 解析失败
 */
class ProxyHealthChecker(
    private val httpProvider: HttpClientProvider,
    private val auth: AuthRepository
) {

    sealed class Outcome {
        abstract val success: Boolean
        abstract val message: String

        data class Ok(override val message: String) : Outcome() { override val success = true }
        data class Warn(override val message: String) : Outcome() { override val success = true }
        data class Err(override val message: String) : Outcome() { override val success = false }
    }

    suspend fun check(): Outcome {
        val client = httpProvider.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        return checkWithClient(client, direct = false)
    }

    suspend fun check(config: ProxyConfig): Outcome {
        val builder = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)

        val active = config.enabled && config.isValid()
        var socksAuthUnverified = false
        if (active) {
            val proxyType = when (config.type) {
                ProxyType.HTTP -> Proxy.Type.HTTP
                ProxyType.SOCKS5 -> Proxy.Type.SOCKS
            }
            builder.proxy(Proxy(proxyType, InetSocketAddress(config.host, config.port)))

            if (config.requiresAuth && config.type == ProxyType.HTTP) {
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request.newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(config.username, config.password))
                            .build()
                    }
                }
            } else if (config.requiresAuth && config.type == ProxyType.SOCKS5) {
                // OkHttp/JVM only drives SOCKS user/pass auth via the process-global Authenticator,
                // which the live provider owns and reflects the *saved* config — not this unsaved form.
                // Rather than racing that global state, we run the reachability test without form creds
                // and clearly tell the user the credentials weren't validated.
                socksAuthUnverified = true
            }
        } else {
            builder.proxy(Proxy.NO_PROXY)
        }

        val outcome = checkWithClient(builder.build(), direct = !active)
        return if (socksAuthUnverified) {
            val note = "\nℹ️ 注意：SOCKS5 账号/密码无法在此测试中验证，以上仅为代理可达性结果；请保存后实际使用以确认认证。"
            when (outcome) {
                is Outcome.Ok -> Outcome.Ok(outcome.message + note)
                is Outcome.Warn -> Outcome.Warn(outcome.message + note)
                is Outcome.Err -> outcome
            }
        } else {
            outcome
        }
    }

    private suspend fun checkWithClient(client: OkHttpClient, direct: Boolean): Outcome {
        val (req, apiBase) = buildRequest()
        val start = System.currentTimeMillis()
        return try {
            val resp = client.newCall(req).executeAsync()
            val elapsed = System.currentTimeMillis() - start
            val code = resp.code
            resp.close()
            mapResponse(code, elapsed, apiBase, direct)
        } catch (e: SocketTimeoutException) {
            val elapsed = System.currentTimeMillis() - start
            Outcome.Err(
                "❌ 连接 Copilot 超时（${elapsed}ms）\n" +
                        "常见原因：代理未启用、Clash 未运行、端口/host 填错\n" +
                        "模拟器 host 用 10.0.2.2；真机用 PC/路由器 LAN IP，或手机本机 Clash 才用 127.0.0.1"
            )
        } catch (e: ConnectException) {
            Outcome.Err(
                "❌ 无法连接代理：${e.message ?: "ConnectException"}\n" +
                        "检查 Clash、端口和 host：模拟器 10.0.2.2；真机用 PC/路由器 LAN IP"
            )
        } catch (e: UnknownHostException) {
            Outcome.Err(
                "❌ DNS 解析失败：${e.message ?: "UnknownHostException"}\n" +
                        "模拟器 host 用 10.0.2.2；真机用 PC/路由器 LAN IP，手机本机 Clash 才用 127.0.0.1"
            )
        } catch (e: Throwable) {
            Outcome.Err("❌ 失败：${e::class.simpleName}：${e.message ?: ""}")
        }
    }

    private suspend fun buildRequest(): Pair<Request, String> {
        val session = runCatching { auth.getValidCopilotSession() }.getOrNull()
        val apiBase = session?.apiBase ?: Constants.COPILOT_API_BASE
        val builder = Request.Builder()
            .url("$apiBase/models")
            .get()
            .header("Accept", "application/json")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", Constants.USER_AGENT_VSCODE)
            .header("Editor-Version", Constants.EDITOR_VERSION)
            .header("Editor-Plugin-Version", Constants.EDITOR_PLUGIN_VERSION)
            .header("Copilot-Integration-Id", Constants.COPILOT_INTEGRATION_ID)
            .header("X-Request-Id", UUID.randomUUID().toString())
        if (session != null) {
            builder.header("Authorization", "Bearer ${session.token}")
        }
        return builder.build() to apiBase
    }

    private fun mapResponse(code: Int, elapsed: Long, apiBase: String, direct: Boolean): Outcome {
        val prefix = if (direct) "（直连测试，未启用代理）\n" else ""
        return when {
            code in 200..299 -> Outcome.Ok(
                prefix + "✅ Copilot 可达：HTTP $code，${elapsed}ms\n端点：$apiBase"
            )
            code == 401 || code == 403 -> Outcome.Warn(
                prefix + "⚠️ 代理已通到 Copilot（HTTP $code，${elapsed}ms）\n但 token 失效，请回设置-账号重新登录"
            )
            code in 500..599 -> Outcome.Warn(
                prefix + "⚠️ Copilot 服务端 $code，${elapsed}ms（代理工作正常，是服务端问题）"
            )
            else -> Outcome.Warn(
                prefix + "⚠️ 异常响应：HTTP $code，${elapsed}ms\n可能被中间网关劫持（确认代理指向 Clash 而非系统 VPN）"
            )
        }
    }
}
