package com.tongxie.copilotgo.data.auth

import com.tongxie.copilotgo.data.Constants
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class CopilotTokenClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val tokenUrl: String = Constants.COPILOT_TOKEN_URL
) {
    suspend fun exchange(githubAccessToken: String): CopilotTokenResponse {
        val req = Request.Builder()
            .url(tokenUrl)
            .get()
            .header("Authorization", "token $githubAccessToken")
            .header("Accept", "application/json")
            .header("User-Agent", Constants.USER_AGENT_VSCODE)
            .header("Editor-Version", Constants.EDITOR_VERSION)
            .header("Editor-Plugin-Version", Constants.EDITOR_PLUGIN_VERSION)
            .build()
        val resp = httpClient.newCall(req).executeAsync()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            error("Copilot token exchange failed (${resp.code}): $text")
        }
        return json.decodeFromString(CopilotTokenResponse.serializer(), text)
    }
}
