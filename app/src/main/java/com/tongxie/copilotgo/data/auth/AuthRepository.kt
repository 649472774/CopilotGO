package com.tongxie.copilotgo.data.auth

import com.tongxie.copilotgo.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 统一对外的鉴权门面：
 * 1) 走 Device Flow 拿 user token
 * 2) 用 user token 换 Copilot session token
 * 3) session token 过期前自动刷新
 */
class AuthRepository(
    private val tokenStore: TokenStore,
    private val deviceFlow: DeviceFlowClient,
    private val copilotToken: CopilotTokenClient
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.NotLoggedIn)
    val state: Flow<AuthState> = _state.asStateFlow()

    private val refreshLock = Mutex()

    data class CopilotSession(val token: String, val apiBase: String)

    suspend fun bootstrap() {
        val gh = tokenStore.getGithubToken()
        if (gh != null) {
            val cp = tokenStore.getCopilotToken()
            val sku = cp?.sku
            _state.value = AuthState.LoggedIn(sku)
        } else {
            _state.value = AuthState.NotLoggedIn
        }
    }

    /** 启动 device flow，UI 收到 AwaitingUserAuthorization 后展示 user_code */
    suspend fun beginDeviceLogin(): DeviceCodeResponse {
        val dc = deviceFlow.requestDeviceCode()
        _state.value = AuthState.AwaitingUserAuthorization(
            userCode = dc.userCode,
            verificationUri = dc.verificationUri,
            expiresInSeconds = dc.expiresIn
        )
        return dc
    }

    /** 阻塞挂起直到登录完成或失败 */
    suspend fun pollUntilDone(dc: DeviceCodeResponse) {
        deviceFlow.pollAccessToken(dc).collect { result ->
            when (result) {
                is DeviceFlowClient.PollResult.Success -> {
                    tokenStore.saveGithubToken(result.accessToken)
                    runCatching { exchangeAndStoreCopilotToken(result.accessToken) }
                        .onSuccess {
                            _state.value = AuthState.LoggedIn(it.sku)
                        }
                        .onFailure { e ->
                            Logger.e("exchange copilot token failed: ${e.message}", throwable = e)
                            _state.value = AuthState.Failed(
                                "GitHub 登录成功，但兑换 Copilot token 失败：${e.message}"
                            )
                        }
                }
                is DeviceFlowClient.PollResult.Failure -> {
                    _state.value = AuthState.Failed(result.message)
                }
            }
        }
    }

    /** 获取一个有效的 Copilot session token + 对应的 API base URL，过期则自动刷新。 */
    suspend fun getValidCopilotSession(): CopilotSession = refreshLock.withLock {
        val cached = tokenStore.getCopilotToken()
        if (cached != null && !cached.isExpiringSoon() && cached.apiBase != null) {
            return@withLock CopilotSession(token = cached.token, apiBase = cached.apiBase)
        }
        val gh = tokenStore.getGithubToken() ?: error("尚未登录")
        val fresh = exchangeAndStoreCopilotToken(gh)
        CopilotSession(
            token = fresh.token,
            apiBase = fresh.endpoints?.get("api") ?: com.tongxie.copilotgo.data.Constants.COPILOT_API_BASE
        )
    }

    /** 兼容老调用方：只要 token 字符串。 */
    suspend fun getValidCopilotToken(): String = getValidCopilotSession().token

    private suspend fun exchangeAndStoreCopilotToken(githubToken: String): CopilotTokenResponse {
        val resp = copilotToken.exchange(githubToken)
        val apiBase = resp.endpoints?.get("api")
        tokenStore.saveCopilotToken(resp.token, resp.expiresAt, resp.sku, apiBase)
        return resp
    }

    suspend fun logout() {
        tokenStore.clearAll()
        _state.value = AuthState.NotLoggedIn
    }
}
