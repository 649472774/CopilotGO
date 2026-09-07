package com.tongxie.copilotgo.data.auth

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.net.apiFailure
import com.tongxie.copilotgo.data.net.readBodyLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class DeviceFlowClient(
    private val httpProvider: HttpClientProvider,
    private val json: Json,
    private val clientId: String = Constants.CLIENT_ID,
    private val deviceCodeUrl: String = Constants.GITHUB_DEVICE_CODE_URL,
    private val accessTokenUrl: String = Constants.GITHUB_ACCESS_TOKEN_URL,
    private val pollDelay: suspend (Long) -> Unit = { delay(it) }
) {

    suspend fun requestDeviceCode(scope: String = "read:user"): DeviceCodeResponse {
        httpProvider.awaitReady()
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scope)
            .build()
        val req = Request.Builder()
            .url(deviceCodeUrl.toHttpUrl())
            .post(body)
            .header("Accept", "application/json")
            .build()
        return httpProvider.client.newCall(req).withResponse { resp ->
            val text = resp.readBodyLimited(64 * 1024)
            if (!resp.isSuccessful) throw apiFailure(resp.code, text, json)
            json.decodeFromString(DeviceCodeResponse.serializer(), text).also {
                require(it.deviceCode.isNotBlank() && it.userCode.isNotBlank() && it.expiresIn > 0) {
                    "登录响应缺少有效的授权信息"
                }
            }
        }
    }

    fun pollAccessToken(deviceCode: DeviceCodeResponse): Flow<PollResult> = flow {
        val deadline = System.currentTimeMillis() + deviceCode.expiresIn * 1000L
        var interval = deviceCode.interval.coerceAtLeast(5)
        while (System.currentTimeMillis() < deadline) {
            pollDelay(interval * 1000L)
            if (System.currentTimeMillis() >= deadline) break
            val resp = pollOnce(deviceCode.deviceCode)
            when {
                !resp.accessToken.isNullOrBlank() -> {
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
                    emit(PollResult.Failure("授权未完成，请重新开始登录"))
                    return@flow
                }
                else -> {
                    emit(PollResult.Failure("授权响应缺少状态，请重新开始登录"))
                    return@flow
                }
            }
        }
        emit(PollResult.Failure("登录超时"))
    }.flowOn(Dispatchers.IO)

    internal suspend fun pollOnce(deviceCode: String): AccessTokenResponse {
        httpProvider.awaitReady()
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
        return httpProvider.client.newCall(req).withResponse { resp ->
            val text = resp.readBodyLimited(64 * 1024)
            if (!resp.isSuccessful) throw apiFailure(resp.code, text, json)
            json.decodeFromString(AccessTokenResponse.serializer(), text)
        }
    }

    sealed interface PollResult {
        data class Success(val accessToken: String) : PollResult
        data class Failure(val message: String) : PollResult
    }
}
