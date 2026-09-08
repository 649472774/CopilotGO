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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.ui.theme.AppLayout
import com.tongxie.copilotgo.data.tools.CredentialUpdate
import com.tongxie.copilotgo.data.tools.McpAuthMode
import com.tongxie.copilotgo.data.tools.McpNetworkTrust
import com.tongxie.copilotgo.data.tools.McpTransport
import com.tongxie.copilotgo.data.tools.ToolCredentialState
import com.tongxie.copilotgo.data.tools.ToolSettingsLimits
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
import com.tongxie.copilotgo.ui.settings.mcpSettingsDiscoveryItems
import com.tongxie.copilotgo.ui.settings.messageResource
import com.tongxie.copilotgo.ui.settings.rememberToolSecretInput
import com.tongxie.copilotgo.ui.settings.rememberToolSettingsFeedback
import com.tongxie.copilotgo.ui.settings.toolSettingsProblemMessage
import com.tongxie.copilotgo.ui.settings.toolCredentialWillExist
import com.tongxie.copilotgo.ui.viewmodel.ToolSettingsViewModel

@Composable
fun McpServerSettingsScreen(
    viewModel: ToolSettingsViewModel,
    serverId: String?,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val discoveries by viewModel.discovery.collectAsStateWithLifecycle()
    val form = ui.server
    val saved = settings.snapshot?.servers?.firstOrNull { it.id == form?.serverId }
    val secretIdentity = remember(viewModel, serverId) { Any() }
    val secret = rememberToolSecretInput(secretIdentity, ToolSettingsLimits.MAX_CREDENTIAL_CHARS)
    val snackbar = rememberToolSettingsFeedback(ui.problem, settings.problem)
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnBack by rememberUpdatedState(onBack)
    var showDiscard by rememberSaveable(serverId) { mutableStateOf(false) }
    var discardForReset by rememberSaveable(serverId) { mutableStateOf(false) }
    var showDelete by rememberSaveable(serverId) { mutableStateOf(false) }
    val working = ui.pending != null
    val dirty = form?.dirty == true || secret.value.isNotEmpty() || secret.needsReentry || secret.rejectedInput
    val stale = form?.isStale(saved) == true
    val usable = !settings.loading && settings.problem == null
    val inputsEnabled = usable && !working && form != null
    val secretValid = form?.credentialAction != ToolCredentialAction.REPLACE ||
        (isToolCredentialInputValid(secret.value) && !secret.rejectedInput && !secret.needsReentry)
    val credentialPresent = form != null &&
        toolCredentialWillExist(form.credentialAction, form.original?.credentialState ?: ToolCredentialState.MISSING)
    val authenticationReady = form == null || (form.draft.authMode != McpAuthMode.NONE) == credentialPresent

    LaunchedEffect(viewModel, serverId) { viewModel.openServer(serverId) }
    LaunchedEffect(ui.saveSequence) { if (ui.saveSequence > 0) secret.clear() }
    LaunchedEffect(ui.deleted) {
        if (ui.deleted) {
            secret.clear()
            viewModel.consumeDeleted()
            currentOnBack()
        }
    }
    DisposableEffect(viewModel, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.cancelDiscovery()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.cancelDiscovery()
        }
    }

    fun leave() {
        focus.clearFocus()
        viewModel.cancelDiscovery()
        if (!working && dirty) {
            discardForReset = false
            showDiscard = true
        } else {
            secret.clear()
            onBack()
        }
    }
    BackHandler(onBack = ::leave)

    PageScaffold(
        stringResource(
            if (form?.serverId != null || serverId != null) R.string.tool_settings_mcp_edit_title
            else R.string.tool_settings_mcp_add_title
        ),
        ::leave,
        snackbar
    ) { pageModifier ->
        when {
            settings.loading -> ScreenState(
                stringResource(R.string.tool_settings_loading), modifier = pageModifier, loading = true
            )
            settings.problem != null -> ScreenState(
                title = stringResource(R.string.tool_settings_load_failed),
                detail = settings.problem?.let { toolSettingsProblemMessage(it) },
                modifier = pageModifier,
                actionLabel = stringResource(R.string.tool_settings_retry),
                onAction = viewModel::reload
            )
            form == null && ui.problem != null -> ScreenState(
                title = ui.problem?.let { toolSettingsProblemMessage(it) } ?: stringResource(R.string.tool_settings_missing),
                modifier = pageModifier,
                actionLabel = stringResource(R.string.action_back),
                onAction = ::leave
            )
            form == null -> ScreenState(
                stringResource(R.string.tool_settings_loading), modifier = pageModifier, loading = true
            )
            else -> LazyColumn(
                modifier = pageModifier.imePadding().testTag("tool-mcp-settings"),
                contentPadding = PaddingValues(AppLayout.PageGutter),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item {
                    Text(stringResource(R.string.tool_settings_mcp_android), style = MaterialTheme.typography.bodyLarge)
                }
                if (stale) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_stale), isError = true)
                }
                if (saved != null) item {
                    Text(
                        stringResource(R.string.tool_settings_server_revision, saved.revision),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                item {
                    SettingsSection(stringResource(R.string.tool_settings_mcp_basic)) {
                        OutlinedTextField(
                            value = form.draft.label,
                            onValueChange = { value -> viewModel.editServer { it.copy(label = value) } },
                            label = { Text(stringResource(R.string.tool_settings_mcp_label)) },
                            supportingText = {
                                Text(stringResource(form.validation.label?.messageResource() ?: R.string.tool_settings_mcp_label_hint))
                            },
                            isError = form.validation.label != null,
                            enabled = inputsEnabled,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
                            modifier = Modifier.fillMaxWidth().testTag("tool-server-label")
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = form.draft.endpoint,
                        onValueChange = { value -> viewModel.editServer { it.copy(endpoint = value) } },
                        label = { Text(stringResource(R.string.tool_settings_mcp_endpoint)) },
                        supportingText = {
                            Text(stringResource(form.validation.endpoint?.messageResource() ?: R.string.tool_settings_mcp_endpoint_hint))
                        },
                        isError = form.validation.endpoint != null,
                        enabled = inputsEnabled,
                        minLines = 2,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
                        modifier = Modifier.fillMaxWidth().testTag("tool-server-endpoint")
                    )
                }
                item {
                    SettingsToggleRow(
                        title = stringResource(R.string.tool_settings_mcp_enabled),
                        detail = stringResource(R.string.tool_settings_mcp_enabled_detail),
                        checked = form.draft.enabled,
                        onCheckedChange = { value -> viewModel.editServer { it.copy(enabled = value) } },
                        enabled = inputsEnabled,
                        modifier = Modifier.testTag("tool-server-enabled")
                    )
                }
                item {
                    SettingsSection(stringResource(R.string.tool_settings_mcp_transport)) {
                        Column(Modifier.selectableGroup()) {
                            SettingsChoiceRow(
                                title = stringResource(R.string.tool_settings_mcp_streamable),
                                detail = stringResource(R.string.tool_settings_mcp_streamable_detail),
                                selected = form.draft.transport == McpTransport.STREAMABLE_HTTP,
                                onClick = { viewModel.editServer { it.copy(transport = McpTransport.STREAMABLE_HTTP) } },
                                enabled = inputsEnabled,
                                modifier = Modifier.testTag("tool-transport-streamable_http")
                            )
                        }
                        form.validation.transport?.let {
                            FeedbackBanner(stringResource(it.messageResource()), isError = true)
                        }
                    }
                }
                item { HorizontalDivider() }
                item {
                    SettingsSection(stringResource(R.string.tool_settings_network_title)) {
                        Column(Modifier.selectableGroup()) {
                            McpNetworkTrust.entries.forEach { trust ->
                                SettingsChoiceRow(
                                    title = stringResource(
                                        if (trust == McpNetworkTrust.PUBLIC) R.string.tool_settings_network_public
                                        else R.string.tool_settings_network_lan
                                    ),
                                    detail = stringResource(
                                        if (trust == McpNetworkTrust.PUBLIC) R.string.tool_settings_network_public_detail
                                        else R.string.tool_settings_network_lan_detail
                                    ),
                                    selected = form.draft.networkTrust == trust,
                                    onClick = { viewModel.editServer { it.copy(networkTrust = trust) } },
                                    enabled = inputsEnabled,
                                    modifier = Modifier.testTag("tool-network-${trust.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
                item {
                    SettingsSection(stringResource(R.string.tool_settings_auth_title)) {
                        Column(Modifier.selectableGroup()) {
                            McpAuthMode.entries.forEach { mode ->
                                SettingsChoiceRow(
                                    title = stringResource(when (mode) {
                                        McpAuthMode.NONE -> R.string.tool_settings_auth_none
                                        McpAuthMode.BEARER -> R.string.tool_settings_auth_bearer
                                        McpAuthMode.CUSTOM_HEADER -> R.string.tool_settings_auth_custom
                                    }),
                                    detail = stringResource(when (mode) {
                                        McpAuthMode.NONE -> R.string.tool_settings_auth_none_detail
                                        McpAuthMode.BEARER -> R.string.tool_settings_auth_bearer_detail
                                        McpAuthMode.CUSTOM_HEADER -> R.string.tool_settings_auth_custom_detail
                                    }),
                                    selected = form.draft.authMode == mode,
                                    onClick = {
                                        if (mode != form.draft.authMode) {
                                            secret.clear()
                                            if (form.credentialAction == ToolCredentialAction.REPLACE) {
                                                viewModel.setServerCredentialAction(ToolCredentialAction.KEEP)
                                            }
                                            viewModel.editServer { it.copy(authMode = mode) }
                                        }
                                    },
                                    enabled = inputsEnabled,
                                    modifier = Modifier.testTag("tool-auth-${mode.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
                if (form.draft.authMode == McpAuthMode.CUSTOM_HEADER) item {
                    OutlinedTextField(
                        value = form.draft.authHeaderName,
                        onValueChange = { value -> viewModel.editServer { it.copy(authHeaderName = value) } },
                        label = { Text(stringResource(R.string.tool_settings_auth_header)) },
                        supportingText = {
                            Text(stringResource(form.validation.header?.messageResource() ?: R.string.tool_settings_auth_header_hint))
                        },
                        isError = form.validation.header != null,
                        enabled = inputsEnabled,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().testTag("tool-server-header")
                    )
                }
                item {
                    Text(stringResource(R.string.tool_settings_credential_namespace), style = MaterialTheme.typography.bodyLarge)
                }
                item {
                    ToolCredentialEditor(
                        credentialState = saved?.credentialState ?: ToolCredentialState.MISSING,
                        action = form.credentialAction,
                        onActionChange = viewModel::setServerCredentialAction,
                        secret = secret,
                        enabled = inputsEnabled,
                        allowReplacement = form.draft.authMode != McpAuthMode.NONE
                    )
                }
                if (form.needsCredentialDecision) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_credential_decision_required), isError = true)
                }
                if (!secretValid) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_credential_required), isError = true)
                }
                if (!authenticationReady && form.draft.authMode != McpAuthMode.NONE) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_credential_auth_missing), isError = true)
                }
                if (!form.validation.valid) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_fix_fields), isError = true)
                }
                ui.problem?.let { problem -> item { ToolSettingsProblem(problem) } }
                if (working) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_save_background))
                }
                if (dirty) item {
                    Text(stringResource(R.string.tool_settings_unsaved), style = MaterialTheme.typography.bodyLarge)
                }
                if (ui.savedNotice && !dirty) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_saved))
                }
                item {
                    Button(
                        onClick = {
                            focus.clearFocus()
                            viewModel.saveServer(when (form.credentialAction) {
                                ToolCredentialAction.KEEP -> CredentialUpdate.Keep
                                ToolCredentialAction.REMOVE -> CredentialUpdate.Remove
                                ToolCredentialAction.REPLACE -> CredentialUpdate.Replace(secret.value)
                            })
                        },
                        enabled = inputsEnabled && dirty && form.validation.valid &&
                            !form.needsCredentialDecision && secretValid && authenticationReady && !stale,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("tool-settings-save")
                    ) {
                        Text(stringResource(if (working) R.string.tool_settings_saving else R.string.tool_settings_save))
                    }
                }
                item {
                    TextButton(
                        onClick = {
                            focus.clearFocus()
                            viewModel.cancelDiscovery()
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
                item { HorizontalDivider() }
                mcpSettingsDiscoveryItems(
                    form = form,
                    saved = saved,
                    discovery = form.serverId?.let { discoveries[it] },
                    working = working,
                    usable = usable,
                    checking = ui.checking,
                    cancelled = ui.discoveryCancelled,
                    onDiscover = { focus.clearFocus(); viewModel.discover() },
                    onCancel = viewModel::cancelDiscovery,
                    onToolSelection = viewModel::setToolSelected
                )
                if (form.serverId != null) {
                    item { HorizontalDivider() }
                    item {
                        TextButton(
                            onClick = { focus.clearFocus(); showDelete = true },
                            enabled = !working && !stale && usable,
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("tool-delete-server")
                        ) { Text(stringResource(R.string.tool_settings_delete), color = MaterialTheme.colorScheme.error) }
                    }
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
    if (showDelete && form != null) {
        ToolSettingsConfirmation(
            title = stringResource(R.string.tool_settings_delete_title),
            detail = stringResource(R.string.tool_settings_delete_detail, form.original?.label ?: form.draft.label),
            confirmLabel = stringResource(R.string.tool_settings_delete_confirm),
            dismissLabel = stringResource(R.string.tool_settings_cancel),
            onConfirm = {
                showDelete = false
                viewModel.deleteServer()
            },
            onDismiss = { showDelete = false },
            confirmEnabled = !working && !stale && usable
        )
    }
}
