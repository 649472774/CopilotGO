package com.tongxie.copilotgo.data.auth

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.net.apiFailure
import com.tongxie.copilotgo.data.net.readBodyLimited
import kotlinx.serialization.json.Json
import okhttp3.Request

class CopilotTokenClient(
    private val httpProvider: HttpClientProvider,
    private val json: Json,
    private val tokenUrl: String = Constants.COPILOT_TOKEN_URL
) {
    suspend fun exchange(githubAccessToken: String): CopilotTokenResponse {
        httpProvider.awaitReady()
        val req = Request.Builder()
            .url(tokenUrl)
            .get()
            .header("Authorization", "token $githubAccessToken")
            .header("Accept", "application/json")
            .header("User-Agent", Constants.USER_AGENT_VSCODE)
            .header("Editor-Version", Constants.EDITOR_VERSION)
            .header("Editor-Plugin-Version", Constants.EDITOR_PLUGIN_VERSION)
            .build()
        return httpProvider.client.newCall(req).withResponse { resp ->
            val text = resp.readBodyLimited(64 * 1024)
            if (!resp.isSuccessful) throw apiFailure(resp.code, text, json)
            json.decodeFromString(CopilotTokenResponse.serializer(), text).also {
                require(it.token.isNotBlank() && it.expiresAt > System.currentTimeMillis() / 1000) {
                    "服务返回了无效的登录凭据"
                }
            }
        }
    }
}
