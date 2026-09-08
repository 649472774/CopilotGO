package com.tongxie.copilotgo.ui.screens

import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.ui.components.FeedbackBanner
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.components.ScreenState
import com.tongxie.copilotgo.ui.settings.GITHUB_DEVICE_AUTHORIZATION_URL
import com.tongxie.copilotgo.ui.settings.SettingsSection
import com.tongxie.copilotgo.ui.settings.trustedGitHubAuthorizationUrl
import com.tongxie.copilotgo.ui.viewmodel.AuthViewModel
import com.tongxie.copilotgo.ui.theme.AppLayout
import com.tongxie.copilotgo.util.Logger
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoggedIn: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val initializing by viewModel.initializing.collectAsStateWithLifecycle()
    val loggingOut by viewModel.loggingOut.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val currentOnLoggedIn by rememberUpdatedState(onLoggedIn)
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val activeLogin = !initializing && !loggingOut && (busy || state is AuthState.AwaitingUserAuthorization)

    fun leave() {
        if (activeLogin) {
            viewModel.cancel()
            onBack?.invoke()
        } else {
            onBack?.invoke() ?: dispatcher?.onBackPressed()
        }
    }

    // Without an explicit navigation callback, the first Back cancels; the next is normal system Back.
    BackHandler(enabled = activeLogin) {
        viewModel.cancel()
        onBack?.invoke()
    }

    LaunchedEffect(state, initializing, loggingOut) {
        if (!initializing && !loggingOut && state is AuthState.LoggedIn) currentOnLoggedIn()
    }

    fun startLogin() {
        if (!viewModel.busy.value && !viewModel.initializing.value && !viewModel.loggingOut.value) {
            viewModel.startLogin()
        }
    }

    PageScaffold(stringResource(R.string.settings_login_title), ::leave, snackbar) { pageModifier ->
        if (initializing || loggingOut) {
            ScreenState(
                title = stringResource(
                    if (loggingOut) R.string.settings_account_logging_out
                    else R.string.settings_login_initializing
                ),
                detail = stringResource(
                    if (loggingOut) R.string.settings_account_logout_progress
                    else R.string.settings_login_initializing_detail
                ),
                loading = true,
                modifier = pageModifier
            )
        } else {
            Column(
                pageModifier.verticalScroll(rememberScrollState()).padding(AppLayout.PageGutter),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        stringResource(R.string.settings_login_intro),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (val current = state) {
                    is AuthState.AwaitingUserAuthorization -> {
                        val authorizationUrl = remember(current.verificationUri) {
                            trustedGitHubAuthorizationUrl(current.verificationUri)
                        }
                        SettingsSection(stringResource(R.string.settings_login_code)) {
                            SelectionContainer {
                                Text(
                                    current.userCode,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        textDirection = TextDirection.Ltr
                                    )
                                )
                            }
                            Text(stringResource(R.string.settings_login_code_hint), style = MaterialTheme.typography.bodyLarge)
                            SelectionContainer {
                                Text(
                                    authorizationUrl ?: GITHUB_DEVICE_AUTHORIZATION_URL,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (authorizationUrl == null) {
                                FeedbackBanner(stringResource(R.string.settings_login_invalid_url))
                            }
                            FilledTonalButton(
                                onClick = {
                                    val copied = copyLoginText(
                                        context, context.getString(R.string.settings_login_code_label), current.userCode
                                    )
                                    scope.launch {
                                        snackbar.showSnackbar(context.getString(
                                            if (copied) R.string.settings_login_copied else R.string.settings_login_copy_failed
                                        ))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                            ) { Text(stringResource(R.string.settings_login_copy_code)) }
                            Button(
                                onClick = {
                                    val trusted = trustedGitHubAuthorizationUrl(current.verificationUri) ?: return@Button
                                    val opened = try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(trusted))
                                                .addCategory(Intent.CATEGORY_BROWSABLE)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                        true
                                    } catch (_: ActivityNotFoundException) {
                                        Logger.w("No authorization browser is available")
                                        false
                                    } catch (_: SecurityException) {
                                        Logger.w("Authorization browser launch was denied")
                                        false
                                    }
                                    if (!opened) {
                                        scope.launch {
                                            val result = snackbar.showSnackbar(
                                                context.getString(R.string.settings_login_no_browser),
                                                actionLabel = context.getString(R.string.settings_login_copy_url),
                                                withDismissAction = true
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                val copied = copyLoginText(
                                                    context, context.getString(R.string.settings_login_url_label), trusted
                                                )
                                                snackbar.showSnackbar(context.getString(
                                                    if (copied) R.string.settings_login_url_copied else R.string.settings_login_copy_failed
                                                ))
                                            }
                                        }
                                    }
                                },
                                enabled = authorizationUrl != null,
                                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                            ) { Text(stringResource(R.string.settings_login_open_browser)) }
                        }
                        LoginProgress(stringResource(R.string.settings_login_waiting))
                        Text(
                            stringResource(R.string.settings_login_expiry_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = viewModel::cancel,
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                        ) { Text(stringResource(R.string.settings_login_cancel)) }
                    }
                    is AuthState.LoggedIn -> {
                        Text(
                            stringResource(R.string.settings_login_success),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    else -> {
                        if (busy) {
                            LoginProgress(stringResource(R.string.settings_login_requesting))
                            Text(
                                stringResource(R.string.settings_login_requesting_detail),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = viewModel::cancel,
                                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                            ) { Text(stringResource(R.string.settings_login_cancel)) }
                        } else {
                            if (current is AuthState.Failed) {
                                SettingsSection(stringResource(R.string.settings_login_failed_title)) {
                                    FeedbackBanner(stringResource(loginFailureText(current.message)), isError = true)
                                }
                            }
                            Button(
                                onClick = ::startLogin,
                                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                            ) {
                                Text(stringResource(
                                    if (current is AuthState.Failed) R.string.settings_action_retry
                                    else R.string.settings_login_start
                                ))
                            }
                        }
                    }
                }
                SettingsSection(stringResource(R.string.settings_terms_title)) {
                    Text(
                        stringResource(R.string.settings_login_terms),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginProgress(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        CircularProgressIndicator(Modifier.size(28.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun copyLoginText(context: Context, label: String, value: String): Boolean = try {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) false else {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        true
    }
} catch (_: SecurityException) {
    Logger.w("Authorization clipboard access denied")
    false
} catch (_: IllegalStateException) {
    Logger.w("Authorization clipboard is unavailable")
    false
}

private fun loginFailureText(message: String): Int = when {
    message.contains("过期") || message.contains("expired", ignoreCase = true) -> R.string.settings_login_expired
    message.contains("拒绝") || message.contains("denied", ignoreCase = true) -> R.string.settings_login_denied
    message.contains("频率") || message.contains("rate", ignoreCase = true) -> R.string.settings_login_rate_limited
    message.contains("读取") && message.contains("凭据") -> R.string.settings_login_credential_error
    else -> R.string.settings_login_failed_detail
}
