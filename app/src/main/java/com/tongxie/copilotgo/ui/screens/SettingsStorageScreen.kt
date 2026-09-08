package com.tongxie.copilotgo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.storage.AppPaths
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.components.ScreenState
import com.tongxie.copilotgo.ui.settings.SettingsSection
import com.tongxie.copilotgo.ui.theme.AppLayout
import com.tongxie.copilotgo.util.Logger
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsStorageScreen(
    paths: AppPaths,
    onBack: () -> Unit,
    onOpenFiles: (() -> Unit)? = null
) {
    var retry by remember { mutableStateOf(0) }
    val pathState by produceState<StoragePathsState>(StoragePathsState.Loading, paths, retry) {
        value = StoragePathsState.Loading
        value = try {
            withContext(Dispatchers.IO) { StoragePathsState.Loaded(paths.describe()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Logger.w("Storage description could not be read")
            StoragePathsState.Failed
        } catch (_: SecurityException) {
            Logger.w("Storage description access denied")
            StoragePathsState.Failed
        }
    }

    PageScaffold(stringResource(R.string.settings_storage_title), onBack) { pageModifier ->
        when (val current = pathState) {
            StoragePathsState.Loading -> ScreenState(
                title = stringResource(R.string.settings_storage_loading),
                modifier = pageModifier,
                loading = true
            )
            StoragePathsState.Failed -> ScreenState(
                title = stringResource(R.string.settings_storage_failed),
                modifier = pageModifier,
                actionLabel = stringResource(R.string.settings_action_retry),
                onAction = { retry++ }
            )
            is StoragePathsState.Loaded -> Column(
                pageModifier.verticalScroll(rememberScrollState()).padding(AppLayout.PageGutter),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                SettingsSection(stringResource(R.string.settings_storage_paths)) {
                    SelectionContainer {
                        Text(
                            current.description,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                SettingsSection(stringResource(R.string.settings_storage_protected_title)) {
                    Text(
                        stringResource(R.string.settings_storage_protected_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.settings_storage_manage_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onOpenFiles != null) {
                    OutlinedButton(
                        onClick = onOpenFiles,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                    ) { Text(stringResource(R.string.settings_storage_open_files)) }
                }
            }
        }
    }
}

private sealed interface StoragePathsState {
    data object Loading : StoragePathsState
    data object Failed : StoragePathsState
    data class Loaded(val description: String) : StoragePathsState
}
