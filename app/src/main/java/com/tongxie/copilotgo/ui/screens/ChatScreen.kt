package com.tongxie.copilotgo.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.chat.AttachmentKind
import com.tongxie.copilotgo.data.chat.AttachmentRef
import com.tongxie.copilotgo.data.chat.ModelCatalogState
import com.tongxie.copilotgo.data.chat.OperationResult
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.SessionLoadState
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.data.storage.AttachmentImportException
import com.tongxie.copilotgo.ui.components.AttachmentPreviewDialog
import com.tongxie.copilotgo.ui.components.ChatComposer
import com.tongxie.copilotgo.ui.components.ChatTags
import com.tongxie.copilotgo.ui.components.FeedbackBanner
import com.tongxie.copilotgo.ui.components.MessageBubble
import com.tongxie.copilotgo.ui.components.ModelPickerInline
import com.tongxie.copilotgo.ui.components.PageScaffold
import com.tongxie.copilotgo.ui.components.ScreenState
import com.tongxie.copilotgo.ui.components.UiAttachment
import com.tongxie.copilotgo.ui.draft.ComposerDraft
import com.tongxie.copilotgo.ui.draft.DraftLimits
import com.tongxie.copilotgo.ui.files.exportShareIntent
import com.tongxie.copilotgo.ui.state.NetworkAvailability
import com.tongxie.copilotgo.ui.state.observeNetworkAvailability
import com.tongxie.copilotgo.ui.viewmodel.ChatDraftsViewModel
import com.tongxie.copilotgo.ui.viewmodel.ChatViewModel
import com.tongxie.copilotgo.ui.viewmodel.DraftProblem
import com.tongxie.copilotgo.ui.viewmodel.DraftUiState
import com.tongxie.copilotgo.ui.viewmodel.LibraryFilesViewModel
import com.tongxie.copilotgo.ui.viewmodel.LibraryResult
import com.tongxie.copilotgo.ui.viewmodel.SessionListViewModel
import com.tongxie.copilotgo.util.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.io.File

@Composable
fun ChatScreen(
    sessionId: String,
    viewModel: ChatViewModel,
    modelsVm: SessionListViewModel,
    draftsVm: ChatDraftsViewModel,
    filesVm: LibraryFilesViewModel,
    onBack: () -> Unit
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val catalog by modelsVm.catalogState.collectAsStateWithLifecycle()
    val draftFlow = remember(sessionId, draftsVm) { draftsVm.state(sessionId) }
    val draft by draftFlow.collectAsStateWithLifecycle()
    val libraryBusy by filesVm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val connectivity = remember(context.applicationContext) { observeNetworkAvailability(context.applicationContext) }
    val network by connectivity.collectAsStateWithLifecycle(initialValue = NetworkAvailability.UNKNOWN)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    DisposableEffect(sessionId, draftsVm) {
        draftsVm.retain(sessionId)
        onDispose { draftsVm.release(sessionId) }
    }
    LaunchedEffect(sessionId) { modelsVm.refreshModels() }
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it, withDismissAction = true) }
    }

    fun importSelected(uris: List<Uri>) {
        draftsVm.importAttachments(sessionId, uris) { uri ->
            viewModel.importAttachment(context.contentResolver, uri)
        }
    }
    val pickText = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::importSelected)
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(DraftLimits.ATTACHMENTS),
        ::importSelected
    )
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (spoken.isNullOrBlank()) {
                scope.launch { snackbar.showSnackbar(context.getString(R.string.voice_no_result)) }
            } else {
                draftsVm.appendSpeech(sessionId, spoken)
            }
        }
    }

    LaunchedEffect(draft.notice?.serial) {
        val notice = draft.notice ?: return@LaunchedEffect
        val message = notice.rejection ?: context.getString(
            when (notice.problem) {
                DraftProblem.NOT_READY -> R.string.draft_not_ready
                DraftProblem.BUSY -> R.string.draft_busy
                DraftProblem.EMPTY -> R.string.draft_empty
                DraftProblem.TEXT_LIMIT -> R.string.draft_text_limit
                DraftProblem.COUNT_LIMIT -> R.string.draft_count_limit
                DraftProblem.TOTAL_LIMIT -> R.string.draft_total_limit
                DraftProblem.IMPORT_FAILED -> R.string.draft_import_error
                null -> R.string.draft_not_ready
            }
        )
        snackbar.showSnackbar(message, withDismissAction = true)
        draftsVm.clearNotice(sessionId, notice.serial)
    }

    var actionKind by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var actionId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var editText by rememberSaveable(sessionId) { mutableStateOf("") }
    var actionBusy by remember { mutableStateOf(false) }
    var changingModel by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    fun requestAction(kind: String, message: UiMessage) {
        if (kind == "edit" && message.content.length > DraftLimits.TEXT_CHARS) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.message_edit_limit)) }
        } else {
            actionKind = kind
            actionId = message.id
            editText = if (kind == "edit") message.content else ""
            actionError = null
        }
    }

    val loaded = session
    if (loaded == null) {
        PageScaffold(stringResource(R.string.chat_title), onBack, snackbar) { modifier ->
            when (val state = loadState) {
                SessionLoadState.Missing -> ScreenState(
                    stringResource(R.string.chat_missing),
                    modifier,
                    actionLabel = stringResource(R.string.action_back),
                    onAction = onBack
                )
                is SessionLoadState.Failed -> ScreenState(
                    stringResource(R.string.chat_load_failed),
                    modifier,
                    detail = state.message,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = viewModel::reload
                )
                else -> ScreenState(stringResource(R.string.chat_loading), modifier, loading = true)
            }
        }
    } else {
        ChatContent(
            session = loaded,
            catalog = catalog,
            draft = draft,
            sending = sending,
            error = error,
            snackbar = snackbar,
            onBack = onBack,
            onModelSelect = { model ->
                if (!changingModel) {
                    changingModel = true
                    scope.launch {
                        try {
                            val result = viewModel.setModelAndAwait(model)
                            if (result is OperationResult.Rejected) snackbar.showSnackbar(result.message)
                        } finally {
                            changingModel = false
                        }
                    }
                }
            },
            changingModel = changingModel,
            networkUnavailable = network == NetworkAvailability.UNAVAILABLE,
            onNetworkSettings = {
                try {
                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.network_settings_unavailable)) }
                } catch (_: SecurityException) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.network_settings_unavailable)) }
                }
            },
            onRefreshModels = { modelsVm.refreshModels(force = true) },
            onTextChange = { draftsVm.updateText(sessionId, it) },
            onSend = {
                draftsVm.submit(sessionId) { snapshot ->
                    viewModel.submit(
                        snapshot.text.trim(),
                        attachmentRefs = snapshot.attachments,
                        submissionId = snapshot.submissionId
                    )
                }
            },
            onStop = viewModel::stopStreaming,
            onPickText = {
                try {
                    pickText.launch(arrayOf("text/*", "application/json", "application/xml", "application/javascript"))
                } catch (_: ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.picker_unavailable)) }
                }
            },
            onPickImages = {
                try {
                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.picker_unavailable)) }
                }
            },
            onVoice = {
                try {
                    voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_prompt))
                    })
                } catch (_: ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.voice_unavailable)) }
                }
            },
            onRemoveAttachment = { draftsVm.removeAttachment(sessionId, it) },
            attachmentFile = viewModel::attachmentFile,
            onRetryDraft = { if (draft.loadFailed) draftsVm.reload(sessionId) else draftsVm.retrySave(sessionId) },
            onEdit = { requestAction("edit", it) },
            onDelete = { requestAction("delete", it) },
            onRegenerate = { requestAction("regenerate", it) },
            onRetry = {
                loaded.messages.lastOrNull { it.role == "assistant" }?.let { requestAction("regenerate", it) }
                    ?: viewModel.clearError()
            },
            onShare = { message ->
                if (!libraryBusy) {
                    scope.launch {
                        when (val result = filesVm.exportMessage(message)) {
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
            }
        )
    }

    val id = actionId
    val kind = actionKind
    if (id != null && kind != null) {
        val isEdit = kind == "edit"
        AlertDialog(
            onDismissRequest = { actionId = null; actionKind = null },
            title = { Text(stringResource(when (kind) {
                "edit" -> R.string.message_edit
                "regenerate" -> R.string.message_regenerate
                else -> R.string.message_delete_title
            })) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(when (kind) {
                        "edit" -> R.string.message_edit_warning
                        "regenerate" -> R.string.message_regenerate_warning
                        else -> R.string.message_delete_warning
                    }))
                    if (isEdit) {
                        OutlinedTextField(
                            value = editText,
                            onValueChange = {
                                if (it.length <= DraftLimits.TEXT_CHARS) editText = it
                                else actionError = context.getString(R.string.draft_text_limit)
                            },
                            label = { Text(stringResource(R.string.composer_label)) },
                            enabled = !actionBusy,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !actionBusy && !sending && (!isEdit || editText.isNotBlank()),
                    onClick = {
                        actionBusy = true
                        scope.launch {
                            try {
                                val result = when (kind) {
                                    "edit" -> viewModel.editAndResendAndAwait(id, editText.trim())
                                    "regenerate" -> viewModel.regenerateAndAwait(id)
                                    else -> viewModel.deleteMessageAndAwait(id)
                                }
                                when (result) {
                                    OperationResult.Accepted -> { actionId = null; actionKind = null }
                                    is OperationResult.Rejected -> {
                                        if (actionId == id) actionError = result.message else snackbar.showSnackbar(result.message)
                                    }
                                }
                            } finally {
                                actionBusy = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(if (actionBusy) R.string.state_saving else when (kind) {
                        "edit" -> R.string.message_resend
                        "regenerate" -> R.string.message_regenerate
                        else -> R.string.action_delete
                    }))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { actionId = null; actionKind = null }
                ) { Text(stringResource(if (actionBusy) R.string.action_close else R.string.action_cancel)) }
            }
        )
    }
}

/** Stateless core inputs make this screen usable by the hermetic Android acceptance host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    session: Session,
    catalog: ModelCatalogState,
    draft: DraftUiState,
    sending: Boolean,
    error: String?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onModelSelect: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickText: () -> Unit,
    onPickImages: () -> Unit,
    onVoice: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    attachmentFile: (AttachmentRef) -> File,
    onRetryDraft: () -> Unit,
    onEdit: (UiMessage) -> Unit,
    onDelete: (UiMessage) -> Unit,
    onRegenerate: (UiMessage) -> Unit,
    onRetry: () -> Unit,
    onShare: (UiMessage) -> Unit,
    changingModel: Boolean = false,
    networkUnavailable: Boolean = false,
    onNetworkSettings: (() -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by rememberSaveable(session.id) { mutableStateOf(true) }
    var initialized by rememberSaveable(session.id) { mutableStateOf(false) }
    var observedSend by rememberSaveable(session.id) { mutableStateOf(draft.acceptedSerial) }
    var explicitScrollPending by remember(session.id) { mutableStateOf(false) }
    val scrollRequests = remember(listState, session.id) { Channel<Unit>(Channel.CONFLATED) }
    var preview by remember { mutableStateOf<UiAttachment?>(null) }
    val atLatest by remember { derivedStateOf { !listState.canScrollForward } }
    val anchor = session.messages.size
    val currentAnchor by rememberUpdatedState(anchor)
    val model = catalog.models.firstOrNull { it.id == session.model }
    val images = draft.draft.attachments.any { it.kind == AttachmentKind.IMAGE }
    val canSubmit = model?.chatCompatible == true && !changingModel && (!images || model.supportsVision)
    val attachmentItems = remember(draft.draft.attachments) {
        draft.draft.attachments.map { it.toUiAttachment(attachmentFile) }
    }
    val scrollIntent = remember(listState, session.id) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    following = false
                    explicitScrollPending = false
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                    following = !listState.canScrollForward
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!listState.canScrollForward) following = true
                return Velocity.Zero
            }
        }
    }

    DisposableEffect(scrollRequests) {
        onDispose { scrollRequests.close() }
    }
    LaunchedEffect(listState, scrollRequests) {
        for (request in scrollRequests) {
            val explicit = explicitScrollPending
            explicitScrollPending = false
            if (following && (explicit || !listState.isScrollInProgress)) {
                // One owner, and no concurrent AwaitFirstLayout continuations during first placement.
                listState.requestScrollToItem(currentAnchor)
                initialized = true
            }
        }
    }

    LaunchedEffect(draft.acceptedSerial) {
        if (draft.acceptedSerial != observedSend) {
            observedSend = draft.acceptedSerial
            following = true
            explicitScrollPending = true
            scrollRequests.trySend(Unit)
        }
    }
    LaunchedEffect(session.id, anchor, following, session.messages.lastOrNull()?.content?.length) {
        if (following) scrollRequests.trySend(Unit)
    }
    LaunchedEffect(listState, session.id) {
        var previous: ChatViewport? = null
        snapshotFlow {
            val layout = listState.layoutInfo
            ChatViewport(
                firstIndex = listState.firstVisibleItemIndex,
                firstOffset = listState.firstVisibleItemScrollOffset,
                lastItemEnd = layout.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size },
                itemCount = layout.totalItemsCount,
                viewportEnd = layout.viewportEndOffset,
                scrolling = listState.isScrollInProgress,
                canScrollForward = listState.canScrollForward
            )
        }.collect { viewport ->
            val before = previous
            val movedEarlier = before != null && before.itemCount == viewport.itemCount &&
                (viewport.firstIndex < before.firstIndex ||
                    (viewport.firstIndex == before.firstIndex && viewport.firstOffset < before.firstOffset))
            if (!explicitScrollPending && viewport.canScrollForward &&
                ((viewport.scrolling && before?.scrolling != true) || movedEarlier)
            ) {
                following = false
            }
            if (!viewport.scrolling && !viewport.canScrollForward && viewport.itemCount > 0) following = true
            previous = viewport
            if (following && !viewport.scrolling && viewport.canScrollForward) scrollRequests.trySend(Unit)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val imeHeight = with(density) { WindowInsets.ime.getBottom(this).toDp() }
        val compactHeight = maxHeight - imeHeight < 480.dp
        val minimalChrome = imeHeight > 0.dp && maxHeight - imeHeight < 320.dp
        val systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val safeHeight = maxHeight - imeHeight - with(density) {
            (systemInsets.getTop(this) + systemInsets.getBottom(this)).toDp()
        }
        val composerMaxHeight = if (minimalChrome) safeHeight.coerceAtLeast(96.dp)
        else ((safeHeight - 64.dp) * 0.7f).coerceIn(160.dp, 480.dp)
        Scaffold(
            contentWindowInsets = systemInsets,
            topBar = {
                if (!minimalChrome) {
                TopAppBar(
                    title = {
                        Text(session.title.ifBlank { stringResource(R.string.chat_title) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    windowInsets = systemInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Box(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
                    contentAlignment = Alignment.TopCenter
                ) {
                        ChatComposer(
                            modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth().heightIn(max = composerMaxHeight),
                            text = draft.draft.text,
                            attachments = attachmentItems,
                            onTextChange = onTextChange,
                            onSend = onSend,
                            onStop = onStop,
                            onPickText = onPickText,
                            onPickImages = onPickImages,
                            onVoice = onVoice,
                            onRemoveAttachment = onRemoveAttachment,
                            onPreviewAttachment = { preview = it },
                            sending = sending,
                            submitting = draft.submitting,
                            importing = draft.importing,
                            enabled = !draft.loading && !draft.loadFailed,
                            submissionEnabled = canSubmit,
                            compactHeight = compactHeight,
                            supportingText = when {
                                draft.loading -> stringResource(R.string.draft_loading)
                                changingModel -> stringResource(R.string.model_changing)
                                model == null -> stringResource(R.string.chat_select_model)
                                images && !canSubmit -> stringResource(R.string.chat_model_no_vision)
                                sending -> stringResource(R.string.chat_draft_while_sending)
                                else -> null
                            },
                            notice = {
                                if (draft.loadFailed || draft.saveFailed) {
                                    FeedbackBanner(
                                        stringResource(if (draft.loadFailed) R.string.draft_load_failed else R.string.draft_save_failed),
                                        isError = true,
                                        actionLabel = stringResource(R.string.action_retry),
                                        onAction = onRetryDraft
                                    )
                                }
                            }
                        )
                }
            }
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
                    if (networkUnavailable && !minimalChrome) {
                        FeedbackBanner(
                            stringResource(R.string.network_offline),
                            actionLabel = if (onNetworkSettings != null) stringResource(R.string.network_settings) else null,
                            onAction = onNetworkSettings
                        )
                    }
                    if (!compactHeight || imeHeight == 0.dp) {
                        ModelPickerInline(
                            currentModel = session.model,
                            models = catalog.models,
                            onSelect = onModelSelect,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            enabled = !sending && !draft.submitting && !changingModel,
                            loading = catalog.loading || changingModel,
                            error = catalog.error,
                            isStale = catalog.isStale,
                            onRefresh = onRefreshModels
                        )
                    }
                    if (error != null) {
                        FeedbackBanner(
                            error,
                            isError = true,
                            actionLabel = stringResource(R.string.chat_retry_last),
                            onAction = if (sending) null else onRetry
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (session.messages.isEmpty()) {
                            ScreenState(
                                stringResource(R.string.chat_empty_title),
                                modifier = Modifier.fillMaxSize(),
                                detail = stringResource(R.string.chat_empty_hint)
                            )
                        }
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(vertical = 8.dp),
                            modifier = Modifier.fillMaxSize().nestedScroll(scrollIntent).testTag(ChatTags.MESSAGES)
                        ) {
                            items(session.messages, key = { it.id }, contentType = { "message" }) { message ->
                                val attachments = remember(message.attachments) {
                                    message.attachments.map { it.toUiAttachment(attachmentFile) }
                                }
                                MessageBubble(
                                    message = message,
                                    attachments = attachments,
                                    actionsEnabled = !sending && !draft.submitting,
                                    onEdit = if (message.role == "user") ({ onEdit(message) }) else null,
                                    onRegenerate = if (message.role == "assistant") ({ onRegenerate(message) }) else null,
                                    onDelete = { onDelete(message) },
                                    onShare = { onShare(message) },
                                    onFeedback = { feedback -> scope.launch { snackbar.showSnackbar(feedback) } }
                                )
                            }
                            item(key = "__latest__") { Spacer(Modifier.height(1.dp)) }
                        }
                        if (initialized && !atLatest) {
                            FilledTonalButton(
                                onClick = {
                                    following = true
                                    explicitScrollPending = true
                                    scrollRequests.trySend(Unit)
                                },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag(ChatTags.LATEST)
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = null)
                                Text(stringResource(R.string.chat_jump_latest), Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    preview?.let { AttachmentPreviewDialog(it) { preview = null } }
}

private data class ChatViewport(
    val firstIndex: Int,
    val firstOffset: Int,
    val lastItemEnd: Int?,
    val itemCount: Int,
    val viewportEnd: Int,
    val scrolling: Boolean,
    val canScrollForward: Boolean
)

private fun AttachmentRef.toUiAttachment(resolve: (AttachmentRef) -> File): UiAttachment {
    val file = try {
        resolve(this)
    } catch (_: AttachmentImportException) {
        Logger.w("Invalid attachment preview reference")
        null
    } catch (_: IllegalArgumentException) {
        Logger.w("Invalid attachment preview metadata")
        null
    }
    return UiAttachment(id, name, mimeType, sizeBytes, file, kind == AttachmentKind.IMAGE)
}
