package com.tongxie.copilotgo.data.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("interval") val interval: Int
)

@Serializable
data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null
)

@Serializable
data class CopilotTokenResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("refresh_in") val refreshIn: Int = 1500,
    val sku: String? = null,
    val endpoints: Map<String, String>? = null,
    @SerialName("annotations_enabled") val annotationsEnabled: Boolean = false,
    @SerialName("chat_enabled") val chatEnabled: Boolean = true
)

sealed interface AuthState {
    data object NotLoggedIn : AuthState
    data class AwaitingUserAuthorization(
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Int
    ) : AuthState
    data class LoggedIn(val sku: String?) : AuthState
    data class Failed(val message: String) : AuthState
}
