package com.tongxie.copilotgo.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.ui.theme.AppLayout
import com.tongxie.copilotgo.data.proxy.ProxyType
import com.tongxie.copilotgo.ui.components.FeedbackBanner
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.components.ScreenState
import com.tongxie.copilotgo.ui.settings.ProxyDraft
import com.tongxie.copilotgo.ui.settings.SettingsSection
import com.tongxie.copilotgo.ui.settings.SettingsToggleRow
import com.tongxie.copilotgo.ui.viewmodel.ProxyFormViewModel
import com.tongxie.copilotgo.ui.viewmodel.ProxyViewModel

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsProxyScreen(
    proxyVm: ProxyViewModel,
    onBack: () -> Unit,
    formVm: ProxyFormViewModel = viewModel()
) {
    val savedConfig by proxyVm.config.collectAsStateWithLifecycle()
    val initialized by proxyVm.initialized.collectAsStateWithLifecycle()
    val loadError by proxyVm.loadError.collectAsStateWithLifecycle()
    val saving by proxyVm.saving.collectAsStateWithLifecycle()
    val saveError by proxyVm.error.collectAsStateWithLifecycle()
    val testState by proxyVm.testState.collectAsStateWithLifecycle()
    val form by formVm.state.collectAsStateWithLifecycle()
    var showLeaveDialog by rememberSaveable { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val currentOnBack by rememberUpdatedState(onBack)
    val working = saving || form.saving
    val inputsEnabled = initialized && loadError == null && !working
    val testing = testState is ProxyViewModel.TestState.Testing && form.testMatchesDraft
    val draft = form.draft

    DisposableEffect(formVm, proxyVm) {
        onDispose {
            formVm.cancelAutomaticExit()
            proxyVm.cancelTest()
        }
    }
    LaunchedEffect(savedConfig, initialized, loadError) {
        if (initialized && loadError == null) formVm.receiveSaved(savedConfig)
    }
    LaunchedEffect(form.exitRequested) {
        if (form.exitRequested) {
            formVm.consumeExitRequest()
            showLeaveDialog = false
            proxyVm.cancelTest()
            currentOnBack()
        }
    }

    fun leave() {
        focusManager.clearFocus()
        if (working) {
            formVm.cancelAutomaticExit()
            showLeaveDialog = false
            proxyVm.cancelTest()
            onBack()
        } else if (form.dirty) showLeaveDialog = true else {
            proxyVm.cancelTest()
            onBack()
        }
    }

    fun edit(transform: (ProxyDraft) -> ProxyDraft) {
        if (inputsEnabled && formVm.edit(transform)) {
            proxyVm.resetTestState()
            proxyVm.clearError()
        }
    }

    BackHandler(onBack = ::leave)

    PageScaffold(stringResource(R.string.settings_proxy_title), ::leave) { pageModifier ->
        when {
            !initialized -> ScreenState(
                title = stringResource(R.string.settings_proxy_initializing),
                modifier = pageModifier,
                loading = true
            )
            loadError != null -> ScreenState(
                title = stringResource(R.string.settings_proxy_load_failed),
                modifier = pageModifier,
                actionLabel = stringResource(R.string.settings_action_retry),
                onAction = proxyVm::reload
            )
            draft == null -> ScreenState(
                title = stringResource(R.string.settings_proxy_initializing),
                modifier = pageModifier,
                loading = true
            )
            else -> {
                val validation = draft.validation
                Column(
                    pageModifier.verticalScroll(rememberScrollState()).padding(AppLayout.PageGutter),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    SettingsSection(stringResource(R.string.settings_proxy_saved_title)) {
                        Text(
                            if (savedConfig.enabled) stringResource(
                                R.string.settings_proxy_saved_enabled,
                                savedConfig.type.name, savedConfig.host, savedConfig.port
                            ) else stringResource(R.string.settings_proxy_saved_disabled),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.settings_proxy_saved_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider()
                    SettingsSection(stringResource(R.string.settings_proxy_basic)) {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_proxy_enabled),
                            detail = stringResource(R.string.settings_proxy_scope),
                            checked = draft.enabled,
                            enabled = inputsEnabled,
                            onCheckedChange = { enabled -> edit { it.copy(enabled = enabled) } }
                        )
                        if (!draft.enabled) {
                            Text(
                                stringResource(R.string.settings_proxy_edit_disabled_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(stringResource(R.string.settings_proxy_protocol), style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProxyType.entries.forEach { type ->
                                FilterChip(
                                    selected = draft.type == type,
                                    onClick = { edit { it.copy(type = type) } },
                                    enabled = inputsEnabled,
                                    label = { Text(type.name) },
                                    modifier = Modifier.sizeIn(minHeight = 48.dp)
                                )
                            }
                        }
                        OutlinedTextField(
                            value = draft.host,
                            onValueChange = { value -> edit { it.copy(host = value) } },
                            label = { Text(stringResource(R.string.settings_proxy_host)) },
                            enabled = inputsEnabled,
                            isError = validation.hostInvalid,
                            supportingText = {
                                Text(stringResource(
                                    if (validation.hostInvalid) R.string.settings_proxy_host_invalid
                                    else R.string.settings_proxy_host_hint
                                ))
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = draft.portText,
                            onValueChange = { value -> edit { it.copy(portText = value) } },
                            label = { Text(stringResource(R.string.settings_proxy_port)) },
                            enabled = inputsEnabled,
                            isError = validation.portInvalid,
                            supportingText = {
                                Text(stringResource(
                                    if (validation.portInvalid) R.string.settings_proxy_port_invalid
                                    else R.string.settings_proxy_port_hint
                                ))
                            },
                            keyboardOptions = KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Number,
                                imeAction = if (draft.authenticationEnabled) ImeAction.Next else ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Next) },
                                onDone = { focusManager.clearFocus() }
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    HorizontalDivider()
                    SettingsSection(stringResource(R.string.settings_proxy_authentication)) {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_proxy_authentication_enabled),
                            detail = stringResource(R.string.settings_proxy_authentication_hint),
                            checked = draft.authenticationEnabled,
                            enabled = inputsEnabled,
                            onCheckedChange = { enabled ->
                                passwordVisible = false
                                edit { it.copy(authenticationEnabled = enabled) }
                            }
                        )
                        if (draft.authenticationEnabled) {
                            OutlinedTextField(
                                value = draft.username,
                                onValueChange = { value -> edit { it.copy(username = value) } },
                                label = { Text(stringResource(R.string.settings_proxy_username)) },
                                enabled = inputsEnabled,
                                isError = validation.usernameMissing,
                                supportingText = if (validation.usernameMissing) {
                                    { Text(stringResource(R.string.settings_proxy_username_missing)) }
                                } else null,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = draft.password,
                                onValueChange = { value -> edit { it.copy(password = value, passwordNeedsReentry = false) } },
                                label = { Text(stringResource(R.string.settings_proxy_password)) },
                                enabled = inputsEnabled,
                                isError = validation.passwordMissing,
                                supportingText = {
                                    Text(stringResource(
                                        if (validation.passwordMissing) R.string.settings_proxy_password_reentry
                                        else R.string.settings_proxy_password_hint
                                    ))
                                },
                                keyboardOptions = KeyboardOptions(
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(
                                        onClick = { passwordVisible = !passwordVisible },
                                        enabled = inputsEnabled,
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    ) {
                                        Icon(
                                            if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = stringResource(
                                                if (passwordVisible) R.string.settings_proxy_hide_password
                                                else R.string.settings_proxy_show_password
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            if (validation.passwordMissing) {
                                TextButton(
                                    onClick = { edit { it.copy(password = "", passwordNeedsReentry = false) } },
                                    enabled = inputsEnabled,
                                    modifier = Modifier.sizeIn(minHeight = 48.dp)
                                ) { Text(stringResource(R.string.settings_proxy_use_empty_password)) }
                            }
                            Text(
                                stringResource(R.string.settings_proxy_password_retention),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (draft.type == ProxyType.SOCKS5) {
                                FeedbackBanner(stringResource(R.string.settings_proxy_socks_auth_hint))
                            }
                        }
                    }

                    SettingsSection(stringResource(R.string.settings_proxy_presets)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProxyPreset(R.string.settings_proxy_preset_http, inputsEnabled) {
                                edit { it.copy(type = ProxyType.HTTP, portText = "7890") }
                            }
                            ProxyPreset(R.string.settings_proxy_preset_socks, inputsEnabled) {
                                edit { it.copy(type = ProxyType.SOCKS5, portText = "7891") }
                            }
                            ProxyPreset(R.string.settings_proxy_preset_emulator, inputsEnabled) {
                                edit { it.copy(host = "10.0.2.2") }
                            }
                            ProxyPreset(R.string.settings_proxy_preset_phone, inputsEnabled) {
                                edit { it.copy(host = "127.0.0.1") }
                            }
                        }
                    }

                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (draft.hasOversizedRestorationText) {
                            FeedbackBanner(stringResource(R.string.settings_proxy_large_draft))
                        } else if (form.restoredTextOmitted) {
                            FeedbackBanner(stringResource(R.string.settings_proxy_draft_text_reentry))
                        }
                        if (working) {
                            FeedbackBanner(stringResource(R.string.settings_proxy_saving_background))
                        }
                        if (form.dirty) {
                            Text(
                                stringResource(R.string.settings_proxy_unsaved),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                        if (!validation.isValid) {
                            Text(
                                stringResource(R.string.settings_proxy_fix_fields),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (form.savedNotice) {
                            FeedbackBanner(stringResource(R.string.settings_proxy_save_success))
                        }
                        if ((form.saveFailed || saveError != null) && !working) {
                            FeedbackBanner(stringResource(R.string.settings_proxy_save_failed), isError = true)
                        }
                        Button(
                            onClick = { focusManager.clearFocus(); formVm.save(proxyVm) },
                            enabled = inputsEnabled && form.dirty && validation.isValid,
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                        ) {
                            Text(stringResource(if (working) R.string.settings_saving else R.string.settings_action_save))
                        }
                        OutlinedButton(
                            onClick = { focusManager.clearFocus(); formVm.test(proxyVm) },
                            enabled = inputsEnabled && validation.isValid && !testing,
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                        ) {
                            Text(stringResource(
                                if (testing) R.string.settings_proxy_testing
                                else if (draft.enabled) R.string.settings_proxy_test else R.string.settings_proxy_test_direct
                            ))
                        }
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            TextButton(
                                onClick = proxyVm::cancelTest,
                                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                            ) { Text(stringResource(R.string.settings_proxy_test_cancel)) }
                        }
                        TextButton(
                            onClick = {
                                formVm.reset()
                                proxyVm.resetTestState()
                                proxyVm.clearError()
                                passwordVisible = false
                            },
                            enabled = inputsEnabled && form.dirty,
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                        ) { Text(stringResource(R.string.settings_proxy_reset)) }
                    }

                    val result = testState as? ProxyViewModel.TestState.Result
                    if (result != null && form.testMatchesDraft) {
                        SettingsSection(stringResource(R.string.settings_proxy_test_title)) {
                            FeedbackBanner(stringResource(testFeedbackText(result)), isError = !result.success)
                            Text(
                                stringResource(R.string.settings_proxy_test_snapshot),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    SettingsSection(stringResource(R.string.settings_proxy_help_title)) {
                        Text(
                            stringResource(R.string.settings_proxy_help),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showLeaveDialog && !working) {
        val maximumHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
        BasicAlertDialog(
            onDismissRequest = { if (!working) showLeaveDialog = false },
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp
            ) {
                Column(
                    Modifier.heightIn(max = maximumHeight).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(R.string.settings_proxy_leave_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(stringResource(R.string.settings_proxy_leave_description))
                    if (draft?.validation?.isValid == false) {
                        Text(stringResource(R.string.settings_proxy_fix_fields), color = MaterialTheme.colorScheme.error)
                    }
                    if (form.saveFailed) {
                        Text(
                            stringResource(R.string.settings_proxy_save_failed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = { showLeaveDialog = false },
                            enabled = !working,
                            modifier = Modifier.sizeIn(minHeight = 48.dp)
                        ) { Text(stringResource(R.string.settings_proxy_continue_editing)) }
                        TextButton(
                            onClick = { formVm.save(proxyVm, leaveAfterSave = true) },
                            enabled = inputsEnabled && form.dirty && draft?.validation?.isValid == true,
                            modifier = Modifier.sizeIn(minHeight = 48.dp)
                        ) {
                            Text(stringResource(
                                if (working) R.string.settings_saving else R.string.settings_proxy_save_and_leave
                            ))
                        }
                        TextButton(
                            onClick = {
                                formVm.reset()
                                proxyVm.cancelTest()
                                showLeaveDialog = false
                                onBack()
                            },
                            enabled = !working,
                            modifier = Modifier.sizeIn(minHeight = 48.dp)
                        ) { Text(stringResource(R.string.settings_proxy_discard)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyPreset(label: Int, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(stringResource(label)) },
        modifier = Modifier.sizeIn(minHeight = 48.dp)
    )
}

private fun testFeedbackText(result: ProxyViewModel.TestState.Result): Int = when {
    result.message.contains("先保存 SOCKS5") -> R.string.settings_proxy_socks_auth_hint
    result.message.contains("401") || result.message.contains("403") || result.message.contains("失效") ->
        R.string.settings_proxy_test_auth
    result.message.contains("服务端") -> R.string.settings_proxy_test_server
    result.message.contains("异常响应") -> R.string.settings_proxy_test_gateway
    result.message.contains("超时") -> R.string.settings_proxy_test_timeout
    result.message.contains("DNS") || result.message.contains("解析失败") -> R.string.settings_proxy_test_dns
    result.success -> R.string.settings_proxy_test_response
    else -> R.string.settings_proxy_test_failed
}
