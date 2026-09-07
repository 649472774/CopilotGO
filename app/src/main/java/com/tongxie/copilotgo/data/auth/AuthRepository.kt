package com.tongxie.copilotgo.data.auth

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.net.ApiException
import com.tongxie.copilotgo.data.net.networkErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class AuthRepository(
    private val tokenStore: CredentialStore,
    private val deviceFlow: DeviceFlowClient,
    private val copilotToken: CopilotTokenClient
) {
    private val guard = Any()
    private val credentialsLock = Mutex()
    private val refreshLock = Mutex()
    private val _state = MutableStateFlow<AuthState>(AuthState.NotLoggedIn)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _initializing = MutableStateFlow(true)
    val initializing = _initializing.asStateFlow()
    private val _loggingOut = MutableStateFlow(false)
    val loggingOut = _loggingOut.asStateFlow()
    private val _accountGeneration = MutableStateFlow(0L)
    val accountGeneration = _accountGeneration.asStateFlow()
    private var activeDeviceCode: Pair<String, Long>? = null
    private var logoutFlight: CompletableDeferred<Unit>? = null

    data class CopilotSession(val token: String, val apiBase: String)

    private fun requireCurrent(generation: Long) {
        if (_accountGeneration.value != generation) {
            throw CancellationException("Account operation superseded")
        }
    }

    suspend fun bootstrap() {
        val generation = accountGeneration.value
        try {
            val credentials = credentialsLock.withLock { tokenStore.readCredentials() }
            synchronized(guard) {
                requireCurrent(generation)
                if (_loggingOut.value) return
                _state.value = if (credentials.githubToken.isNullOrBlank()) {
                    AuthState.NotLoggedIn
                } else {
                    AuthState.LoggedIn(credentials.copilot?.sku)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(guard) {
                if (generation == accountGeneration.value) {
                    _state.value = AuthState.Failed("无法读取登录凭据，请重试；原凭据未被删除")
                }
            }
        } finally {
            _initializing.value = false
        }
    }

    suspend fun beginDeviceLogin(): DeviceCodeResponse {
        val generation = synchronized(guard) {
            check(!_loggingOut.value) { "正在退出登录" }
            _accountGeneration.value += 1
            activeDeviceCode = null
            _busy.value = true
            _state.value = AuthState.NotLoggedIn
            _accountGeneration.value
        }
        try {
            val code = withAccount { deviceFlow.requestDeviceCode() }
            currentCoroutineContext().ensureActive()
            synchronized(guard) {
                requireCurrent(generation)
                activeDeviceCode = code.deviceCode to generation
                _state.value = AuthState.AwaitingUserAuthorization(
                    code.userCode, code.verificationUri, code.expiresIn
                )
            }
            return code
        } catch (e: CancellationException) {
            finishCancelledLogin(generation)
            throw e
        } catch (e: Exception) {
            failLogin(generation, networkErrorMessage(e))
            throw e
        }
    }

    suspend fun pollUntilDone(dc: DeviceCodeResponse) {
        val generation = synchronized(guard) {
            activeDeviceCode?.takeIf { it.first == dc.deviceCode }?.second
                ?: throw CancellationException("Login is no longer active")
        }
        try {
            withAccount { deviceFlow.pollAccessToken(dc).collect { result ->
                requireCurrent(generation)
                when (result) {
                    is DeviceFlowClient.PollResult.Success -> {
                        val fresh = copilotToken.exchange(result.accessToken)
                        val cached = fresh.toCached()
                        commitCredentials(generation, StoredCredentials(result.accessToken, cached))
                        synchronized(guard) {
                            requireCurrent(generation)
                            _state.value = AuthState.LoggedIn(fresh.sku)
                            _busy.value = false
                            activeDeviceCode = null
                        }
                    }
                    is DeviceFlowClient.PollResult.Failure -> failLogin(generation, result.message)
                }
            } }
        } catch (e: CancellationException) {
            finishCancelledLogin(generation)
            throw e
        } catch (e: Exception) {
            failLogin(generation, networkErrorMessage(e))
        }
    }

    fun cancelLogin() = synchronized(guard) {
        if (_busy.value || activeDeviceCode != null) {
            _accountGeneration.value += 1
            activeDeviceCode = null
            _busy.value = false
            _state.value = AuthState.NotLoggedIn
        }
    }

    private fun finishCancelledLogin(generation: Long) = synchronized(guard) {
        if (generation == accountGeneration.value) {
            activeDeviceCode = null
            _busy.value = false
            _state.value = AuthState.NotLoggedIn
        }
    }

    private fun failLogin(generation: Long, message: String) = synchronized(guard) {
        if (generation == accountGeneration.value) {
            activeDeviceCode = null
            _busy.value = false
            _state.value = AuthState.Failed(message)
        }
    }

    suspend fun getValidCopilotSession(): CopilotSession = withAccount {
        val generation = accountGeneration.value
        refreshLock.withLock {
            val credentials = credentialsLock.withLock {
                requireCurrent(generation)
                if (_loggingOut.value) throw ApiException(401, message = "正在退出登录")
                tokenStore.readCredentials()
            }
            val github = credentials.githubToken
                ?.takeIf { it.isNotBlank() }
                ?: throw ApiException(401, message = "尚未登录，请先登录")
            val cached = credentials.copilot
            if (cached != null && !cached.isExpiringSoon() && !cached.apiBase.isNullOrBlank()) {
                requireCurrent(generation)
                return@withLock CopilotSession(cached.token, cached.apiBase)
            }
            val fresh = copilotToken.exchange(github).toCached()
            commitCredentials(generation, StoredCredentials(github, fresh))
            requireCurrent(generation)
            CopilotSession(fresh.token, requireNotNull(fresh.apiBase))
        }
    }

    suspend fun getValidCopilotToken(): String = getValidCopilotSession().token

    suspend fun getCachedCopilotSession(): CopilotSession? {
        val generation = accountGeneration.value
        return credentialsLock.withLock {
            requireCurrent(generation)
            if (_loggingOut.value) return@withLock null
            val credentials = tokenStore.readCredentials()
            val cached = credentials.copilot
            if (credentials.githubToken.isNullOrBlank() || cached == null ||
                cached.isExpiringSoon() || cached.apiBase.isNullOrBlank()
            ) return@withLock null
            CopilotSession(cached.token, cached.apiBase)
        }
    }

    private suspend fun commitCredentials(generation: Long, credentials: StoredCredentials) {
        val caller = currentCoroutineContext()
        credentialsLock.withLock {
            caller.ensureActive()
            requireCurrent(generation)
            withContext(NonCancellable) {
                val previous = tokenStore.readCredentials()
                tokenStore.writeCredentials(credentials)
                if (generation != accountGeneration.value || !caller.isActive) {
                    tokenStore.writeCredentials(previous)
                    throw CancellationException("Credential commit superseded")
                }
            }
        }
    }

    internal suspend fun accountCacheKey(): String {
        val generation = accountGeneration.value
        return credentialsLock.withLock {
            requireCurrent(generation)
            val github = tokenStore.readCredentials().githubToken
                ?: throw ApiException(401, message = "尚未登录，请先登录")
            MessageDigest.getInstance("SHA-256").digest(github.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    internal suspend fun <T> withAccount(block: suspend () -> T): T = coroutineScope {
        val generation = accountGeneration.value
        val operation = this
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            accountGeneration.first { it != generation }
            operation.cancel("Account changed")
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    suspend fun logout() {
        val (completion, owner) = synchronized(guard) {
            logoutFlight?.let { return@synchronized it to false }
            val completion = CompletableDeferred<Unit>()
            logoutFlight = completion
            _accountGeneration.value += 1
            activeDeviceCode = null
            _busy.value = false
            _loggingOut.value = true
            completion to true
        }
        if (!owner) {
            completion.await()
            return
        }
        // Duplicate logout actions join one durable clear; navigation cannot cancel the clear.
        withContext(NonCancellable) {
            try {
                credentialsLock.withLock { tokenStore.clearAll() }
                synchronized(guard) { _state.value = AuthState.NotLoggedIn }
                completion.complete(Unit)
            } catch (e: Exception) {
                _state.value = AuthState.Failed("退出登录未完成，凭据清除失败，请重试")
                completion.completeExceptionally(e)
                throw e
            } finally {
                synchronized(guard) {
                    if (logoutFlight === completion) {
                        logoutFlight = null
                        _loggingOut.value = false
                    }
                }
            }
        }
    }

    private fun CopilotTokenResponse.toCached() = TokenStore.CachedCopilot(
        token, expiresAt, sku, endpoints?.get("api") ?: Constants.COPILOT_API_BASE
    )
}
