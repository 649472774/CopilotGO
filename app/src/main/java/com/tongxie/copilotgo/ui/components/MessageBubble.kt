package com.tongxie.copilotgo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.data.agent.AgentRunRecord
import com.tongxie.copilotgo.ui.agent.AgentMessageActivity
import com.tongxie.copilotgo.ui.agent.AgentSourceRow
import com.tongxie.copilotgo.ui.agent.rememberAgentSourceOpener
import com.tongxie.copilotgo.ui.markdown.LocalMarkdownCitations

@Composable
fun MessageBubble(
    message: UiMessage,
    onRegenerate: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    attachments: List<UiAttachment> = emptyList(),
    actionsEnabled: Boolean = true,
    replayBlocked: Boolean = false,
    onReviewAgent: ((AgentRunRecord) -> Unit)? = null,
    onOpenAgentSource: ((String) -> Unit)? = null,
    citationLinks: Map<String, String> = emptyMap(),
    onFeedback: (String) -> Unit = {}
) {
    val isUser = message.role == "user"
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.message_copied)
    val copyTooLarge = stringResource(R.string.message_copy_too_large)
    val copyFailed = stringResource(R.string.message_copy_failed)
    val author = stringResource(if (isUser) R.string.message_author_user else R.string.message_author_assistant)
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    var preview by remember(message.id) { mutableStateOf<UiAttachment?>(null) }
    val openSource = rememberAgentSourceOpener(onFeedback)
    val copyMessage = {
        onFeedback(when (copyText(clipboard, message.content)) {
            TextCopyResult.Copied -> copied
            TextCopyResult.TooLong -> copyTooLarge
            TextCopyResult.Unavailable -> copyFailed
        })
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { isTraversalGroup = true },
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                author,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (message.content.isNotEmpty()) {
                IconButton(onClick = copyMessage) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.message_copy, author)
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.message_actions, author)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (onShare != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.message_share)) },
                            onClick = { menuOpen = false; onShare() }
                        )
                    }
                    onRegenerate?.let { regenerate ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.message_regenerate)) },
                            enabled = actionsEnabled && !replayBlocked,
                            onClick = { menuOpen = false; regenerate() }
                        )
                    }
                    onEdit?.let { edit ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.message_edit)) },
                            enabled = actionsEnabled && !replayBlocked,
                            onClick = { menuOpen = false; edit() }
                        )
                    }
                    onDelete?.let { delete ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            enabled = actionsEnabled && !replayBlocked,
                            onClick = { menuOpen = false; delete() }
                        )
                    }
                    if (!actionsEnabled) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.message_actions_busy)) },
                            enabled = false,
                            onClick = {}
                        )
                    }
                    if (replayBlocked && (onEdit != null || onRegenerate != null)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.agent_protected_history)) },
                            enabled = false,
                            onClick = {}
                        )
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.widthIn(max = if (isUser) 640.dp else 760.dp),
            shape = MaterialTheme.shapes.medium,
            color = if (isUser) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (isUser) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (attachments.isNotEmpty()) {
                    AttachmentStrip(
                        attachments = attachments,
                        onPreview = { preview = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (message.imageUrls.isNotEmpty()) {
                    Text(
                        stringResource(R.string.message_legacy_images, message.imageUrls.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                message.agentRun?.let { run ->
                    AgentMessageActivity(run, onReviewAgent?.let { review -> { review(run) } })
                    if (message.content.isNotEmpty()) HorizontalDivider()
                }
                if (message.content.isEmpty() && message.isStreaming && message.agentRun == null) {
                    TypingDots()
                } else if (message.content.isNotEmpty()) {
                    CompositionLocalProvider(LocalMarkdownCitations provides citationLinks) {
                        SimpleMarkdownText(
                            markdown = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            isStreaming = message.isStreaming,
                            onFeedback = onFeedback
                        )
                    }
                } else if (attachments.isEmpty() && message.imageUrls.isEmpty() && message.agentRun == null) {
                    Text(
                        stringResource(R.string.message_empty),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            message.agentRun?.takeIf { it.sources.isNotEmpty() }?.let { run ->
                Column(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.agent_sources_title), style = MaterialTheme.typography.titleMedium)
                    run.sources.take(4).forEach { source ->
                        AgentSourceRow(source, onOpenAgentSource ?: openSource)
                    }
                    if (run.sources.size > 4 && onReviewAgent != null) {
                        TextButton(onClick = { onReviewAgent(run) }) {
                            Text(stringResource(R.string.agent_sources_more, run.sources.size))
                        }
                    }
                }
            }
        }
    }
    preview?.let { AttachmentPreviewDialog(it, onDismiss = { preview = null }) }
}
