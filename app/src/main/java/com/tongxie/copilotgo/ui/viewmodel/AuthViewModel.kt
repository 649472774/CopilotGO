package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.DeviceCodeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel(private val auth: AuthRepository) : ViewModel() {
    val state = auth.state
    val busy = auth.busy
    val initializing = auth.initializing
    val loggingOut = auth.loggingOut
    private val _deviceCode = MutableStateFlow<DeviceCodeResponse?>(null)
    val deviceCode: StateFlow<DeviceCodeResponse?> = _deviceCode
    private var pollJob: Job? = null

    init {
        viewModelScope.launch { auth.bootstrap() }
    }

    fun startLogin() {
        if (loggingOut.value) return
        cancel()
        pollJob = viewModelScope.launch {
            try {
                val dc = auth.beginDeviceLogin()
                _deviceCode.value = dc
                auth.pollUntilDone(dc)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The repository has published a recoverable AuthState.Failed.
                _deviceCode.value = null
            }
        }
    }

    fun cancel() {
        pollJob?.cancel()
        pollJob = null
        auth.cancelLogin()
        _deviceCode.value = null
    }

    suspend fun logoutAndAwait() {
        try {
            auth.logout()
        } finally {
            val login = pollJob
            pollJob = null
            _deviceCode.value = null
            login?.cancelAndJoin()
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                logoutAndAwait()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The failure state remains visible; navigation must not report success.
            }
        }
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }
}
