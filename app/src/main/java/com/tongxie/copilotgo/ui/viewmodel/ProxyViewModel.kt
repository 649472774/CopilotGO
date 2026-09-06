package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.chat.OperationResult
import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.data.proxy.ProxyHealthChecker
import com.tongxie.copilotgo.data.proxy.ProxySettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProxyViewModel(
    private val store: ProxySettingsStore,
    private val healthChecker: ProxyHealthChecker
) : ViewModel() {
    val config = store.config
    val initialized = store.initialized
    val loadError = store.loadError
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState = _testState.asStateFlow()
    private var testJob: Job? = null
    private var testGeneration = 0L

    suspend fun save(c: ProxyConfig): OperationResult {
        if (!_saving.compareAndSet(false, true)) return OperationResult.Rejected("正在保存代理配置")
        return try {
            store.update(c)
            _error.value = null
            resetTestState()
            OperationResult.Accepted
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val message = if (c.enabled && !c.isValid()) "代理地址或端口无效" else "保存失败，请重试；表单内容已保留"
            _error.value = message
            OperationResult.Rejected(message)
        } finally {
            _saving.value = false
        }
    }

    fun update(c: ProxyConfig) {
        viewModelScope.launch { save(c) }
    }

    fun clearError() { _error.value = null }
    fun reload() = store.reload()
    fun runHealthCheck() = startTest(null)
    fun testConfig(c: ProxyConfig) = startTest(c)

    private fun startTest(c: ProxyConfig?) {
        cancelTest()
        val generation = testGeneration
        _testState.value = TestState.Testing
        testJob = viewModelScope.launch {
            try {
                val outcome = if (c == null) healthChecker.check() else healthChecker.check(c)
                if (generation == testGeneration) {
                    _testState.value = TestState.Result(outcome.success, outcome.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == testGeneration) {
                    _testState.value = TestState.Result(false, "连接测试未完成，请检查代理配置后重试")
                }
            }
        }
    }

    fun cancelTest() {
        testGeneration++
        testJob?.cancel()
        testJob = null
        _testState.value = TestState.Idle
    }

    fun resetTestState() = cancelTest()

    sealed class TestState {
        data object Idle : TestState()
        data object Testing : TestState()
        data class Result(val success: Boolean, val message: String) : TestState()
    }
}
