package com.tongxie.copilotgo.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.chat.SessionSummary
import com.tongxie.copilotgo.ui.components.ConfirmActionDialog
import com.tongxie.copilotgo.ui.components.FeedbackBanner
import com.tongxie.copilotgo.ui.components.ScreenState
import com.tongxie.copilotgo.ui.components.SessionListRow
import com.tongxie.copilotgo.ui.components.UpdateDialog
import com.tongxie.copilotgo.ui.files.exportShareIntent
import com.tongxie.copilotgo.ui.viewmodel.LibraryFilesViewModel
import com.tongxie.copilotgo.ui.viewmodel.LibraryResult
import com.tongxie.copilotgo.ui.viewmodel.SessionListViewModel
import com.tongxie.copilotgo.ui.viewmodel.UpdateViewModel
import kotlinx.coroutines.launch

@Composable
fun ChatListScreen(
    viewModel: SessionListViewModel,
    updateVm: UpdateViewModel,
    filesVm: LibraryFilesViewModel,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onFiles: () -> Unit,
    onRemote: () -> Unit
) {
    val sessions by filesVm.summaries.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by filesVm.busy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTitle by rememberSaveable { mutableStateOf("") }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

    val updateState by updateVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { updateVm.autoCheckOnce() }
    UpdateDialog(state = updateState, vm = updateVm)

    SessionListContent(
        sessions = sessions,
        loading = loading,
        error = error,
        busy = busy,
        snackbar = snackbar,
        onOpen = onOpen,
        onReload = viewModel::reload,
        onSettings = onSettings,
        onFiles = onFiles,
        onRemote = onRemote,
        onNew = {
            scope.launch {
                when (val result = filesVm.createSession { viewModel.createNew() }) {
                    is LibraryResult.Success -> onOpen(result.value.id)
                    is LibraryResult.Failure -> snackbar.showSnackbar(result.message)
                }
            }
        },
        onTogglePin = { session ->
            scope.launch {
                val result = filesVm.setPinned(session.id, !session.pinned)
                if (result is LibraryResult.Failure) snackbar.showSnackbar(result.message)
            }
        },
        onRename = { renameId = it.id; renameText = it.title; renameError = null },
        onDeleteRequest = { deleteId = it.id; deleteTitle = it.title; deleteError = null },
        onShare = { session ->
            scope.launch {
                when (val result = filesVm.exportSession(session.id)) {
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
        }
    )

    deleteId?.let { id ->
        ConfirmActionDialog(
            title = stringResource(R.string.session_delete_title),
            description = stringResource(R.string.session_delete_warning, deleteTitle),
            confirmLabel = stringResource(R.string.action_delete),
            busy = busy,
            error = deleteError,
            onDismiss = { deleteId = null },
            onConfirm = {
                scope.launch {
                    when (val result = filesVm.deleteSession(id)) {
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
    renameId?.let { id ->
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text(stringResource(R.string.session_rename)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = {
                            if (it.length <= 120) {
                                renameText = it
                                renameError = null
                            } else {
                                renameError = context.getString(R.string.session_title_limit)
                            }
                        },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.session_title)) },
                        isError = renameText.isBlank() || renameText.length > 120,
                        supportingText = { Text(stringResource(R.string.session_title_limit)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    renameError?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && renameText.isNotBlank() && renameText.length <= 120,
                    onClick = {
                        scope.launch {
                            when (val result = filesVm.renameSession(id, renameText)) {
                                is LibraryResult.Success -> renameId = null
                                is LibraryResult.Failure -> {
                                    if (renameId == id) renameError = result.message else snackbar.showSnackbar(result.message)
                                }
                            }
                        }
                    }
                ) { Text(stringResource(if (busy) R.string.state_saving else R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameId = null }) {
                    Text(stringResource(if (busy) R.string.action_close else R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListContent(
    sessions: List<SessionSummary>,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    snackbar: SnackbarHostState,
    onOpen: (String) -> Unit,
    onReload: () -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onFiles: () -> Unit,
    onRemote: () -> Unit,
    onTogglePin: (SessionSummary) -> Unit,
    onRename: (SessionSummary) -> Unit,
    onShare: (SessionSummary) -> Unit,
    onDeleteRequest: (SessionSummary) -> Unit
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var searchLimitReached by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val filtered = remember(sessions, search) {
        val query = search.trim()
        if (query.isEmpty()) sessions else sessions.filter { it.title.contains(query, ignoreCase = true) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("CopilotGo", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        actions = {
                            IconButton(onClick = {
                                searchVisible = !searchVisible
                                if (!searchVisible) search = ""
                            }) {
                                Icon(
                                    if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                                    stringResource(if (searchVisible) R.string.session_search_close else R.string.session_search)
                                )
                            }
                            if (!expanded) {
                                Box {
                                    IconButton(onClick = { menu = true }) {
                                        Icon(Icons.Default.MoreVert, stringResource(R.string.app_menu))
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.remote_title)) },
                                            leadingIcon = { Icon(Icons.Default.Cloud, null) },
                                            onClick = { menu = false; onRemote() }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.files_title)) },
                                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                                            onClick = { menu = false; onFiles() }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.settings_title)) },
                                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                                            onClick = { menu = false; onSettings() }
                                        )
                                    }
                                }
                            }
                        }
                    )
                    if (searchVisible) {
                        OutlinedTextField(
                            value = search,
                            onValueChange = {
                                searchLimitReached = it.length > 256
                                if (!searchLimitReached) search = it
                            },
                            singleLine = true,
                            isError = searchLimitReached,
                            supportingText = if (searchLimitReached) ({ Text(stringResource(R.string.search_limit)) }) else null,
                            label = { Text(stringResource(R.string.session_search_hint)) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                .testTag("session_search")
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (busy) {
                    Surface(
                        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = androidx.compose.material3.MaterialTheme.shapes.large
                    ) {
                        Text(stringResource(R.string.library_working), Modifier.padding(16.dp))
                    }
                } else {
                    ExtendedFloatingActionButton(
                        onClick = onNew,
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text(stringResource(R.string.chat_new)) },
                        modifier = Modifier.testTag("new_session")
                    )
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (expanded) {
                    NavigationRail(Modifier.verticalScroll(rememberScrollState())) {
                        NavigationRailItem(
                            selected = true,
                            onClick = { search = ""; searchVisible = false },
                            icon = { Icon(Icons.Default.ChatBubbleOutline, null) },
                            label = { Text(stringResource(R.string.chat_title)) }
                        )
                        NavigationRailItem(
                            selected = false,
                            onClick = onRemote,
                            icon = { Icon(Icons.Default.Cloud, null) },
                            label = { Text(stringResource(R.string.remote_short_title)) }
                        )
                        NavigationRailItem(
                            selected = false,
                            onClick = onFiles,
                            icon = { Icon(Icons.Default.Folder, null) },
                            label = { Text(stringResource(R.string.files_title)) }
                        )
                        NavigationRailItem(
                            selected = false,
                            onClick = onSettings,
                            icon = { Icon(Icons.Default.Settings, null) },
                            label = { Text(stringResource(R.string.settings_title)) }
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
                        if (loading && sessions.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error?.let {
                            FeedbackBanner(it, isError = true, actionLabel = stringResource(R.string.action_retry), onAction = onReload)
                        }
                        when {
                            loading && sessions.isEmpty() -> ScreenState(
                                stringResource(R.string.session_loading),
                                Modifier.fillMaxSize(),
                                loading = true
                            )
                            sessions.isEmpty() && error == null -> ScreenState(
                                stringResource(R.string.session_empty_title),
                                Modifier.fillMaxSize().padding(bottom = 88.dp),
                                detail = stringResource(R.string.session_empty_hint)
                            )
                            filtered.isEmpty() && sessions.isNotEmpty() -> ScreenState(
                                stringResource(R.string.session_no_match),
                                Modifier.fillMaxSize(),
                                actionLabel = stringResource(R.string.session_search_clear),
                                onAction = { search = "" }
                            )
                            else -> LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 96.dp)
                            ) {
                                items(filtered, key = { it.id }, contentType = { "session" }) { session ->
                                    SessionListRow(
                                        session,
                                        enabled = !busy,
                                        onOpen = { onOpen(session.id) },
                                        onTogglePin = { onTogglePin(session) },
                                        onRename = { onRename(session) },
                                        onShare = { onShare(session) },
                                        onDeleteRequest = { onDeleteRequest(session) }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
