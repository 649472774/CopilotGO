package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.data.chat.ChatStreamCenter
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
    private val authRepo: AuthRepository,
    private val center: ChatStreamCenter
) : ViewModel() {

    val sessions: StateFlow<List<Session>> = store.sessions

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models

    // 节流：防止 auth state 重复 emit + ChatScreen 进入 + session 切换 多次触发 GET /models
    @Volatile
    private var lastRefreshAt: Long = 0L
    @Volatile
    private var refreshing: Boolean = false

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
        val now = System.currentTimeMillis()
        // 已经在请求中，或者距离上次成功不到 30s，跳过
        if (refreshing) {
            Logger.d("refreshModels: skip (in flight)")
            return
        }
        if (_models.value.isNotEmpty() && now - lastRefreshAt < 30_000L) {
            Logger.d("refreshModels: skip (throttled, last=${now - lastRefreshAt}ms ago)")
            return
        }
        refreshing = true
        viewModelScope.launch {
            runCatching { chatClient.listModels() }
                .onSuccess { list ->
                    if (list.isNotEmpty()) {
                        Logger.i("Loaded ${list.size} real models")
                        _models.value = list
                        lastRefreshAt = System.currentTimeMillis()
                    } else if (_models.value.isEmpty()) {
                        _models.value = Constants.FALLBACK_MODELS.map { ModelInfo(id = it) }
                    }
                }
                .onFailure { e ->
                    Logger.w("refreshModels failed: ${e.message}")
                    if (_models.value.isEmpty()) {
                        _models.value = Constants.FALLBACK_MODELS.map { ModelInfo(id = it) }
                    }
                }
            refreshing = false
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
        // Bug 1 修复：先让 ChatStreamCenter 取消正在跑的流并清缓存 + 拒绝后续 saveSnap，
        // 否则被删除 session 在流式过程中的 throttled-save / finally-save 会复活磁盘 JSON 文件。
        center.purge(id)
        viewModelScope.launch { store.delete(id) }
    }

    fun reload() {
        viewModelScope.launch { store.load() }
    }
}
