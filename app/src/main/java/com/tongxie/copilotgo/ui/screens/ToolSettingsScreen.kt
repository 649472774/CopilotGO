package com.tongxie.copilotgo.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.tools.SearchProvider
import com.tongxie.copilotgo.data.tools.ToolCredentialState
import com.tongxie.copilotgo.data.tools.ToolSettingsLimits
import com.tongxie.copilotgo.ui.components.FeedbackBanner
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.components.ScreenState
import com.tongxie.copilotgo.ui.settings.SettingsSection
import com.tongxie.copilotgo.ui.settings.SettingsToggleRow
import com.tongxie.copilotgo.ui.settings.ToolSettingsProblem
import com.tongxie.copilotgo.ui.settings.canBeSelected
import com.tongxie.copilotgo.ui.settings.currentToolDiscovery
import com.tongxie.copilotgo.ui.settings.rememberToolSettingsFeedback
import com.tongxie.copilotgo.ui.settings.toolSettingsProblemMessage
import com.tongxie.copilotgo.ui.viewmodel.ToolSettingsViewModel

@Composable
fun ToolSettingsScreen(
    viewModel: ToolSettingsViewModel,
    onOpenSearch: () -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (String) -> Unit,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val discoveries by viewModel.discovery.collectAsStateWithLifecycle()
    val snapshot = settings.snapshot
    val working = ui.pending != null
    val snackbar = rememberToolSettingsFeedback(ui.problem, settings.problem)
    BackHandler(onBack = onBack)

    PageScaffold(stringResource(R.string.tool_settings_title), onBack, snackbar) { pageModifier ->
        when {
            settings.loading -> ScreenState(
                stringResource(R.string.tool_settings_loading), modifier = pageModifier, loading = true
            )
            settings.problem != null || snapshot == null -> ScreenState(
                title = stringResource(R.string.tool_settings_load_failed),
                detail = settings.problem?.let { toolSettingsProblemMessage(it) },
                modifier = pageModifier,
                actionLabel = stringResource(R.string.tool_settings_retry),
                onAction = viewModel::reload
            )
            else -> LazyColumn(
                modifier = pageModifier.testTag("tool-settings-list"),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.tool_settings_overview_detail),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                ui.problem?.let { problem -> item { ToolSettingsProblem(problem) } }
                if (working) item {
                    FeedbackBanner(stringResource(R.string.tool_settings_save_background))
                }
                item {
                    SettingsSection(stringResource(R.string.tool_settings_search_title)) {
                        Text(
                            stringResource(
                                R.string.tool_settings_search_summary,
                                stringResource(if (snapshot.web.searchEnabled) R.string.tool_settings_on else R.string.tool_settings_off),
                                stringResource(if (snapshot.web.pageReaderEnabled) R.string.tool_settings_on else R.string.tool_settings_off),
                                stringResource(
                                    if (snapshot.web.provider == SearchProvider.EXA_KEYLESS) R.string.tool_settings_provider_keyless
                                    else R.string.tool_settings_provider_key
                                ),
                                stringResource(
                                    if (snapshot.web.externalSharingConsent) R.string.tool_settings_consent_yes
                                    else R.string.tool_settings_consent_no
                                )
                            ),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (!snapshot.web.externalSharingConsent) {
                            Text(
                                stringResource(R.string.tool_settings_sharing_blocked),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenSearch,
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("tool-open-search")
                        ) { Text(stringResource(R.string.tool_settings_search_open)) }
                    }
                }
                item { HorizontalDivider() }
                item {
                    SettingsSection(stringResource(R.string.tool_settings_servers_title)) {
                        Text(stringResource(R.string.tool_settings_mcp_android), style = MaterialTheme.typography.bodyLarge)
                        if (snapshot.servers.isEmpty()) {
                            Text(stringResource(R.string.tool_settings_servers_empty), style = MaterialTheme.typography.bodyLarge)
                        }
                        if (snapshot.servers.size >= ToolSettingsLimits.MAX_SERVERS) {
                            Text(stringResource(R.string.tool_settings_servers_limit), style = MaterialTheme.typography.bodyLarge)
                        }
                        Button(
                            onClick = onAddServer,
                            enabled = snapshot.servers.size < ToolSettingsLimits.MAX_SERVERS,
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("tool-add-server")
                        ) { Text(stringResource(R.string.tool_settings_add_server)) }
                    }
                }
                items(snapshot.servers, key = { it.id }) { server ->
                    SettingsSection(server.label, Modifier.testTag("tool-server-${server.id}")) {
                        SettingsToggleRow(
                            title = stringResource(R.string.tool_settings_toggle_server, server.label),
                            detail = stringResource(R.string.tool_settings_server_toggle_detail),
                            checked = server.enabled,
                            enabled = !working,
                            onCheckedChange = { value ->
                                viewModel.setServerEnabled(server.id, server.revision, value)
                            },
                            modifier = Modifier.testTag("tool-server-enabled-${server.id}")
                        )
                        Text(server.endpoint, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.tool_settings_server_revision, server.revision),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(
                                if (server.credentialState == ToolCredentialState.CONFIGURED) R.string.tool_settings_credential_configured
                                else R.string.tool_settings_credential_missing
                            ),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.tool_settings_server_selection, server.enabledTools.size),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        val discovery = discoveries[server.id]
                        val report = currentToolDiscovery(server, discovery)
                        when {
                            discovery?.configRevision != null && discovery.configRevision != server.revision ->
                                Text(stringResource(R.string.tool_settings_discovery_outdated))
                            discovery?.loading == true -> Text(stringResource(R.string.tool_settings_discovery_running))
                            discovery?.problem != null -> ToolSettingsProblem(discovery.problem)
                            report != null -> {
                                Text(
                                    stringResource(
                                        R.string.tool_settings_discovery_current, report.configRevision, report.protocolVersion
                                    ),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    stringResource(
                                        R.string.tool_settings_discovery_counts,
                                        report.tools.count { it.canBeSelected() },
                                        report.tools.count { !it.canBeSelected() }
                                    ),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            else -> Text(stringResource(R.string.tool_settings_discovery_unchecked))
                        }
                        OutlinedButton(
                            onClick = { onEditServer(server.id) },
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                                .testTag("tool-edit-server-${server.id}")
                        ) { Text(stringResource(R.string.tool_settings_edit_server, server.label)) }
                    }
                }
            }
        }
    }
}
