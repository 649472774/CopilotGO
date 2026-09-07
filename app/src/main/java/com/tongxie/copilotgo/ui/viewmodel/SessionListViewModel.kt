package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.data.chat.ChatStreamCenter
import com.tongxie.copilotgo.data.chat.CopilotChatClient
import com.tongxie.copilotgo.data.chat.ModelCatalog
import com.tongxie.copilotgo.data.chat.OperationResult
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.storage.SessionStorageException
import com.tongxie.copilotgo.data.storage.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class SessionListViewModel(
    private val store: SessionStore,
    chatClient: CopilotChatClient,
    authRepo: AuthRepository,
    private val center: ChatStreamCenter,
    private val catalog: ModelCatalog = chatClient.modelCatalog
) : ViewModel() {
    val sessions = store.sessions
    val summaries = store.summaries
    val issues = store.issues
    val loading = store.loading
    val catalogState = catalog.state
    val models = catalog.state.map { it.models }.stateIn(
        viewModelScope, SharingStarted.Eagerly, catalog.state.value.models
    )
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        viewModelScope.launch { operation { store.load() } }
        viewModelScope.launch {
            authRepo.state.filterIsInstance<AuthState.LoggedIn>().collect { refreshModels() }
        }
    }

    fun refreshModels(force: Boolean = false) {
        viewModelScope.launch { catalog.refresh(force) }
    }

    suspend fun createNew(model: String? = null): Session {
        val choices = catalog.state.value.models
        val pick = model ?: choices.firstOrNull { it.isChatDefault }?.id ?: choices.firstOrNull()?.id.orEmpty()
        val created = Session(UUID.randomUUID().toString(), "新会话", pick)
        store.save(created)
        return requireNotNull(store.getSession(created.id))
    }

    suspend fun deleteAndAwait(id: String): OperationResult = operation { store.delete(id) }
    fun delete(id: String) { viewModelScope.launch { deleteAndAwait(id) } }
    suspend fun togglePinAndAwait(id: String): OperationResult = operation {
        store.update(id) { it.copy(pinned = !it.pinned) }
    }
    fun togglePin(id: String) { viewModelScope.launch { togglePinAndAwait(id) } }
    suspend fun renameAndAwait(id: String, title: String): OperationResult = operation { store.rename(id, title) }
    fun rename(id: String, title: String) { viewModelScope.launch { renameAndAwait(id, title) } }
    fun clearError() { _error.value = null }
    fun reload() { viewModelScope.launch { operation { store.load(force = true) } } }

    private suspend fun operation(block: suspend () -> Unit): OperationResult = try {
        block()
        _error.value = null
        OperationResult.Accepted
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val message = when (e) {
            is SessionStorageException -> e.userMessage
            is IllegalArgumentException -> "操作参数无效，请检查标题后重试"
            else -> "会话操作失败，请重试"
        }
        _error.value = message
        OperationResult.Rejected(message)
    }
}
