package com.tongxie.copilotgo.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.ui.theme.AppLayout
import com.tongxie.copilotgo.data.tools.CredentialUpdate
import com.tongxie.copilotgo.data.tools.SearchProvider
import com.tongxie.copilotgo.data.tools.ToolSettingsLimits
import com.tongxie.copilotgo.ui.agent.rememberAgentSourceOpener
import com.tongxie.copilotgo.ui.components.FeedbackBanner
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.components.ScreenState
import com.tongxie.copilotgo.ui.settings.SettingsChoiceRow
import com.tongxie.copilotgo.ui.settings.SettingsSection
import com.tongxie.copilotgo.ui.settings.SettingsToggleRow
import com.tongxie.copilotgo.ui.settings.ToolCredentialAction
import com.tongxie.copilotgo.ui.settings.ToolCredentialEditor
import com.tongxie.copilotgo.ui.settings.ToolSettingsConfirmation
import com.tongxie.copilotgo.ui.settings.ToolSettingsProblem
import com.tongxie.copilotgo.ui.settings.isToolCredentialInputValid
import com.tongxie.copilotgo.ui.settings.rememberToolSecretInput
import com.tongxie.copilotgo.ui.settings.rememberToolSettingsFeedback
import com.tongxie.copilotgo.ui.settings.toolSettingsProblemMessage
import com.tongxie.copilotgo.ui.settings.toolCredentialWillExist
import com.tongxie.copilotgo.ui.viewmodel.ToolSettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun SearchToolSettingsScreen(
    viewModel: ToolSettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val form = ui.search
    val secret = rememberToolSecretInput(viewModel, ToolSettingsLimits.MAX_CREDENTIAL_CHARS)
    val snackbar = rememberToolSettingsFeedback(ui.problem, settings.problem)
    val scope = rememberCoroutineScope()
    val openDocumentation = rememberAgentSourceOpener { message ->
        scope.launch { snackbar.showSnackbar(message) }
    }
    val focus = LocalFocusManager.current
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var discardForReset by rememberSaveable { mutableStateOf(false) }
    val working = ui.pending != null
    val dirty = form?.dirty == true || secret.value.isNotEmpty() || secret.needsReentry || secret.rejectedInput
    val stale = form?.isStale(settings.snapshot?.web) == true
    val inputsEnabled = !settings.loading && settings.problem == null && !working && form != null
    val secretValid = form?.credentialAction != ToolCredentialAction.REPLACE ||
        (isToolCredentialInputValid(secret.value) && !secret.rejectedInput && !secret.needsReentry)
    val credentialReady = form == null || form.draft.provider == SearchProvider.EXA_KEYLESS ||
        toolCredentialWillExist(form.credentialAction, form.original.credentialState)

    LaunchedEffect(viewModel) { viewModel.openSearch() }
    LaunchedEffect(ui.saveSequence) {
        if (ui.saveSequence > 0) {
            secret.clear()
        }
    }

    fun leave() {
        focus.clearFocus()
        if (!working && dirty) {
            discardForReset = false
            showDiscard = true
        } else {
            secret.clear()
            onBack()
        }
    }
    BackHandler(onBack = ::leave)

    PageScaffold(stringResource(R.string.tool_settings_search_title), ::leave, snackbar) { pageModifier ->
        when {
            settings.loading || (form == null && settings.problem == null) -> ScreenState(
                stringResource(R.string.tool_settings_loading), modifier = pageModifier, loading = true
            )
            settings.problem != null -> ScreenState(
                title = stringResource(R.string.tool_settings_load_failed),
                detail = settings.problem?.let { toolSettingsProblemMessage(it) },
                modifier = pageModifier,
                actionLabel = stringResource(R.string.tool_settings_retry),
                onAction = viewModel::reload
            )
            form != null -> LazyColumn(
                modifier = pageModifier.imePadding().testTag("tool-search-settings"),
                contentPadding = PaddingValues(AppLayout.PageGutter),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.tool_settings_overview_detail),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                if (stale) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_stale), isError = true)
                }
                item {
                    SettingsToggleRow(
                        title = stringResource(R.string.tool_settings_enabled_search),
                        detail = stringResource(R.string.tool_settings_enabled_search_detail),
                        checked = form.draft.searchEnabled,
                        enabled = inputsEnabled,
                        onCheckedChange = { value -> viewModel.editSearch { it.copy(searchEnabled = value) } },
                        modifier = Modifier.testTag("tool-search-enabled")
                    )
                }
                item {
                    SettingsToggleRow(
                        title = stringResource(R.string.tool_settings_enabled_page),
                        detail = stringResource(R.string.tool_settings_enabled_page_detail),
                        checked = form.draft.pageReaderEnabled,
                        enabled = inputsEnabled,
                        onCheckedChange = { value -> viewModel.editSearch { it.copy(pageReaderEnabled = value) } },
                        modifier = Modifier.testTag("tool-page-enabled")
                    )
                }
                item { HorizontalDivider() }
                item {
                    SettingsSection(stringResource(R.string.tool_settings_provider_title)) {
                        Column(Modifier.selectableGroup()) {
                            SearchProvider.entries.forEach { provider ->
                                SettingsChoiceRow(
                                    title = stringResource(
                                        if (provider == SearchProvider.EXA_KEYLESS) R.string.tool_settings_provider_keyless
                                        else R.string.tool_settings_provider_key
                                    ),
                                    detail = stringResource(
                                        if (provider == SearchProvider.EXA_KEYLESS) R.string.tool_settings_provider_keyless_detail
                                        else R.string.tool_settings_provider_key_detail
                                    ),
                                    selected = form.draft.provider == provider,
                                    enabled = inputsEnabled,
                                    onClick = {
                                        if (provider != form.draft.provider) {
                                            secret.clear()
                                            if (form.credentialAction == ToolCredentialAction.REPLACE) {
                                                viewModel.setSearchCredentialAction(ToolCredentialAction.KEEP)
                                            }
                                            viewModel.editSearch { it.copy(provider = provider) }
                                        }
                                    },
                                    modifier = Modifier.testTag("tool-provider-${provider.name.lowercase()}")
                                )
                            }
                        }
                        TextButton(
                            onClick = { openDocumentation("https://exa.ai/docs/reference/exa-mcp") },
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                        ) { Text(stringResource(R.string.tool_settings_provider_docs)) }
                    }
                }
                item {
                    SettingsSection(stringResource(R.string.tool_settings_sharing_title)) {
                        Text(
                            stringResource(R.string.tool_settings_sharing_detail),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        SettingsToggleRow(
                            title = stringResource(R.string.tool_settings_sharing_consent),
                            detail = stringResource(R.string.tool_settings_sharing_consent_detail),
                            checked = form.draft.externalSharingConsent,
                            enabled = inputsEnabled,
                            onCheckedChange = { value ->
                                viewModel.editSearch { it.copy(externalSharingConsent = value) }
                            },
                            modifier = Modifier.testTag("tool-sharing-consent")
                        )
                        if (!form.draft.externalSharingConsent) {
                            FeedbackBanner(stringResource(R.string.tool_settings_sharing_blocked))
                        }
                    }
                }
                item { HorizontalDivider() }
                item {
                    ToolCredentialEditor(
                        credentialState = settings.snapshot?.web?.credentialState ?: form.original.credentialState,
                        action = form.credentialAction,
                        onActionChange = viewModel::setSearchCredentialAction,
                        secret = secret,
                        enabled = inputsEnabled,
                        allowReplacement = form.draft.provider == SearchProvider.EXA_API_KEY
                    )
                }
                if (!secretValid) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_credential_required), isError = true)
                }
                if (!credentialReady) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_key_provider_needs_key), isError = true)
                }
                ui.problem?.let { problem -> item { ToolSettingsProblem(problem) } }
                if (working) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_save_background))
                }
                if (dirty) item {
                    Text(stringResource(R.string.tool_settings_unsaved), style = MaterialTheme.typography.bodyLarge)
                }
                if (ui.savedNotice && !dirty) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_save_search_success))
                }
                item {
                    Button(
                        onClick = {
                            focus.clearFocus()
                            if (secretValid) {
                                viewModel.saveSearch(when (form.credentialAction) {
                                    ToolCredentialAction.KEEP -> CredentialUpdate.Keep
                                    ToolCredentialAction.REMOVE -> CredentialUpdate.Remove
                                    ToolCredentialAction.REPLACE -> CredentialUpdate.Replace(secret.value)
                                })
                            }
                        },
                        enabled = inputsEnabled && dirty && !stale && secretValid && credentialReady,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("tool-settings-save")
                    ) {
                        Text(stringResource(if (working) R.string.tool_settings_saving else R.string.tool_settings_save))
                    }
                }
                item {
                    TextButton(
                        onClick = {
                            focus.clearFocus()
                            if (dirty) {
                                discardForReset = true
                                showDiscard = true
                            } else {
                                secret.clear()
                                viewModel.resetEditor()
                            }
                        },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                    ) { Text(stringResource(R.string.tool_settings_reset)) }
                }
            }
        }
    }

    if (showDiscard) {
        ToolSettingsConfirmation(
            title = stringResource(R.string.tool_settings_leave_title),
            detail = stringResource(R.string.tool_settings_leave_detail),
            confirmLabel = stringResource(R.string.tool_settings_discard),
            dismissLabel = stringResource(R.string.tool_settings_continue),
            onConfirm = {
                showDiscard = false
                secret.clear()
                if (discardForReset) viewModel.resetEditor() else {
                    viewModel.discardEditor()
                    onBack()
                }
            },
            onDismiss = { showDiscard = false }
        )
    }
}
