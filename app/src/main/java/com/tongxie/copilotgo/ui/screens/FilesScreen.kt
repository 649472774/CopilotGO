package com.tongxie.copilotgo.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.ui.components.ConfirmActionDialog
import com.tongxie.copilotgo.ui.components.FeedbackBanner
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.components.ScreenState
import com.tongxie.copilotgo.ui.files.ExportPreview
import com.tongxie.copilotgo.ui.files.exportShareIntent
import com.tongxie.copilotgo.ui.viewmodel.LibraryFilesViewModel
import com.tongxie.copilotgo.ui.viewmodel.LibraryResult
import com.tongxie.copilotgo.ui.viewmodel.SessionListViewModel
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FilesScreen(
    filesVm: LibraryFilesViewModel,
    sessionsVm: SessionListViewModel,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit
) {
    val sessions by filesVm.summaries.collectAsStateWithLifecycle()
    val sessionLoading by sessionsVm.loading.collectAsStateWithLifecycle()
    val sessionError by sessionsVm.error.collectAsStateWithLifecycle()
    val exports by filesVm.exportsState.collectAsStateWithLifecycle()
    val busy by filesVm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTitle by rememberSaveable { mutableStateOf("") }
    var deleteIsSession by rememberSaveable { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var previewName by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LibraryResult<ExportPreview>?>(null) }

    suspend fun share(result: LibraryResult<File>) {
        when (result) {
            is LibraryResult.Success -> try {
                context.startActivity(Intent.createChooser(
                    exportShareIntent(context, result.value),
                    context.getString(R.string.export_chooser)
                ))
            } catch (_: ActivityNotFoundException) {
                snackbar.showSnackbar(context.getString(R.string.export_no_app))
            }
            is LibraryResult.Failure -> snackbar.showSnackbar(result.message)
        }
    }

    LaunchedEffect(previewName) {
        preview = null
        previewName?.let { preview = filesVm.previewExport(it) }
    }
    PageScaffold(
        title = stringResource(R.string.files_title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(
                onClick = { if (tab == 0) sessionsVm.reload() else filesVm.reloadExports() },
                enabled = !busy && !(if (tab == 0) sessionLoading else exports.loading)
            ) { Icon(Icons.Default.Refresh, stringResource(R.string.files_refresh)) }
        }
    ) { modifier ->
        Column(modifier) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.files_sessions)) }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.files_exports)) }
                )
            }
            if ((tab == 0 && sessionLoading && sessions.isNotEmpty()) ||
                (tab == 1 && exports.loading && exports.entries.isNotEmpty())
            ) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (tab == 0) {
                when {
                    sessionLoading && sessions.isEmpty() -> ScreenState(
                        stringResource(R.string.session_loading), Modifier.fillMaxSize(), loading = true
                    )
                    sessionError != null && sessions.isEmpty() -> ScreenState(
                        stringResource(R.string.chat_load_failed),
                        Modifier.fillMaxSize(),
                        detail = sessionError,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = sessionsVm::reload
                    )
                    sessions.isEmpty() -> ScreenState(
                        stringResource(R.string.files_no_sessions), Modifier.fillMaxSize()
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        sessionError?.let { message ->
                            item {
                                FeedbackBanner(
                                    message,
                                    isError = true,
                                    actionLabel = stringResource(R.string.action_retry),
                                    onAction = sessionsVm::reload
                                )
                            }
                        }
                        item {
                            Text(
                                stringResource(R.string.files_protected_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        items(sessions, key = { it.id }) { session ->
                            FileRow(
                                title = session.title.ifBlank { stringResource(R.string.session_untitled) },
                                detail = if (session.loadError != null) stringResource(R.string.session_needs_recovery)
                                else stringResource(R.string.files_session_detail, session.messageCount),
                                enabled = !busy,
                                onOpen = { onOpenSession(session.id) },
                                onShare = { scope.launch { share(filesVm.exportSession(session.id)) } },
                                onDelete = {
                                    deleteId = session.id
                                    deleteTitle = session.title
                                    deleteIsSession = true
                                    deleteError = null
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                when {
                    exports.loading && exports.entries.isEmpty() -> ScreenState(
                        stringResource(R.string.files_export_loading), Modifier.fillMaxSize(), loading = true
                    )
                    exports.error != null && exports.entries.isEmpty() -> ScreenState(
                        stringResource(R.string.files_export_failed),
                        Modifier.fillMaxSize(),
                        detail = exports.error,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = filesVm::reloadExports
                    )
                    exports.entries.isEmpty() -> ScreenState(
                        stringResource(R.string.files_no_exports),
                        Modifier.fillMaxSize(),
                        detail = stringResource(R.string.files_export_hint)
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Text(
                                stringResource(R.string.files_export_retention),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        exports.error?.let { message ->
                            item {
                                FeedbackBanner(
                                    message,
                                    isError = true,
                                    actionLabel = stringResource(R.string.action_retry),
                                    onAction = filesVm::reloadExports
                                )
                            }
                        }
                        items(exports.entries, key = { it.name }) { export ->
                            FileRow(
                                title = export.name,
                                detail = android.text.format.Formatter.formatShortFileSize(context, export.sizeBytes),
                                enabled = !busy,
                                onOpen = { previewName = export.name },
                                onShare = { scope.launch { share(filesVm.shareExport(export.name)) } },
                                onDelete = {
                                    deleteId = export.name
                                    deleteTitle = export.name
                                    deleteIsSession = false
                                    deleteError = null
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    deleteId?.let { id ->
        ConfirmActionDialog(
            title = stringResource(if (deleteIsSession) R.string.session_delete_title else R.string.files_delete_export_title),
            description = stringResource(
                if (deleteIsSession) R.string.session_delete_warning else R.string.files_delete_export_warning,
                deleteTitle
            ),
            confirmLabel = stringResource(R.string.action_delete),
            onDismiss = { deleteId = null },
            busy = busy,
            error = deleteError,
            onConfirm = {
                scope.launch {
                    val result = if (deleteIsSession) filesVm.deleteSession(id) else filesVm.deleteExport(id)
                    when (result) {
                        is LibraryResult.Success -> {
                            deleteId = null
                            result.warning?.let { snackbar.showSnackbar(it, withDismissAction = true) }
                        }
                        is LibraryResult.Failure -> {
                            if (deleteId == id) deleteError = result.message else snackbar.showSnackbar(result.message)
                        }
                    }
                }
            }
        )
    }
    previewName?.let { name ->
        AlertDialog(
            onDismissRequest = { previewName = null },
            title = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (val result = preview) {
                        null -> Text(stringResource(R.string.state_loading))
                        is LibraryResult.Failure -> Text(result.message, color = MaterialTheme.colorScheme.error)
                        is LibraryResult.Success -> {
                            if (result.value.truncated) {
                                Text(
                                    stringResource(R.string.files_preview_limit),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SelectionContainer { Text(result.value.text, style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { previewName = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
}

@Composable
private fun FileRow(
    title: String,
    detail: String,
    enabled: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clickable(enabled = enabled, onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.files_actions, title))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.session_export)) },
                    enabled = enabled,
                    onClick = { menu = false; onShare() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    enabled = enabled,
                    onClick = { menu = false; onDelete() }
                )
            }
        }
    }
}
