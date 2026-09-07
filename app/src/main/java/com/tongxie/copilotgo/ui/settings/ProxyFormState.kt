package com.tongxie.copilotgo.ui.settings

import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.data.proxy.ProxyType

const val PROXY_SAVED_TEXT_LIMIT = 1024

data class ProxyDraft(
    val enabled: Boolean,
    val type: ProxyType,
    val host: String,
    val portText: String,
    val authenticationEnabled: Boolean,
    val username: String,
    val password: String,
    val passwordNeedsReentry: Boolean = false
) {
    val hasOversizedRestorationText: Boolean
        get() = host.length > PROXY_SAVED_TEXT_LIMIT || portText.length > PROXY_SAVED_TEXT_LIMIT ||
            username.length > PROXY_SAVED_TEXT_LIMIT

    val validation: ProxyFormValidation
        get() {
            val port = parseProxyPort(portText)
            return ProxyFormValidation(
                hostInvalid = !validProxyHost(host),
                portInvalid = port == null,
                usernameMissing = authenticationEnabled && username.isBlank(),
                passwordMissing = authenticationEnabled && passwordNeedsReentry
            )
        }

    fun validatedConfig(): ProxyConfig? {
        if (!validation.isValid) return null
        return ProxyConfig(
            enabled = enabled,
            type = type,
            host = host,
            port = parseProxyPort(portText) ?: return null,
            username = if (authenticationEnabled) username else "",
            password = if (authenticationEnabled) password else ""
        ).takeIf { it.isValid() }
    }

    // Avoid leaking the memory-only password through incidental diagnostics.
    override fun toString(): String = "ProxyDraft(enabled=$enabled, type=$type)"

    companion object {
        fun from(config: ProxyConfig) = ProxyDraft(
            enabled = config.enabled,
            type = config.type,
            host = config.host,
            portText = config.port.toString(),
            authenticationEnabled = config.requiresAuth,
            username = config.username,
            password = config.password
        )
    }
}

data class ProxyFormValidation(
    val hostInvalid: Boolean,
    val portInvalid: Boolean,
    val usernameMissing: Boolean,
    val passwordMissing: Boolean
) {
    val isValid: Boolean
        get() = !hostInvalid && !portInvalid && !usernameMissing && !passwordMissing
}

fun parseProxyPort(text: String): Int? {
    if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
    return text.toIntOrNull()?.takeIf { it in 1..65535 }
}

private fun validProxyHost(host: String): Boolean {
    if (host.length !in 1..253 || !ProxyConfig(host = host, port = 1).isValid()) return false
    if (':' in host) return true
    if (!host.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '.'
    }) return false
    val labels = host.split('.')
    if (labels.any { it.isEmpty() || it.length > 63 || it.first() == '-' || it.last() == '-' }) return false
    if (labels.size > 1 && labels.all { label -> label.all { it in '0'..'9' } }) {
        if (labels.size != 4 || labels.any { it.length > 3 || it.toInt() !in 0..255 }) return false
    }
    return true
}

data class ProxyFormState(
    val saved: ProxyConfig? = null,
    val draft: ProxyDraft? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val savedNotice: Boolean = false,
    val exitRequested: Boolean = false,
    val testedDraft: ProxyDraft? = null,
    val restoredTextOmitted: Boolean = false,
    val exitAfterSave: Boolean = false
) {
    val dirty: Boolean
        get() = saved != null && draft != null && (restoredTextOmitted || draft != ProxyDraft.from(saved))

    val testMatchesDraft: Boolean
        get() = draft != null && testedDraft == draft

    fun receiveSaved(config: ProxyConfig): ProxyFormState {
        if (saving) return this
        return if (dirty) copy(saved = config)
        else copy(saved = config, draft = ProxyDraft.from(config), testedDraft = null)
    }

    fun edit(next: ProxyDraft): ProxyFormState {
        if (saving || next == draft) return this
        return copy(draft = next, saveFailed = false, savedNotice = false, testedDraft = null)
    }

    fun accepted(config: ProxyConfig, leave: Boolean): ProxyFormState = copy(
        saved = config,
        draft = ProxyDraft.from(config),
        saving = false,
        saveFailed = false,
        savedNotice = true,
        exitRequested = leave,
        testedDraft = null,
        restoredTextOmitted = false,
        exitAfterSave = false
    )

    fun rejected(): ProxyFormState = copy(saving = false, saveFailed = true, savedNotice = false, exitAfterSave = false)

    fun cancelAutomaticExit(): ProxyFormState = copy(exitAfterSave = false, exitRequested = false)

    override fun toString(): String = "ProxyFormState(dirty=$dirty, saving=$saving)"
}
