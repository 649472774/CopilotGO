package com.tongxie.copilotgo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.chat.ModelInfo

@Composable
fun ModelPickerInline(
    currentModel: String,
    models: List<ModelInfo>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
    isStale: Boolean = false,
    onRefresh: () -> Unit = {},
    compact: Boolean = false,
    headingText: String? = null
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var queryTooLong by remember { mutableStateOf(false) }
    val current = models.firstOrNull { it.id == currentModel }
    val label = current?.name?.ifBlank { null } ?: currentModel.ifBlank {
        stringResource(R.string.model_picker_title)
    }
    val status = when {
        loading -> stringResource(R.string.model_loading)
        error != null -> error
        currentModel.isNotBlank() && current == null -> stringResource(R.string.model_unavailable)
        isStale -> stringResource(R.string.model_cached)
        else -> null
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("model_picker")
                .semantics { status?.let { stateDescription = it } }
        ) {
            Column(Modifier.weight(1f)) {
                headingText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() }
                    )
                }
                Text(
                    label,
                    style = if (headingText != null) MaterialTheme.typography.bodyMedium
                        else MaterialTheme.typography.labelLarge,
                    color = if (headingText != null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else if (compact && status != null) Icon(
                if (isStale && error == null && current != null) Icons.Default.Info else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = if (isStale && error == null && current != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            else Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        if (!compact && !loading && currentModel.isNotBlank() && current == null) {
            Text(
                stringResource(R.string.model_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        } else if (!compact && isStale) {
            Text(
                stringResource(R.string.model_cached),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (expanded) {
        val filtered = remember(models, query) {
            val search = query.trim()
            models.filter {
                search.isEmpty() || it.id.contains(search, ignoreCase = true) ||
                    it.name.orEmpty().contains(search, ignoreCase = true)
            }
        }
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(stringResource(R.string.model_picker_title)) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 480.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = {
                                queryTooLong = it.length > 256
                                if (!queryTooLong) query = it
                            },
                            singleLine = true,
                            isError = queryTooLong,
                            supportingText = if (queryTooLong) ({ Text(stringResource(R.string.search_limit)) }) else null,
                            label = { Text(stringResource(R.string.model_search)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (loading) {
                        item {
                            Text(stringResource(R.string.model_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (error != null) {
                        item {
                            FeedbackBanner(
                                message = error,
                                isError = true,
                                actionLabel = stringResource(R.string.action_retry),
                                onAction = onRefresh
                            )
                        }
                        if (compact && !loading && currentModel.isNotBlank() && current == null) {
                            item {
                                Text(stringResource(R.string.model_unavailable), color = MaterialTheme.colorScheme.error)
                            }
                        } else if (compact && isStale) {
                            item {
                                Text(
                                    stringResource(R.string.model_cached),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    if (filtered.isEmpty() && !loading) {
                        item {
                            Text(
                                stringResource(if (models.isEmpty()) R.string.model_empty else R.string.model_no_match),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    items(filtered, key = { it.id }) { model ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(
                                    selected = model.id == currentModel,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(model.id); expanded = false }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(selected = model.id == currentModel, onClick = null, enabled = enabled)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    model.name?.ifBlank { null } ?: model.id,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!model.name.isNullOrBlank() && model.name != model.id) {
                                    Text(
                                        model.id,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    stringResource(when (model.capabilities?.supports?.vision) {
                                        true -> R.string.model_supports_images
                                        false -> R.string.model_text_only
                                        null -> R.string.model_vision_unknown
                                    }),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.action_close)) }
            },
            dismissButton = {
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Text(stringResource(R.string.model_refresh))
                }
            }
        )
    }
}
