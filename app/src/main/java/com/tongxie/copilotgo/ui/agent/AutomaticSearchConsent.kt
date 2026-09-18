package com.tongxie.copilotgo.ui.agent

import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import com.tongxie.copilotgo.data.tools.ToolSettingsState
import com.tongxie.copilotgo.data.tools.WebToolSettings
import com.tongxie.copilotgo.ui.draft.ComposerDraft

internal data class AutomaticSearchSubmission(
    val sessionId: String,
    val draft: ComposerDraft,
    val agentSettings: AgentSessionSettings
) {
    fun matches(
        currentSessionId: String?,
        currentDraft: ComposerDraft,
        currentSettings: AgentSessionSettings?
    ): Boolean = sessionId == currentSessionId && draft == currentDraft && agentSettings == currentSettings
}

internal fun readableAutomaticSearchSettings(state: ToolSettingsState): WebToolSettings? =
    state.snapshot?.web?.takeIf { !state.loading && state.problem == null }

internal fun hasAutomaticSearchConsent(state: ToolSettingsState): Boolean {
    val web = readableAutomaticSearchSettings(state) ?: return false
    return web.searchEnabled && web.externalSharingConsent && web.automaticSearchConsent
}
