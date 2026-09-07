package com.tongxie.copilotgo.ui.agent

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.tools.SearchProvider
import com.tongxie.copilotgo.data.tools.ToolCredentialState
import com.tongxie.copilotgo.data.tools.ToolSettingsState
import com.tongxie.copilotgo.data.tools.WebProviderDisclosure

internal fun hasConsentedPublicWebTools(state: ToolSettingsState): Boolean {
    if (state.loading || state.problem != null) return false
    val web = state.snapshot?.web ?: return false
    return web.externalSharingConsent && (
        web.pageReaderEnabled || web.searchEnabled &&
            (web.provider == SearchProvider.EXA_KEYLESS || web.credentialState == ToolCredentialState.CONFIGURED)
        )
}

@Composable
internal fun agentToolDisclosure(state: ToolSettingsState): String {
    if (state.loading) return stringResource(R.string.agent_settings_loading)
    val snapshot = state.snapshot
    if (state.problem != null || snapshot == null) return stringResource(R.string.agent_settings_failed)
    val web = snapshot.web
    val lines = mutableListOf(
        stringResource(
            if (web.provider == SearchProvider.EXA_KEYLESS) R.string.agent_provider_keyless
            else R.string.agent_provider_own_key
        ),
        WebProviderDisclosure.SHARING_NOTICE
    )
    if (web.provider == SearchProvider.EXA_KEYLESS) lines += WebProviderDisclosure.EXA_ENDPOINT
    if (!web.searchEnabled && !web.pageReaderEnabled) lines += stringResource(R.string.agent_web_disabled)
    if (!web.externalSharingConsent) lines += stringResource(R.string.agent_web_no_consent)
    if (web.searchEnabled && web.provider == SearchProvider.EXA_API_KEY &&
        web.credentialState != ToolCredentialState.CONFIGURED
    ) {
        lines += stringResource(R.string.agent_web_missing_key)
    }
    lines += stringResource(R.string.agent_mcp_enabled_count, snapshot.servers.count { it.enabled })
    return lines.joinToString("\n\n")
}
