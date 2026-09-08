package com.tongxie.copilotgo.ui.agent

import android.content.ActivityNotFoundException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.agent.SourceKind
import com.tongxie.copilotgo.data.agent.SourceReference

@Composable
internal fun rememberAgentSourceOpener(onFeedback: (String) -> Unit): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val unavailable = stringResource(R.string.agent_source_open_failed)
    return { url ->
        val destination = agentSourceDestination(url)
        if (destination == null) {
            onFeedback(unavailable)
        } else {
            try {
                uriHandler.openUri(destination)
            } catch (_: ActivityNotFoundException) {
                onFeedback(unavailable)
            } catch (_: SecurityException) {
                onFeedback(unavailable)
            } catch (_: IllegalArgumentException) {
                onFeedback(unavailable)
            }
        }
    }
}

@Composable
internal fun AgentSourceRow(
    source: SourceReference,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val destination = remember(source.url) { agentSourceDestination(source.url) }
    val kind = stringResource(when (source.kind) {
        SourceKind.SEARCH_HIT -> R.string.agent_source_search_hit
        SourceKind.FETCHED_PAGE -> R.string.agent_source_fetched_page
        SourceKind.TOOL_RESOURCE -> R.string.agent_source_tool_resource
    })
    val title = remember(source.title) { agentTextPreview(source.title, 240).text }
    val address = remember(source.url, destination) { agentTextPreview(destination ?: source.url, 2_048) }
    val excerpt = remember(source.excerpt) { source.excerpt?.let { agentTextPreview(it, 480) } }
    val openLabel = stringResource(R.string.agent_open_source, title.ifBlank { address.text })
    Column(
        modifier.fillMaxWidth()
            .clickable(
                enabled = destination != null,
                role = Role.Button,
                onClickLabel = openLabel,
                onClick = { destination?.let(onOpen) }
            )
            .sizeIn(minHeight = 64.dp)
            .padding(vertical = 12.dp)
            .semantics { stateDescription = kind }
            .testTag("agent-source-${source.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            if (source.id.isBlank()) kind else "${agentTextPreview(source.id, 40).text} · $kind",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title.ifBlank { stringResource(R.string.agent_source_untitled) },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (destination != null) Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
        }
        Text(address.text, style = MaterialTheme.typography.bodyMedium)
        excerpt?.let {
            Text(it.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (destination == null) {
            Text(
                stringResource(R.string.agent_source_not_web),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
