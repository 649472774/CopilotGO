package com.tongxie.copilotgo.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.tools.ToolCredentialState
import com.tongxie.copilotgo.data.tools.ToolProblem
import com.tongxie.copilotgo.ui.components.FeedbackBanner

@Composable
internal fun ToolSettingsProblem(problem: ToolProblem, modifier: Modifier = Modifier) {
    FeedbackBanner(
        message = toolSettingsProblemMessage(problem),
        isError = true,
        modifier = modifier
    )
}

@Composable
internal fun toolSettingsProblemMessage(problem: ToolProblem): String = problem.httpStatus?.let {
    stringResource(R.string.tool_settings_error_http, problem.message, it)
} ?: problem.message

@Composable
internal fun rememberToolSettingsFeedback(problem: ToolProblem?): SnackbarHostState {
    val snackbar = remember { SnackbarHostState() }
    val message = problem?.let { toolSettingsProblemMessage(it) }
    LaunchedEffect(problem) {
        if (message != null) snackbar.showSnackbar(
            message, withDismissAction = true, duration = SnackbarDuration.Long
        )
    }
    return snackbar
}

@Composable
internal fun ToolCredentialEditor(
    credentialState: ToolCredentialState,
    action: ToolCredentialAction,
    onActionChange: (ToolCredentialAction) -> Unit,
    secret: ToolSecretInputState,
    enabled: Boolean,
    allowReplacement: Boolean
) {
    SettingsSection(stringResource(R.string.tool_settings_credential_title)) {
        Text(
            stringResource(
                if (credentialState == ToolCredentialState.CONFIGURED) R.string.tool_settings_credential_configured
                else R.string.tool_settings_credential_missing
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("tool-credential-status")
        )
        Column(Modifier.selectableGroup()) {
            ToolCredentialAction.entries.forEach { choice ->
                if (choice != ToolCredentialAction.REPLACE || allowReplacement) {
                    SettingsChoiceRow(
                        title = stringResource(when (choice) {
                            ToolCredentialAction.KEEP -> R.string.tool_settings_credential_keep
                            ToolCredentialAction.REPLACE -> R.string.tool_settings_credential_replace
                            ToolCredentialAction.REMOVE -> R.string.tool_settings_credential_remove
                        }),
                        detail = stringResource(when (choice) {
                            ToolCredentialAction.KEEP -> R.string.tool_settings_credential_keep_detail
                            ToolCredentialAction.REPLACE -> R.string.tool_settings_credential_replace_detail
                            ToolCredentialAction.REMOVE -> R.string.tool_settings_credential_remove_detail
                        }),
                        selected = action == choice,
                        onClick = {
                            if (choice != action) {
                                secret.clear()
                                onActionChange(choice)
                            }
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("tool-credential-${choice.name.lowercase()}")
                    )
                }
            }
        }
        if (action == ToolCredentialAction.REPLACE && allowReplacement) {
            ToolSecretInput(
                state = secret,
                label = stringResource(R.string.tool_settings_credential_input),
                enabled = enabled
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolSettingsConfirmation(
    title: String,
    detail: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true
) {
    val maximumHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp
        ) {
            Column(
                Modifier.heightIn(max = maximumHeight).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }
                )
                Text(detail, style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                ) { Text(dismissLabel) }
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                ) { Text(confirmLabel) }
            }
        }
    }
}

@StringRes
internal fun ToolSettingsInputIssue.messageResource(): Int = when (this) {
    ToolSettingsInputIssue.LABEL_REQUIRED -> R.string.tool_settings_label_required
    ToolSettingsInputIssue.LABEL_TOO_LONG -> R.string.tool_settings_label_too_long
    ToolSettingsInputIssue.LABEL_CONTROL_CHARACTERS -> R.string.tool_settings_label_control
    ToolSettingsInputIssue.ENDPOINT_REQUIRED -> R.string.tool_settings_endpoint_required
    ToolSettingsInputIssue.ENDPOINT_TOO_LONG -> R.string.tool_settings_endpoint_too_long
    ToolSettingsInputIssue.ENDPOINT_HTTPS_REQUIRED -> R.string.tool_settings_endpoint_https
    ToolSettingsInputIssue.ENDPOINT_INVALID -> R.string.tool_settings_endpoint_invalid
    ToolSettingsInputIssue.ENDPOINT_USER_INFO -> R.string.tool_settings_endpoint_user_info
    ToolSettingsInputIssue.ENDPOINT_SECRET_QUERY -> R.string.tool_settings_endpoint_secret_query
    ToolSettingsInputIssue.HEADER_REQUIRED -> R.string.tool_settings_header_required
    ToolSettingsInputIssue.HEADER_INVALID -> R.string.tool_settings_header_invalid
    ToolSettingsInputIssue.TOO_MANY_TOOLS -> R.string.tool_settings_tools_limit
    ToolSettingsInputIssue.UNSUPPORTED_TRANSPORT -> R.string.tool_settings_mcp_stdio_invalid
}
