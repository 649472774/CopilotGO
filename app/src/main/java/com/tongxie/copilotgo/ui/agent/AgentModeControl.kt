package com.tongxie.copilotgo.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import com.tongxie.copilotgo.data.chat.ModelInfo
import com.tongxie.copilotgo.ui.components.FeedbackBanner
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.settings.SettingsChoiceRow
import com.tongxie.copilotgo.ui.settings.SettingsSection
import com.tongxie.copilotgo.ui.settings.SettingsToggleRow

@Composable
internal fun AgentModeButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
    val label = stringResource(if (enabled) R.string.agent_mode_agent else R.string.agent_mode_chat)
    val description = stringResource(R.string.agent_mode_current, label)
    TextButton(
        onClick = onClick,
        enabled = interactive,
        modifier = modifier.sizeIn(minHeight = 48.dp)
            .semantics { stateDescription = description }.testTag(AgentTags.MODE)
    ) { Text(label) }
}

@Composable
internal fun AgentModeDialog(
    settings: AgentSessionSettings,
    model: ModelInfo?,
    providerDisclosure: String,
    publicWebReady: Boolean,
    saving: Boolean,
    error: String?,
    onSave: (AgentSessionSettings, AgentSessionSettings) -> Unit,
    onOpenTools: () -> Unit,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            AgentModeContent(
                settings, model, providerDisclosure, publicWebReady, saving, error,
                onSave, onOpenTools, onClose
            )
        }
    }
}

@Composable
internal fun AgentModeContent(
    settings: AgentSessionSettings,
    model: ModelInfo?,
    providerDisclosure: String,
    publicWebReady: Boolean,
    saving: Boolean,
    error: String?,
    onSave: (AgentSessionSettings, AgentSessionSettings) -> Unit,
    onOpenTools: () -> Unit,
    onBack: () -> Unit
) {
    val base = remember { settings }
    var draft by remember { mutableStateOf(settings) }
    val disabledReason = agentModelDisabledReason(model)
    val stale = settings != base
    PageScaffold(stringResource(R.string.agent_mode_title), onBack) { pageModifier ->
        Column(
            pageModifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(Modifier.selectableGroup()) {
                SettingsChoiceRow(
                    title = stringResource(R.string.agent_mode_chat),
                    detail = stringResource(R.string.agent_chat_detail),
                    selected = !draft.enabled,
                    enabled = !saving && !stale,
                    onClick = { draft = draft.copy(enabled = false, autoApprovePublicWebReads = false) }
                )
                SettingsChoiceRow(
                    title = stringResource(R.string.agent_mode_agent),
                    detail = stringResource(disabledReason ?: R.string.agent_mode_detail),
                    selected = draft.enabled,
                    enabled = !saving && !stale && disabledReason == null,
                    modifier = Modifier.testTag("agent-mode-choice"),
                    onClick = { draft = draft.copy(enabled = true) }
                )
            }
            if (draft.enabled) {
                HorizontalDivider()
                SettingsSection(stringResource(R.string.agent_external_scope_title)) {
                    Text(providerDisclosure, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.agent_external_scope), style = MaterialTheme.typography.bodyMedium)
                    SettingsToggleRow(
                        title = stringResource(R.string.agent_auto_public_reads),
                        detail = stringResource(
                            if (publicWebReady) R.string.agent_auto_public_reads_detail
                            else R.string.agent_auto_public_reads_unavailable
                        ),
                        checked = draft.autoApprovePublicWebReads,
                        enabled = !saving && !stale && publicWebReady,
                        onCheckedChange = { draft = draft.copy(autoApprovePublicWebReads = it) }
                    )
                    Text(stringResource(R.string.agent_mcp_always_asks), style = MaterialTheme.typography.bodyMedium)
                }
                SettingsSection(stringResource(R.string.agent_limits_title)) {
                    val limits = draft.limits
                    Text(
                        stringResource(
                            R.string.agent_limits_execution,
                            limits.maxSteps, limits.maxToolCalls, agentDurationSeconds(limits.maxDurationMillis),
                            agentDurationSeconds(limits.toolTimeoutMillis), agentDurationSeconds(limits.approvalTimeoutMillis)
                        ),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(
                            R.string.agent_limits_output,
                            limits.maxArgumentBytes, limits.maxResultBytes, limits.maxTotalResultBytes,
                            limits.maxContextBytes
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(stringResource(R.string.agent_limits_preserve), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (stale) FeedbackBanner(stringResource(R.string.agent_mode_stale), isError = true)
            error?.let { FeedbackBanner(agentTextPreview(it, 2_048).text, isError = true) }
            Button(
                onClick = { if (draft == settings) onBack() else onSave(draft, base) },
                enabled = !saving && !stale && (!draft.enabled || disabledReason == null),
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("agent-mode-save")
            ) { Text(stringResource(if (saving) R.string.state_saving else R.string.agent_mode_apply)) }
            OutlinedButton(
                onClick = { onBack(); onOpenTools() },
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
            ) { Text(stringResource(R.string.agent_tools_settings)) }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                Text(stringResource(R.string.action_back))
            }
        }
    }
}
