package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.chat.OperationResult
import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.data.proxy.ProxyType
import com.tongxie.copilotgo.ui.settings.PROXY_SAVED_TEXT_LIMIT
import com.tongxie.copilotgo.ui.settings.ProxyDraft
import com.tongxie.copilotgo.ui.settings.ProxyFormState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProxyFormViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    private val _state = MutableStateFlow(ProxyFormState())
    val state = _state.asStateFlow()

    fun receiveSaved(config: ProxyConfig) {
        val current = _state.value
        _state.value = if (current.saved == null && savedState.get<Boolean>(HAS_DRAFT) == true) {
            val passwordChanged = savedState.get<Boolean>(PASSWORD_CHANGED) == true
            val needsAuthentication = savedState.get<Boolean>(AUTHENTICATION) ?: config.requiresAuth
            current.copy(
                saved = config,
                draft = ProxyDraft(
                    enabled = savedState.get<Boolean>(ENABLED) ?: config.enabled,
                    type = ProxyType.entries.firstOrNull { it.name == savedState.get<String>(TYPE) } ?: config.type,
                    host = restoreText(HOST, config.host),
                    portText = restoreText(PORT, config.port.toString()),
                    authenticationEnabled = needsAuthentication,
                    username = restoreText(USERNAME, config.username),
                    password = if (passwordChanged) "" else config.password,
                    passwordNeedsReentry = passwordChanged && savedState.get<Boolean>(PASSWORD_PRESENT) == true
                ),
                restoredTextOmitted = savedState.get<Boolean>(TEXT_OMITTED) == true || TEXT_KEYS.any {
                    savedState.get<Boolean>(omittedKey(it)) == true ||
                        (savedState.get<String>(it)?.length ?: 0) > PROXY_SAVED_TEXT_LIMIT
                }
            )
        } else {
            current.receiveSaved(config)
        }
        persistNonsecretDraft()
    }

    fun edit(transform: (ProxyDraft) -> ProxyDraft): Boolean {
        val current = _state.value
        val draft = current.draft ?: return false
        val next = current.edit(transform(draft))
        if (next == current) return false
        _state.value = next
        persistNonsecretDraft()
        return true
    }

    fun reset() {
        val current = _state.value
        val saved = current.saved ?: return
        if (current.saving) return
        _state.value = ProxyFormState(saved = saved, draft = ProxyDraft.from(saved))
        persistNonsecretDraft()
    }

    fun save(proxyVm: ProxyViewModel, leaveAfterSave: Boolean = false) {
        val current = _state.value
        if (current.saving || proxyVm.saving.value || !current.dirty) return
        val config = current.draft?.validatedConfig() ?: return
        _state.value = current.copy(
            saving = true, saveFailed = false, savedNotice = false, testedDraft = null, exitAfterSave = leaveAfterSave
        )
        proxyVm.cancelTest()
        viewModelScope.launch {
            try {
                _state.value = when (proxyVm.save(config)) {
                    OperationResult.Accepted -> _state.value.accepted(config, _state.value.exitAfterSave)
                    is OperationResult.Rejected -> _state.value.rejected()
                }
                persistNonsecretDraft()
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(saving = false, exitAfterSave = false)
                throw e
            }
        }
    }

    fun test(proxyVm: ProxyViewModel) {
        val current = _state.value
        if (current.saving || proxyVm.saving.value || proxyVm.testState.value is ProxyViewModel.TestState.Testing) return
        val draft = current.draft ?: return
        val config = draft.validatedConfig() ?: return
        _state.value = current.copy(testedDraft = draft)
        proxyVm.testConfig(config)
    }

    fun consumeExitRequest() {
        _state.value = _state.value.copy(exitRequested = false)
    }

    fun cancelAutomaticExit() {
        _state.value = _state.value.cancelAutomaticExit()
    }

    private fun restoreText(key: String, fallback: String): String {
        if (savedState.get<Boolean>(omittedKey(key)) == true) return ""
        val text = savedState.get<String>(key) ?: return fallback
        return text.takeIf { it.length <= PROXY_SAVED_TEXT_LIMIT } ?: ""
    }

    private fun persistText(key: String, text: String) {
        val omitted = text.length > PROXY_SAVED_TEXT_LIMIT
        savedState[omittedKey(key)] = omitted
        if (omitted) savedState.remove<String>(key) else savedState[key] = text
    }

    private fun persistNonsecretDraft() {
        val current = _state.value
        val draft = current.draft ?: return
        val saved = current.saved ?: return
        if (!current.dirty) {
            KEYS.forEach { savedState.remove<Any>(it) }
            return
        }
        savedState[HAS_DRAFT] = true
        savedState[ENABLED] = draft.enabled
        savedState[TYPE] = draft.type.name
        persistText(HOST, draft.host)
        persistText(PORT, draft.portText)
        savedState[AUTHENTICATION] = draft.authenticationEnabled
        persistText(USERNAME, draft.username)
        savedState[TEXT_OMITTED] = current.restoredTextOmitted
        // Persist only whether the unsaved password needs re-entry, never its contents.
        savedState[PASSWORD_CHANGED] = draft.passwordNeedsReentry || draft.password != saved.password
        savedState[PASSWORD_PRESENT] = draft.passwordNeedsReentry || draft.password.isNotEmpty()
    }

    private companion object {
        const val HAS_DRAFT = "proxy_form_has_draft"
        const val ENABLED = "proxy_form_enabled"
        const val TYPE = "proxy_form_type"
        const val HOST = "proxy_form_host"
        const val PORT = "proxy_form_port"
        const val AUTHENTICATION = "proxy_form_authentication"
        const val USERNAME = "proxy_form_username"
        const val PASSWORD_CHANGED = "proxy_form_password_changed"
        const val PASSWORD_PRESENT = "proxy_form_password_present"
        const val TEXT_OMITTED = "proxy_form_text_omitted"
        val TEXT_KEYS = listOf(HOST, PORT, USERNAME)
        fun omittedKey(key: String) = "${key}_omitted"
        val KEYS = listOf(
            HAS_DRAFT, ENABLED, TYPE, AUTHENTICATION, PASSWORD_CHANGED, PASSWORD_PRESENT, TEXT_OMITTED
        ) + TEXT_KEYS + TEXT_KEYS.map(::omittedKey)
    }
}
