package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.data.auth.DeviceCodeResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AuthViewModel(private val auth: AuthRepository) : ViewModel() {

    val state: StateFlow<AuthState> = auth.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AuthState.NotLoggedIn
    )

    private val _deviceCode = MutableStateFlow<DeviceCodeResponse?>(null)
    val deviceCode: StateFlow<DeviceCodeResponse?> = _deviceCode

    private var pollJob: Job? = null

    init {
        viewModelScope.launch { auth.bootstrap() }
    }

    fun startLogin() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            runCatching {
                val dc = auth.beginDeviceLogin()
                _deviceCode.value = dc
                auth.pollUntilDone(dc)
            }
        }
    }

    fun cancel() {
        pollJob?.cancel()
        pollJob = null
        _deviceCode.value = null
    }

    fun logout() {
        viewModelScope.launch {
            auth.logout()
            _deviceCode.value = null
        }
    }
}
