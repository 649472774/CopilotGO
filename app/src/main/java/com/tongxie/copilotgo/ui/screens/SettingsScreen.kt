package com.tongxie.copilotgo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tongxie.copilotgo.BuildConfig
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.data.update.UpdatePrefs
import com.tongxie.copilotgo.ui.components.FeedbackBanner
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.settings.SettingsToggleRow
import com.tongxie.copilotgo.ui.viewmodel.AuthViewModel
import com.tongxie.copilotgo.ui.viewmodel.ProxyViewModel
import com.tongxie.copilotgo.util.Logger
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    authVm: AuthViewModel,
    proxyVm: ProxyViewModel,
    onOpenAccount: () -> Unit,
    onOpenProxy: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit
) {
    val authState by authVm.state.collectAsStateWithLifecycle()
    val authInitializing by authVm.initializing.collectAsStateWithLifecycle()
    val authBusy by authVm.busy.collectAsStateWithLifecycle()
    val loggingOut by authVm.loggingOut.collectAsStateWithLifecycle()
    val proxyConfig by proxyVm.config.collectAsStateWithLifecycle()
    val proxyInitialized by proxyVm.initialized.collectAsStateWithLifecycle()
    val proxyLoadError by proxyVm.loadError.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var updatePrefs by remember { mutableStateOf<UpdatePrefs?>(null) }
    var wifiAutoDownload by remember { mutableStateOf<Boolean?>(null) }
    var wifiSaving by remember { mutableStateOf(false) }
    var preferenceLoadFailed by remember { mutableStateOf(false) }
    var preferenceRetry by remember { mutableStateOf(0) }

    LaunchedEffect(appContext, preferenceRetry) {
        preferenceLoadFailed = false
        try {
            val (prefs, value) = withContext(Dispatchers.IO) {
                val prefs = UpdatePrefs(appContext)
                prefs to prefs.wifiAutoDownload
            }
            updatePrefs = prefs
            wifiAutoDownload = value
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Logger.w("Update preference could not be read")
            preferenceLoadFailed = true
        } catch (_: SecurityException) {
            Logger.w("Update preference access denied")
            preferenceLoadFailed = true
        } catch (_: ClassCastException) {
            Logger.w("Update preference has an incompatible stored type")
            preferenceLoadFailed = true
        }
    }

    val accountSummary = when {
        authInitializing -> stringResource(R.string.settings_login_initializing)
        loggingOut -> stringResource(R.string.settings_account_logging_out)
        authState is AuthState.AwaitingUserAuthorization -> stringResource(R.string.settings_account_waiting)
        authBusy -> stringResource(R.string.settings_login_requesting)
        authState is AuthState.LoggedIn -> stringResource(
            R.string.settings_account_summary,
            (authState as AuthState.LoggedIn).sku?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.settings_account_unknown_sku)
        )
        authState is AuthState.Failed -> stringResource(R.string.settings_account_failed)
        else -> stringResource(R.string.settings_account_logged_out)
    }
    val proxySummary = when {
        !proxyInitialized -> stringResource(R.string.settings_proxy_initializing)
        proxyLoadError != null -> stringResource(R.string.settings_proxy_load_failed)
        proxyConfig.enabled -> stringResource(
            R.string.settings_proxy_saved_enabled, proxyConfig.type.name, proxyConfig.host, proxyConfig.port
        )
        else -> stringResource(R.string.settings_proxy_saved_disabled)
    }

    PageScaffold(stringResource(R.string.settings_title), onBack, snackbar) { pageModifier ->
        Column(
            pageModifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsMenuRow(Icons.Filled.AccountCircle, stringResource(R.string.settings_account_title), accountSummary, onOpenAccount)
            SettingsMenuRow(Icons.Filled.Settings, stringResource(R.string.settings_proxy_title), proxySummary, onOpenProxy)

            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            val loadedWifi = wifiAutoDownload
            when {
                preferenceLoadFailed -> FeedbackBanner(
                    stringResource(R.string.settings_update_preferences_failed),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    isError = true,
                    actionLabel = stringResource(R.string.settings_action_retry),
                    onAction = { preferenceRetry++ }
                )
                loadedWifi == null -> Text(
                    stringResource(R.string.settings_update_preferences_loading),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> SettingsToggleRow(
                    title = stringResource(R.string.settings_update_wifi_title),
                    detail = stringResource(
                        if (wifiSaving) R.string.settings_saving else R.string.settings_update_wifi_detail
                    ),
                    checked = loadedWifi,
                    enabled = !wifiSaving,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    onCheckedChange = { enabled ->
                        val prefs = updatePrefs
                        if (!wifiSaving && prefs != null) {
                            wifiSaving = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { prefs.wifiAutoDownload = enabled }
                                    wifiAutoDownload = enabled
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: IOException) {
                                    Logger.w("Update preference could not be saved")
                                    snackbar.showSnackbar(appContext.getString(R.string.settings_update_preferences_save_failed))
                                } catch (_: SecurityException) {
                                    Logger.w("Update preference save was denied")
                                    snackbar.showSnackbar(appContext.getString(R.string.settings_update_preferences_save_failed))
                                } finally {
                                    wifiSaving = false
                                }
                            }
                        }
                    }
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingsMenuRow(
                Icons.Filled.Folder,
                stringResource(R.string.settings_storage_title),
                stringResource(R.string.settings_storage_summary),
                onOpenStorage
            )
            SettingsMenuRow(
                Icons.Filled.Info,
                stringResource(R.string.settings_about_title),
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                onOpenAbout
            )
        }
    }
}

@Composable
private fun SettingsMenuRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).sizeIn(minHeight = 64.dp),
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}
