package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AccountActionsViewModel : ViewModel() {
    enum class State { Idle, Confirming, Working, Failed, FailureDismissed, Completed }

    private val _state = MutableStateFlow(State.Idle)
    val state = _state.asStateFlow()

    fun confirm() {
        if (_state.value != State.Working) {
            _state.value = if (_state.value == State.FailureDismissed) State.Failed else State.Confirming
        }
    }

    fun dismiss() {
        if (_state.value != State.Working) {
            _state.value = if (_state.value == State.Failed || _state.value == State.FailureDismissed) {
                State.FailureDismissed
            } else State.Idle
        }
    }

    fun logout(logoutAndAwait: suspend () -> Unit) {
        if (_state.value != State.Confirming && _state.value != State.Failed) return
        _state.value = State.Working
        viewModelScope.launch {
            try {
                logoutAndAwait()
                _state.value = State.Completed
            } catch (e: CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (_: IOException) {
                _state.value = State.Failed
            } catch (_: GeneralSecurityException) {
                _state.value = State.Failed
            } catch (_: SecurityException) {
                _state.value = State.Failed
            }
        }
    }
}
