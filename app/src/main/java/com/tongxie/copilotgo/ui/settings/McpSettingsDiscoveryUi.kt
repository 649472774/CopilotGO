package com.tongxie.copilotgo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.tools.McpServerSettings
import com.tongxie.copilotgo.data.tools.ToolSettingsLimits
import com.tongxie.copilotgo.data.tools.mcp.McpDiscoveryState
import com.tongxie.copilotgo.ui.components.FeedbackBanner

internal fun LazyListScope.mcpSettingsDiscoveryItems(
    form: ToolMcpForm,
    saved: McpServerSettings?,
    discovery: McpDiscoveryState?,
    working: Boolean,
    usable: Boolean,
    checking: Boolean,
    cancelled: Boolean,
    onDiscover: () -> Unit,
    onCancel: () -> Unit,
    onToolSelection: (String, Boolean) -> Unit
) {
    val report = currentToolDiscovery(saved, discovery)
    val matches = saved != null && form.canSelectDiscoveredTools(saved.revision, saved.revision)
    val currentState = discovery?.takeIf { it.configRevision == saved?.revision }
    val loading = currentState?.loading == true || checking
    val canSelect = matches && report != null && usable && !working && !loading
    val acceptedNames = report?.tools?.filter { it.canBeSelected() }?.map { it.name }?.toSet().orEmpty()
    val unverifiedNames = form.draft.enabledTools - acceptedNames -
        report?.tools?.filterNot { it.canBeSelected() }?.map { it.name }?.toSet().orEmpty()

    item(key = "mcp-discovery-header") {
        SettingsSection(stringResource(R.string.tool_settings_discovery_title)) {
            Text(stringResource(R.string.tool_settings_discovery_detail), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.tool_settings_tool_readonly_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (!matches) item {
        FeedbackBanner(stringResource(R.string.tool_settings_discovery_save_first))
    }
    if (discovery != null && (
            discovery.configRevision != saved?.revision ||
                (discovery.report != null && report == null && !loading && discovery.problem == null)
            )
    ) item {
        FeedbackBanner(stringResource(R.string.tool_settings_discovery_outdated))
    }
    if (cancelled && !loading) item {
        FeedbackBanner(stringResource(R.string.tool_settings_discovery_stopped))
    }
    item(key = "mcp-discover-action") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onDiscover,
                enabled = matches && usable && !working && !loading,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("tool-discover")
            ) { Text(stringResource(R.string.tool_settings_discover)) }
            if (loading) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text(stringResource(R.string.tool_settings_discovery_running))
                if (checking) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("tool-discover-stop")
                    ) { Text(stringResource(R.string.tool_settings_discovery_stop)) }
                } else {
                    Text(stringResource(R.string.tool_settings_discovery_elsewhere))
                }
            }
        }
    }
    if (currentState?.problem != null) item {
        ToolSettingsProblem(currentState.problem, Modifier.testTag("tool-discovery-error"))
    }
    if (report == null && currentState?.problem == null && !cancelled && !loading) item {
        Text(stringResource(R.string.tool_settings_discovery_unchecked), style = MaterialTheme.typography.bodyLarge)
    }
    if (report != null) {
        item(key = "mcp-discovery-version") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("tool-discovery-current")) {
                Text(
                    stringResource(R.string.tool_settings_discovery_current, report.configRevision, report.protocolVersion),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    stringResource(
                        R.string.tool_settings_discovery_counts,
                        report.tools.count { it.canBeSelected() }, report.tools.count { !it.canBeSelected() }
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
                if (report.tools.isEmpty()) Text(stringResource(R.string.tool_settings_discovery_empty))
            }
        }
        itemsIndexed(report.tools, key = { index, _ -> "mcp-discovered-$index" }) { _, tool ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val selected = tool.name in form.draft.enabledTools
                if (tool.canBeSelected()) {
                    SettingsToggleRow(
                        title = tool.name,
                        detail = stringResource(R.string.tool_settings_tool_enabled_detail),
                        checked = selected,
                        onCheckedChange = { onToolSelection(tool.name, it) },
                        enabled = canSelect && (selected || form.draft.enabledTools.size < ToolSettingsLimits.MAX_SELECTED_TOOLS),
                        modifier = Modifier.testTag("tool-choice-${tool.name}")
                    )
                } else {
                    SettingsToggleRow(
                        title = stringResource(R.string.tool_settings_tool_unsupported, tool.name),
                        detail = stringResource(R.string.tool_settings_tool_unverified_detail),
                        checked = selected,
                        onCheckedChange = { if (!it) onToolSelection(tool.name, false) },
                        enabled = selected && usable && !working && !form.isStale(saved),
                        modifier = Modifier.testTag("tool-unsupported-${tool.name}")
                    )
                    if (tool.problem != null) ToolSettingsProblem(tool.problem)
                    else FeedbackBanner(stringResource(R.string.tool_settings_tool_schema_unavailable), isError = true)
                }
                if (tool.description.isNotBlank()) {
                    Text(tool.description, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    items(unverifiedNames.sorted(), key = { "mcp-unverified-$it" }) { name ->
        SettingsToggleRow(
            title = stringResource(R.string.tool_settings_tool_unverified, name),
            detail = stringResource(R.string.tool_settings_tool_unverified_detail),
            checked = true,
            onCheckedChange = { if (!it) onToolSelection(name, false) },
            enabled = usable && !working && !form.isStale(saved),
            modifier = Modifier.testTag("tool-unverified-$name")
        )
    }
    if (form.draft.enabledTools.size >= ToolSettingsLimits.MAX_SELECTED_TOOLS) item {
        FeedbackBanner(stringResource(R.string.tool_settings_tools_limit))
    }
}
