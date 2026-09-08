package com.tongxie.copilotgo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.ui.theme.AppLayout

object ChatTags {
    const val INPUT = "chat_input"
    const val EDITOR_VIEWPORT = "chat_editor_viewport"
    const val SEND = "chat_send"
    const val STOP = "chat_stop"
    const val ADD = "chat_add"
    const val VOICE = "chat_voice"
    const val HEADER = "chat_header"
    const val ATTACHMENTS = "chat_attachments"
    const val MESSAGES = "chat_messages"
    const val LATEST = "chat_latest"
}

@Composable
fun ChatComposer(
    text: String,
    attachments: List<UiAttachment>,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickText: () -> Unit,
    onPickImages: () -> Unit,
    onVoice: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onPreviewAttachment: (UiAttachment) -> Unit,
    modifier: Modifier = Modifier,
    sending: Boolean = false,
    submitting: Boolean = false,
    importing: Boolean = false,
    enabled: Boolean = true,
    submissionEnabled: Boolean = true,
    compactHeight: Boolean = false,
    supportingText: String? = null,
    notice: @Composable () -> Unit = {}
) {
    var addMenu by remember { mutableStateOf(false) }
    val canEdit = enabled && !submitting
    val canSubmit = canEdit && submissionEnabled && !importing && (text.isNotBlank() || attachments.isNotEmpty())
    val editorInteraction = remember { MutableInteractionSource() }
    val editorLabel = stringResource(R.string.composer_label)
    val streamingDescription = stringResource(R.string.chat_draft_while_sending)

    Surface(
        modifier = modifier.padding(horizontal = AppLayout.ComposerGutter, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = AppLayout.ComposerShape
    ) {
        BoxWithConstraints(Modifier.padding(horizontal = 8.dp)) {
            val inlineActions = constraints.hasBoundedWidth && maxWidth >= 560.dp && maxHeight < 200.dp
            val boundedHeight = constraints.hasBoundedHeight
            val editorScroll = rememberScrollState()
            Layout(
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag(ChatTags.EDITOR_VIEWPORT)
                            .then(if (boundedHeight) Modifier.verticalScroll(editorScroll) else Modifier),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        notice()
                        if (attachments.isNotEmpty()) {
                            AttachmentStrip(
                                attachments = attachments,
                                onPreview = onPreviewAttachment,
                                onRemove = onRemoveAttachment,
                                enabled = canEdit && !importing,
                                compact = compactHeight,
                                modifier = Modifier.fillMaxWidth().testTag(ChatTags.ATTACHMENTS)
                            )
                        }
                        if (importing) {
                            Text(
                                stringResource(R.string.attachment_importing),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = onTextChange,
                            enabled = canEdit,
                            maxLines = if (inlineActions) 1 else if (compactHeight) 2 else 6,
                            interactionSource = editorInteraction,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.fillMaxWidth().testTag(ChatTags.INPUT)
                                .defaultMinSize(minHeight = AppLayout.ControlSize)
                                .semantics {
                                    contentDescription = editorLabel
                                    if (sending) stateDescription = streamingDescription
                                },
                            decorationBox = { innerTextField ->
                                Box(Modifier.padding(horizontal = 8.dp, vertical = if (inlineActions) 4.dp else 8.dp)) {
                                    if (text.isEmpty()) {
                                        Text(
                                            stringResource(R.string.chat_input_hint),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = if (inlineActions) 1 else 2
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        supportingText?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box {
                            IconButton(
                                onClick = { addMenu = true },
                                enabled = canEdit && !importing,
                                modifier = Modifier.size(AppLayout.ControlSize).testTag(ChatTags.ADD)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.composer_add))
                            }
                            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.composer_add_file)) },
                                    leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                                    onClick = { addMenu = false; onPickText() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.composer_add_image)) },
                                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                    onClick = { addMenu = false; onPickImages() }
                                )
                            }
                        }
                        if (!inlineActions) Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = onVoice,
                            enabled = canEdit && !importing,
                            modifier = Modifier.size(AppLayout.ControlSize).testTag(ChatTags.VOICE)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.composer_voice))
                        }
                        if (sending && !submitting) {
                            FilledIconButton(
                                onClick = onStop,
                                modifier = Modifier.size(AppLayout.ControlSize).testTag(ChatTags.STOP)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.composer_stop))
                            }
                        } else {
                            FilledIconButton(
                                onClick = onSend,
                                enabled = canSubmit && !sending,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.size(AppLayout.ControlSize).testTag(ChatTags.SEND)
                            ) {
                                if (submitting) {
                                    val description = stringResource(R.string.composer_submitting)
                                    CircularProgressIndicator(
                                        Modifier.size(20.dp).semantics { contentDescription = description },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        strokeWidth = 2.dp
                                    )
                                } else Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = stringResource(R.string.chat_send)
                                )
                            }
                        }
                    }
                }
            ) { measurables, available ->
                val gap = if (inlineActions) 8.dp.roundToPx() else 0
                val padding = if (inlineActions) 0 else 8.dp.roundToPx()
                val contentHeight = if (boundedHeight) {
                    (available.maxHeight - padding * 2).coerceAtLeast(0)
                } else Constraints.Infinity
                val actionsWidth = if (inlineActions) {
                    (available.maxWidth - 280.dp.roundToPx() - gap).coerceAtLeast(0)
                } else available.maxWidth
                val actions = measurables[1].measure(Constraints(
                    minWidth = if (!inlineActions && available.hasBoundedWidth) actionsWidth else 0,
                    maxWidth = actionsWidth,
                    maxHeight = contentHeight
                ))
                val editorWidth = if (inlineActions) available.maxWidth - actions.width - gap else available.maxWidth
                val editorHeight = if (!boundedHeight || inlineActions) contentHeight
                    else (contentHeight - actions.height - gap).coerceAtLeast(0)
                val editor = measurables[0].measure(Constraints(
                    minWidth = if (available.hasBoundedWidth) editorWidth else 0,
                    maxWidth = editorWidth,
                    maxHeight = editorHeight
                ))
                val width = available.constrainWidth(
                    if (inlineActions) editor.width + gap + actions.width else maxOf(editor.width, actions.width)
                )
                val height = available.constrainHeight(
                    (if (inlineActions) maxOf(editor.height, actions.height) else editor.height + gap + actions.height) +
                        padding * 2
                )
                // Move the same editor node rather than recreate it when the IME changes available space.
                layout(width, height) {
                    editor.placeRelative(0, padding)
                    if (inlineActions) actions.placeRelative(editor.width + gap, height - actions.height - padding)
                    else actions.placeRelative(0, padding + editor.height + gap)
                }
            }
        }
    }
}
