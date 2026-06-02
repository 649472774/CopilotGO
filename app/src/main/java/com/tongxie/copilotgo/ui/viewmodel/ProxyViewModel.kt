package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.data.proxy.ProxyHealthChecker
import com.tongxie.copilotgo.data.proxy.ProxySettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProxyViewModel(
    private val store: ProxySettingsStore,
    private val healthChecker: ProxyHealthChecker
) : ViewModel() {

    val config: StateFlow<ProxyConfig> = store.config

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState

    fun update(c: ProxyConfig) {
        viewModelScope.launch { store.update(c) }
    }

    fun runHealthCheck() {
        viewModelScope.launch {
            _testState.value = TestState.Testing
            val out = healthChecker.check()
            _testState.value = TestState.Result(success = out.success, message = out.message)
        }
    }

    fun testConfig(c: ProxyConfig) {
        viewModelScope.launch {
            _testState.value = TestState.Testing
            val out = healthChecker.check(c)
            _testState.value = TestState.Result(success = out.success, message = out.message)
        }
    }

    sealed class TestState {
        object Idle : TestState()
        object Testing : TestState()
        data class Result(val success: Boolean, val message: String) : TestState()
    }
}
