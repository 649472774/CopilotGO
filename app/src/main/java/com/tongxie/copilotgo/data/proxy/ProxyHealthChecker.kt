package com.tongxie.copilotgo.data.proxy

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.executeAsync
import com.tongxie.copilotgo.data.net.HttpClientProvider
import okhttp3.Request
import java.net.ConnectException
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
        // 拿短超时的临时 client（共用 provider 的代理设置）
        val client = httpProvider.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        // 拿 token + apiBase（如果已登录）。即使没 token 也能继续测，401 也算"通"。
        val session = runCatching { auth.getValidCopilotSession() }.getOrNull()
        val apiBase = session?.apiBase ?: Constants.COPILOT_API_BASE
        val url = "$apiBase/models"

        val builder = Request.Builder()
            .url(url)
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
        val req = builder.build()

        val start = System.currentTimeMillis()
        return try {
            val resp = client.newCall(req).executeAsync()
            val elapsed = System.currentTimeMillis() - start
            val code = resp.code
            resp.close()
            when {
                code in 200..299 -> Outcome.Ok(
                    "✅ Copilot 可达：HTTP $code，${elapsed}ms\n端点：$apiBase"
                )
                code == 401 || code == 403 -> Outcome.Warn(
                    "⚠️ 代理已通到 Copilot（HTTP $code，${elapsed}ms）\n但 token 失效，请回设置-账号重新登录"
                )
                code in 500..599 -> Outcome.Warn(
                    "⚠️ Copilot 服务端 $code，${elapsed}ms（代理工作正常，是服务端问题）"
                )
                else -> Outcome.Warn(
                    "⚠️ 异常响应：HTTP $code，${elapsed}ms\n可能被中间网关劫持（确认代理指向 Clash 而非系统 VPN）"
                )
            }
        } catch (e: SocketTimeoutException) {
            val elapsed = System.currentTimeMillis() - start
            Outcome.Err(
                "❌ 连接 Copilot 超时（${elapsed}ms）\n" +
                        "常见原因：\n" +
                        "1) 代理未启用或 Clash 未运行\n" +
                        "2) Clash 没开 HTTP 端口（默认 7890）\n" +
                        "3) 代理 host/port 填错"
            )
        } catch (e: ConnectException) {
            Outcome.Err(
                "❌ 无法连接代理：${e.message ?: "ConnectException"}\n" +
                        "请检查 Clash 是否正在运行，端口是否正确"
            )
        } catch (e: UnknownHostException) {
            Outcome.Err(
                "❌ DNS 解析失败：${e.message ?: "UnknownHostException"}\n" +
                        "代理 host 写错了吗？模拟器请用 10.0.2.2，真机用 127.0.0.1"
            )
        } catch (e: Throwable) {
            Outcome.Err("❌ 失败：${e::class.simpleName}：${e.message ?: ""}")
        }
    }
}
