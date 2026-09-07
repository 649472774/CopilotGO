package com.tongxie.copilotgo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.ui.components.ConfirmActionDialog
import com.tongxie.copilotgo.ui.components.FeedbackBanner
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.components.ScreenState
import com.tongxie.copilotgo.ui.settings.SettingsSection
import com.tongxie.copilotgo.ui.viewmodel.AccountActionsViewModel
import com.tongxie.copilotgo.ui.viewmodel.AuthViewModel

@Composable
fun SettingsAccountScreen(
    authVm: AuthViewModel,
    onLoggedOut: () -> Unit,
    onBack: () -> Unit,
    actionsVm: AccountActionsViewModel = viewModel()
) {
    val state by authVm.state.collectAsStateWithLifecycle()
    val initializing by authVm.initializing.collectAsStateWithLifecycle()
    val loggingOut by authVm.loggingOut.collectAsStateWithLifecycle()
    val actionState by actionsVm.state.collectAsStateWithLifecycle()
    val currentOnLoggedOut by rememberUpdatedState(onLoggedOut)
    val working = loggingOut || actionState == AccountActionsViewModel.State.Working
    val logoutFailed = actionState == AccountActionsViewModel.State.Failed ||
        actionState == AccountActionsViewModel.State.FailureDismissed
    val showConfirmation = !working && (actionState == AccountActionsViewModel.State.Confirming ||
        actionState == AccountActionsViewModel.State.Failed)

    LaunchedEffect(actionState) {
        if (actionState == AccountActionsViewModel.State.Completed) {
            actionsVm.dismiss()
            currentOnLoggedOut()
        }
    }
    PageScaffold(stringResource(R.string.settings_account_title), onBack = onBack) { pageModifier ->
        if (initializing) {
            ScreenState(
                title = stringResource(R.string.settings_login_initializing),
                loading = true,
                modifier = pageModifier
            )
        } else {
            Column(
                pageModifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                SettingsSection(stringResource(R.string.settings_account_native)) {
                    Text(
                        stringResource(when {
                            working -> R.string.settings_account_logging_out
                            state is AuthState.LoggedIn -> R.string.settings_account_logged_in
                            state is AuthState.AwaitingUserAuthorization -> R.string.settings_account_waiting
                            state is AuthState.Failed -> R.string.settings_account_failed
                            else -> R.string.settings_account_logged_out
                        }),
                        style = MaterialTheme.typography.titleLarge
                    )
                    val loggedIn = state as? AuthState.LoggedIn
                    if (loggedIn != null) {
                        SelectionContainer {
                            Text(
                                stringResource(
                                    R.string.settings_account_sku,
                                    loggedIn.sku?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.settings_account_unknown_sku)
                                ),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.settings_account_remote_note),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state is AuthState.Failed) {
                    FeedbackBanner(
                        stringResource(
                            if (logoutFailed) R.string.settings_account_logout_failed
                            else R.string.settings_login_failed_detail
                        ),
                        isError = true
                    )
                }
                if (working) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    FeedbackBanner(stringResource(R.string.settings_account_logout_background))
                }
                Text(
                    stringResource(R.string.settings_account_logout_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = actionsVm::confirm,
                    enabled = !working && state != AuthState.NotLoggedIn,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                ) {
                    Text(stringResource(
                        if (working) R.string.settings_account_logging_out else R.string.settings_account_logout
                    ))
                }
            }
        }
    }

    if (showConfirmation) {
        ConfirmActionDialog(
            title = stringResource(R.string.settings_account_logout_title),
            description = stringResource(R.string.settings_account_logout_description),
            confirmLabel = stringResource(
                if (actionState == AccountActionsViewModel.State.Failed) R.string.settings_action_retry
                else R.string.settings_account_logout
            ),
            onConfirm = {
                if (!authVm.loggingOut.value) actionsVm.logout(authVm::logoutAndAwait)
            },
            onDismiss = actionsVm::dismiss,
            error = if (actionState == AccountActionsViewModel.State.Failed) {
                stringResource(R.string.settings_account_logout_failed)
            } else null
        )
    }
}
