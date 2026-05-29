package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.data.chat.CopilotChatClient
import com.tongxie.copilotgo.data.chat.ModelInfo
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.storage.SessionStore
import com.tongxie.copilotgo.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import java.util.UUID

class SessionListViewModel(
    private val store: SessionStore,
    private val chatClient: CopilotChatClient,
    private val authRepo: AuthRepository
) : ViewModel() {

    val sessions: StateFlow<List<Session>> = store.sessions

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models

    init {
        viewModelScope.launch { store.load() }
        // 监听登录态：每次进入 LoggedIn 重新拉取真实模型列表
        viewModelScope.launch {
            authRepo.state.filterIsInstance<AuthState.LoggedIn>().collect {
                refreshModels()
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            runCatching { chatClient.listModels() }
                .onSuccess { list ->
                    if (list.isNotEmpty()) {
                        Logger.i("Loaded ${list.size} real models")
                        _models.value = list
                    } else if (_models.value.isEmpty()) {
                        // 真没拉到，才用 fallback 让 UI 至少能选
                        _models.value = Constants.FALLBACK_MODELS.map { ModelInfo(id = it) }
                    }
                }
                .onFailure { e ->
                    Logger.w("refreshModels failed: ${e.message}")
                    if (_models.value.isEmpty()) {
                        _models.value = Constants.FALLBACK_MODELS.map { ModelInfo(id = it) }
                    }
                }
        }
    }

    suspend fun createNew(model: String? = null): Session {
        // 优先用拉到的真实模型列表的首个；否则用 DEFAULT_MODEL
        val pick = model ?: _models.value.firstOrNull()?.id ?: Constants.DEFAULT_MODEL
        val s = Session(
            id = UUID.randomUUID().toString(),
            title = "新会话",
            model = pick
        )
        store.save(s)
        return s
    }

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    fun reload() {
        viewModelScope.launch { store.load() }
    }
}
