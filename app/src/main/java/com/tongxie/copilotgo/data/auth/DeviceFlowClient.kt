package com.tongxie.copilotgo.data.auth

import com.tongxie.copilotgo.data.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class DeviceFlowClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val clientId: String = Constants.CLIENT_ID,
    private val deviceCodeUrl: String = Constants.GITHUB_DEVICE_CODE_URL,
    private val accessTokenUrl: String = Constants.GITHUB_ACCESS_TOKEN_URL
) {

    suspend fun requestDeviceCode(scope: String = "read:user"): DeviceCodeResponse {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scope)
            .build()
        val req = Request.Builder()
            .url(deviceCodeUrl.toHttpUrl())
            .post(body)
            .header("Accept", "application/json")
            .build()
        val resp = httpClient.newCall(req).executeAsync()
        if (!resp.isSuccessful) error("device_code request failed: ${resp.code}")
        val text = resp.body?.string().orEmpty()
        return json.decodeFromString(DeviceCodeResponse.serializer(), text)
    }

    fun pollAccessToken(deviceCode: DeviceCodeResponse): Flow<PollResult> = flow {
        val deadline = System.currentTimeMillis() + deviceCode.expiresIn * 1000L
        var interval = deviceCode.interval.coerceAtLeast(5)
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val resp = pollOnce(deviceCode.deviceCode)
            when {
                resp.accessToken != null -> {
                    emit(PollResult.Success(resp.accessToken))
                    return@flow
                }
                resp.error == "authorization_pending" -> { /* keep waiting */ }
                resp.error == "slow_down" -> { interval += 5 }
                resp.error == "expired_token" -> {
                    emit(PollResult.Failure("登录链接已过期，请重新开始"))
                    return@flow
                }
                resp.error == "access_denied" -> {
                    emit(PollResult.Failure("已被用户拒绝"))
                    return@flow
                }
                resp.error != null -> {
                    emit(PollResult.Failure(resp.errorDescription ?: resp.error))
                    return@flow
                }
            }
        }
        emit(PollResult.Failure("登录超时"))
    }

    internal suspend fun pollOnce(deviceCode: String): AccessTokenResponse {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val req = Request.Builder()
            .url(accessTokenUrl.toHttpUrl())
            .post(body)
            .header("Accept", "application/json")
            .build()
        val resp = httpClient.newCall(req).executeAsync()
        val text = resp.body?.string().orEmpty()
        return json.decodeFromString(AccessTokenResponse.serializer(), text)
    }

    sealed interface PollResult {
        data class Success(val accessToken: String) : PollResult
        data class Failure(val message: String) : PollResult
    }
}
