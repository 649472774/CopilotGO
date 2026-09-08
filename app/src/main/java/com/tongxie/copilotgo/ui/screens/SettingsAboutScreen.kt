package com.tongxie.copilotgo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tongxie.copilotgo.BuildConfig
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.components.UpdateDialog
import com.tongxie.copilotgo.ui.settings.SettingsSection
import com.tongxie.copilotgo.ui.viewmodel.UpdateViewModel
import com.tongxie.copilotgo.ui.theme.AppLayout

@Composable
fun SettingsAboutScreen(updateVm: UpdateViewModel, onBack: () -> Unit) {
    val updateState by updateVm.state.collectAsStateWithLifecycle()
    val checking = updateState is UpdateViewModel.State.Checking
    UpdateDialog(state = updateState, vm = updateVm)

    PageScaffold(stringResource(R.string.settings_about_title), onBack) { pageModifier ->
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
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (checking) CircularProgressIndicator(Modifier.size(28.dp))
                Button(
                    onClick = { updateVm.check(manual = true) },
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                ) {
                    Text(stringResource(
                        if (checking) R.string.settings_about_checking_updates else R.string.settings_about_check_updates
                    ))
                }
            }
            HorizontalDivider()
            SettingsSection(stringResource(R.string.settings_about_project_title)) {
                Text(
                    stringResource(R.string.settings_about_project_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SettingsSection(stringResource(R.string.settings_about_disclaimer_title)) {
                Text(
                    stringResource(R.string.settings_about_disclaimer_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                stringResource(R.string.settings_about_copyright),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
