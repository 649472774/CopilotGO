package com.tongxie.copilotgo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.chat.SessionSummary
import com.tongxie.copilotgo.ui.theme.AppLayout
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListRow(
    session: SessionSummary,
    enabled: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    var expanded by remember(session.id) { mutableStateOf(false) }
    val title = session.title.ifBlank { stringResource(R.string.session_untitled) }
    val pinState = stringResource(if (session.pinned) R.string.session_pinned else R.string.session_not_pinned)
    val date = remember(session.updatedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.updatedAt))
    }
    val details = stringResource(R.string.session_details, session.messageCount, session.model.ifBlank {
        stringResource(R.string.session_model_unselected)
    })
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart && enabled) onDeleteRequest()
        false
    })
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .heightIn(min = 72.dp)
                .clickable(enabled = enabled, onClickLabel = stringResource(R.string.session_open, title), onClick = onOpen)
                .padding(start = AppLayout.PageGutter, end = 8.dp, top = 12.dp, bottom = 12.dp)
                .semantics { stateDescription = "$pinState. $details. $date" }
                .testTag("session_${session.id}"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (session.pinned) Icon(
                        Icons.Default.PushPin, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (session.loadError != null) {
                    Text(
                        stringResource(R.string.session_needs_recovery),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (session.preview.isNotBlank()) {
                    Text(
                        session.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(
                    onClick = { expanded = true },
                    enabled = enabled,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.size(AppLayout.ControlSize)
                ) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.session_actions, title))
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            details, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            date, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(if (session.pinned) R.string.session_unpin else R.string.session_pin)) },
                        enabled = enabled,
                        onClick = { expanded = false; onTogglePin() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.session_rename)) },
                        enabled = enabled,
                        onClick = { expanded = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.session_export)) },
                        enabled = enabled,
                        onClick = { expanded = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        enabled = enabled,
                        onClick = { expanded = false; onDeleteRequest() }
                    )
                }
            }
        }
    }
}
