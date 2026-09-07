package com.tongxie.copilotgo.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect as AndroidRect
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import com.tongxie.copilotgo.data.chat.ModelCatalogState
import com.tongxie.copilotgo.data.chat.ModelInfo
import com.tongxie.copilotgo.data.chat.SendResult
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.data.storage.AppPaths
import com.tongxie.copilotgo.data.storage.SessionStore
import com.tongxie.copilotgo.ui.components.ChatComposer
import com.tongxie.copilotgo.ui.components.ChatTags
import com.tongxie.copilotgo.ui.components.ConfirmActionDialog
import com.tongxie.copilotgo.ui.components.ModelPickerInline
import com.tongxie.copilotgo.ui.components.UiAttachment
import com.tongxie.copilotgo.ui.draft.ChatDraftStore
import com.tongxie.copilotgo.ui.draft.ComposerDraft
import com.tongxie.copilotgo.ui.screens.ChatContent
import com.tongxie.copilotgo.ui.theme.CopilotGoTheme
import com.tongxie.copilotgo.ui.viewmodel.ChatDraftsViewModel
import com.tongxie.copilotgo.ui.viewmodel.DraftUiState
import com.tongxie.copilotgo.ui.viewmodel.LibraryFilesViewModel
import com.tongxie.copilotgo.ui.viewmodel.LibraryResult
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Controlled content only. No AuthViewModel, real conversation, network or account
 * is used. Integration runs this class on its reserved acceptance emulator.
 */
@RunWith(AndroidJUnit4::class)
class NativeUiAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun edgeToEdge() {
        configureHostWindow()
    }

    @Test fun composerKeepsWideEditorAnd48DpTargetsAt200Percent() {
        val text = mutableStateOf("中文草稿 \uD83D\uDC69\u200D\uD83D\uDCBB")
        rule.setContent {
            FixtureTheme(fontScale = 2f) {
                Box(Modifier.widthIn(max = 320.dp).fillMaxWidth()) {
                    FixtureComposer(text)
                }
            }
        }
        rule.onNodeWithTag(ChatTags.INPUT).assertWidthIsAtLeast(280.dp)
        rule.onNodeWithTag(ChatTags.SEND).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onNodeWithTag(ChatTags.ADD).assertHeightIsAtLeast(48.dp).assertHasClickAction()
        saveScreenshot("composer-light-200")
    }

    @Test fun textOnlyAttachmentsCanSendAndRemovalIsNamed() {
        val sent = AtomicInteger()
        val attachments = mutableStateOf(listOf(
            UiAttachment("text-fixture", "中文说明文件-\uD83D\uDCC4.txt", "text/plain", 48, null, false)
        ))
        rule.setContent {
            FixtureTheme(dark = true, fontScale = 2f) {
                ChatComposer(
                    text = "",
                    attachments = attachments.value,
                    onTextChange = {},
                    onSend = { sent.incrementAndGet() },
                    onStop = {},
                    onPickText = {},
                    onPickImages = {},
                    onVoice = {},
                    onRemoveAttachment = { id -> attachments.value = attachments.value.filterNot { it.id == id } },
                    onPreviewAttachment = {}
                )
            }
        }
        rule.onNodeWithTag(ChatTags.SEND).assertIsEnabled().performClick()
        assertEquals(1, sent.get())
        rule.onNodeWithContentDescription("移除附件：中文说明文件-\uD83D\uDCC4.txt")
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()
        saveScreenshot("composer-dark-200")
    }

    @Test fun submittingDisablesDuplicateTapsWithoutClearingText() {
        val clicks = AtomicInteger()
        val submitting = mutableStateOf(false)
        rule.setContent {
            FixtureTheme {
                ChatComposer(
                    text = "A controlled unsent draft",
                    attachments = emptyList(),
                    onTextChange = {},
                    onSend = { clicks.incrementAndGet(); submitting.value = true },
                    onStop = {},
                    onPickText = {},
                    onPickImages = {},
                    onVoice = {},
                    onRemoveAttachment = {},
                    onPreviewAttachment = {},
                    submitting = submitting.value
                )
            }
        }
        rule.onNodeWithTag(ChatTags.SEND).performClick().assertIsNotEnabled()
        rule.onNodeWithText("A controlled unsent draft").assertIsDisplayed()
        assertEquals(1, clicks.get())
    }

    @Test fun modelRefreshNeverSilentlyChangesSelection() {
        val chosen = mutableStateOf("retired-fixture")
        val models = mutableStateOf(listOf(ModelInfo("available-fixture", "可用测试模型")))
        val selected = AtomicInteger()
        rule.setContent {
            FixtureTheme {
                ModelPickerInline(
                    currentModel = chosen.value,
                    models = models.value,
                    onSelect = { chosen.value = it; selected.incrementAndGet() }
                )
            }
        }
        rule.runOnIdle { models.value = models.value + ModelInfo("another-fixture") }
        rule.runOnIdle { assertEquals("retired-fixture", chosen.value); assertEquals(0, selected.get()) }
        rule.onNodeWithTag("model_picker").performClick()
        rule.onNodeWithText("可用测试模型").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("available-fixture", chosen.value); assertEquals(1, selected.get()) }
    }

    @Test fun stopRemainsASeparateLabeledAction() {
        val sending = mutableStateOf(true)
        rule.setContent {
            FixtureTheme {
                ChatComposer(
                    text = "Next draft remains editable",
                    attachments = emptyList(),
                    onTextChange = {},
                    onSend = {},
                    onStop = { sending.value = false },
                    onPickText = {},
                    onPickImages = {},
                    onVoice = {},
                    onRemoveAttachment = {},
                    onPreviewAttachment = {},
                    sending = sending.value
                )
            }
        }
        rule.onNodeWithTag(ChatTags.STOP).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag(ChatTags.STOP).assertDoesNotExist()
        rule.onNodeWithTag(ChatTags.SEND).assertIsEnabled()
        rule.onNodeWithText("Next draft remains editable").assertIsDisplayed()
    }

    @Test fun manualReadingSurvivesStreamingAndCompletion() {
        val session = mutableStateOf(fixtureSession())
        val sending = mutableStateOf(true)
        rule.setContent { FixtureTheme { FixtureChat(session, sending) } }
        rule.waitForIdle()
        rule.onNodeWithTag(ChatTags.MESSAGES).performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithTag(ChatTags.LATEST).assertIsDisplayed()
        val before = scrollPosition()
        rule.runOnIdle {
            val current = session.value
            val messages = current.messages.toMutableList()
            messages[messages.lastIndex] = messages.last().copy(content = messages.last().content + "\ncontrolled streamed tail")
            session.value = current.copy(messages = messages, revision = current.revision + 1)
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ChatTags.LATEST).assertIsDisplayed()
        assertEquals(before, scrollPosition(), 0.5f)
        rule.runOnIdle { sending.value = false }
        rule.waitForIdle()
        rule.onNodeWithTag(ChatTags.LATEST).assertIsDisplayed()
        assertEquals(before, scrollPosition(), 0.5f)
        saveScreenshot("chat-manual-scroll")
        rule.onNodeWithTag(ChatTags.LATEST).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ChatTags.LATEST).assertDoesNotExist()
    }

    @Test fun cjkInputAndSendRemainReachableWithKeyboard() {
        val session = mutableStateOf(fixtureSession().copy(messages = mutableListOf()))
        val text = mutableStateOf("")
        val sending = mutableStateOf(false)
        val chatVisible = mutableStateOf(true)
        rule.setContent {
            FixtureTheme(fontScale = LocalDensity.current.fontScale) {
                if (chatVisible.value) {
                    BackHandler { chatVisible.value = false }
                    FixtureChat(session, sending, text)
                }
            }
        }
        configureHostWindow()
        rule.waitForIdle()
        assertResizeHost()
        rule.onNodeWithTag(ChatTags.INPUT).performClick().performTextInput("测试输入 \uD83D\uDE80")
        waitForRootIme(visible = true)
        rule.waitForIdle()
        saveScreenshot("chat-ime-cjk")
        assertResizeHost()
        rule.onNodeWithTag(ChatTags.INPUT).assertIsDisplayed()
        rule.onNodeWithTag(ChatTags.SEND).assertIsDisplayed().assertIsEnabled()
        val ime = requireNotNull(rootImeGeometry())
        assertTrue("Physical IME must be open for the bounds assertions", ime.visible && ime.bottomInset > 0)
        assertControlAboveIme(ChatTags.INPUT, ime)
        assertControlAboveIme(ChatTags.SEND, ime)
        val sendBounds = controlBoundsOnScreen(ChatTags.SEND, ime)
        val allowedGap = rule.activity.resources.displayMetrics.density * 24f
        assertTrue(
            "Composer must sit next to the real IME, not above a second inset: send=$sendBounds, IME top=${ime.topOnScreen}",
            ime.topOnScreen - sendBounds.bottom <= allowedGap
        )
        if (ime.windowBounds.height() > ime.windowBounds.width()) {
            rule.onNodeWithText(session.value.title).assertIsDisplayed()
            rule.onNodeWithContentDescription(rule.activity.getString(com.tongxie.copilotgo.R.string.action_back))
                .assertIsDisplayed()
        }
        rule.runOnIdle { assertEquals("测试输入 \uD83D\uDE80", text.value) }

        Espresso.pressBack()
        waitForRootIme(visible = false)
        rule.waitForIdle()
        saveScreenshot("chat-ime-cjk-back")
        rule.runOnIdle {
            assertTrue("System Back must hide the IME without navigating away", chatVisible.value)
            assertTrue("System Back must not finish the chat host", !rule.activity.isFinishing && !rule.activity.isDestroyed)
            assertEquals("测试输入 \uD83D\uDE80", text.value)
        }
        rule.onNodeWithTag(ChatTags.INPUT).assertIsDisplayed()
        rule.onNodeWithTag(ChatTags.SEND).assertIsDisplayed().assertIsEnabled()
    }

    @Test fun offlineStateIsVisibleWithoutDiscardingTheDraft() {
        val session = mutableStateOf(fixtureSession().copy(messages = mutableListOf()))
        val text = mutableStateOf("离线草稿 \uD83D\uDE80")
        rule.setContent {
            FixtureTheme {
                FixtureChat(session, remember { mutableStateOf(false) }, text, offline = true)
            }
        }
        rule.onNodeWithText(rule.activity.getString(com.tongxie.copilotgo.R.string.network_offline)).assertIsDisplayed()
        rule.onNodeWithTag(ChatTags.INPUT).assertIsEnabled()
        rule.runOnIdle { assertEquals("离线草稿 \uD83D\uDE80", text.value) }
        saveScreenshot("chat-offline")
    }

    @Test fun destructiveDecisionRequiresExplicitConfirmation() {
        val confirmed = AtomicInteger()
        val open = mutableStateOf(true)
        rule.setContent {
            FixtureTheme(fontScale = 2f) {
                if (open.value) {
                    ConfirmActionDialog(
                        title = "删除测试会话？",
                        description = "这里只包含受控测试内容。删除后无法恢复。",
                        confirmLabel = "确认删除",
                        onConfirm = { confirmed.incrementAndGet(); open.value = false },
                        onDismiss = { open.value = false }
                    )
                }
            }
        }
        rule.runOnIdle { assertEquals(0, confirmed.get()) }
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle { assertEquals(0, confirmed.get()); open.value = true }
        rule.onNodeWithText("确认删除").performClick()
        rule.runOnIdle { assertEquals(1, confirmed.get()) }
    }

    @Test fun busyDialogDoesNotTrapSystemBack() {
        val open = mutableStateOf(true)
        rule.setContent {
            FixtureTheme {
                if (open.value) {
                    ConfirmActionDialog(
                        title = "Controlled operation",
                        description = "A committed operation can continue after dismissing this dialog.",
                        confirmLabel = "Confirm",
                        onConfirm = {},
                        onDismiss = { open.value = false },
                        busy = true
                    )
                }
            }
        }
        Espresso.pressBack()
        rule.runOnIdle { assertTrue(!open.value) }
    }

    @Test fun draftAdmissionIsDurableAndRejectionPreservesSnapshot() {
        val fixtureRoot = File(rule.activity.cacheDir, "draft-fixture-${UUID.randomUUID()}")
        val context = object : ContextWrapper(rule.activity.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(fixtureRoot, "no-backup")
        }
        val owner = ViewModelStore()
        lateinit var vm: ChatDraftsViewModel
        val attempts = AtomicInteger()
        rule.runOnIdle {
            vm = ViewModelProvider(owner, SimpleVMFactory { ChatDraftsViewModel(context) })[ChatDraftsViewModel::class.java]
            vm.retain("controlled-session")
        }
        rule.waitUntil(5_000) { !vm.state("controlled-session").value.loading }
        rule.runOnIdle {
            vm.updateText("controlled-session", "受控草稿 \uD83D\uDE80")
            repeat(2) {
                vm.submit("controlled-session") { snapshot ->
                    attempts.incrementAndGet()
                    val persisted = ChatDraftStore(File(context.noBackupFilesDir, "ui-drafts")).load("controlled-session")
                    assertEquals(snapshot.submissionId, persisted.submissionId)
                    assertEquals(snapshot.text, persisted.text)
                    delay(100)
                    SendResult.Rejected("Controlled refusal")
                }
            }
        }
        rule.waitUntil(5_000) { !vm.state("controlled-session").value.submitting }
        rule.runOnIdle {
            assertEquals(1, attempts.get())
            assertEquals("受控草稿 \uD83D\uDE80", vm.state("controlled-session").value.draft.text)
            vm.submit("controlled-session") { SendResult.Accepted("controlled-user-message") }
        }
        rule.waitUntil(5_000) {
            vm.state("controlled-session").value.let { !it.submitting && !it.saving && it.draft.isEmpty }
        }
        rule.runOnIdle { owner.clear() }
        File(File(context.noBackupFilesDir, "ui-drafts"), "controlled-session.json").delete()
    }

    @Test fun deletionCompletesDraftCleanupWhenCallerIsCancelled() {
        val root = File(rule.activity.cacheDir, "delete-fixture-${UUID.randomUUID()}")
        val context = fixtureContext(root)
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SessionStore(AppPaths(File(root, "data")), Json { encodeDefaults = true }, storeScope)
        val id = UUID.randomUUID().toString()
        runBlocking { store.save(Session(id, "Controlled deletion", "fixture-model")) }
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleaned = AtomicBoolean(false)
        val owner = ViewModelStore()
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var job: Job
        rule.runOnIdle {
            val vm = ViewModelProvider(owner, SimpleVMFactory {
                LibraryFilesViewModel(context, store, discardDraft = {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                    cleaned.set(true)
                })
            })[LibraryFilesViewModel::class.java]
            job = caller.launch { vm.deleteSession(id) }
        }
        rule.waitUntil(5_000) { cleanupStarted.isCompleted }
        job.cancel()
        releaseCleanup.complete(Unit)
        rule.waitUntil(5_000) { job.isCompleted }
        assertTrue(cleaned.get())
        assertTrue(store.summaries.value.none { it.id == id })
        rule.runOnIdle { owner.clear() }
        caller.cancel()
        storeScope.cancel()
    }

    @Test fun exportMutationReplacesAnOlderInFlightListing() {
        val root = File(rule.activity.cacheDir, "export-fixture-${UUID.randomUUID()}")
        val context = fixtureContext(root)
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SessionStore(AppPaths(File(root, "data")), Json { encodeDefaults = true }, storeScope)
        val firstReadStarted = CompletableDeferred<Unit>()
        val releaseOldRead = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val owner = ViewModelStore()
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var vm: LibraryFilesViewModel
        rule.runOnIdle {
            vm = ViewModelProvider(owner, SimpleVMFactory {
                LibraryFilesViewModel(
                    context,
                    store,
                    discardDraft = {},
                    readExportEntries = { exports ->
                        val snapshot = exports.list()
                        if (reads.incrementAndGet() == 1) {
                            firstReadStarted.complete(Unit)
                            withContext(NonCancellable) { releaseOldRead.await() }
                        }
                        snapshot
                    }
                )
            })[LibraryFilesViewModel::class.java]
        }
        rule.waitUntil(5_000) { firstReadStarted.isCompleted }
        rule.runOnIdle {
            caller.launch {
                assertTrue(vm.exportMessage(UiMessage("fixture", "user", "controlled export")) is LibraryResult.Success)
            }
        }
        try {
            rule.waitUntil(5_000) { vm.exportsState.value.entries.size == 1 }
            releaseOldRead.complete(Unit)
            rule.waitForIdle()
            assertEquals(1, vm.exportsState.value.entries.size)
            assertTrue(reads.get() >= 2)
        } finally {
            releaseOldRead.complete(Unit)
            rule.runOnIdle { owner.clear() }
            caller.cancel()
            storeScope.cancel()
        }
    }

    private fun scrollPosition(): Float = rule.onNodeWithTag(ChatTags.MESSAGES)
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun configureHostWindow() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun assertResizeHost() {
        rule.runOnUiThread {
            assertEquals(
                "The fixture must mirror MainActivity's native RESIZE policy, not platform PAN",
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                rule.activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
            )
        }
    }

    private fun saveScreenshot(name: String) {
        val directory = File(rule.activity.getExternalFilesDir(null), "ui-acceptance")
        assertTrue(directory.isDirectory || directory.mkdirs())
        saveNativeScreenshotEvidence(rule.activity, directory, name) {
            rule.onRoot().captureToImage().asAndroidBitmap()
        }
    }

    private data class ImeGeometry(
        val visible: Boolean,
        val bottomInset: Int,
        val windowBounds: AndroidRect,
        val windowOriginOnScreen: Offset
    ) {
        val topOnScreen: Float get() = (windowBounds.bottom - bottomInset).toFloat()
    }

    private fun rootImeGeometry(): ImeGeometry? = rule.runOnUiThread {
        val decor = rule.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor) ?: return@runOnUiThread null
        val onScreen = IntArray(2)
        val inWindow = IntArray(2)
        decor.getLocationOnScreen(onScreen)
        decor.getLocationInWindow(inWindow)
        ImeGeometry(
            visible = insets.isVisible(WindowInsetsCompat.Type.ime()),
            bottomInset = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            windowBounds = AndroidRect(rule.activity.windowManager.currentWindowMetrics.bounds),
            windowOriginOnScreen = Offset(
                (onScreen[0] - inWindow[0]).toFloat(),
                (onScreen[1] - inWindow[1]).toFloat()
            )
        )
    }

    private fun waitForRootIme(visible: Boolean) {
        var previous: ImeGeometry? = null
        var stableSince = 0L
        rule.waitUntil(timeoutMillis = 10_000) {
            val geometry = rootImeGeometry()
            val matches = geometry != null && geometry.visible == visible &&
                (if (visible) geometry.bottomInset > 0 else geometry.bottomInset == 0)
            val now = SystemClock.uptimeMillis()
            if (!matches || geometry != previous) {
                previous = geometry
                stableSince = now
                false
            } else {
                // Wait out platform IME animation, not just Compose's own frame clock.
                now - stableSince >= 250L
            }
        }
    }

    private fun assertControlAboveIme(tag: String, ime: ImeGeometry) {
        val bounds = controlBoundsOnScreen(tag, ime)
        assertTrue("$tag must have non-empty bounds: $bounds", bounds.width > 0 && bounds.height > 0)
        assertTrue(
            "$tag extends under the physical IME: bounds=$bounds, IME top=${ime.topOnScreen}, window=${ime.windowBounds}",
            bounds.bottom <= ime.topOnScreen + 1f
        )
        assertTrue(
            "$tag must remain inside the real window: bounds=$bounds, window=${ime.windowBounds}",
            bounds.left >= ime.windowBounds.left - 1f &&
                bounds.right <= ime.windowBounds.right + 1f &&
                bounds.top >= ime.windowBounds.top - 1f
        )
    }

    private fun controlBoundsOnScreen(tag: String, ime: ImeGeometry): Rect {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        // Use the full measured control, not its clipped visible rectangle.
        val origin = node.positionInWindow + ime.windowOriginOnScreen
        return Rect(
            origin.x, origin.y,
            origin.x + node.size.width,
            origin.y + node.size.height
        )
    }

    private fun fixtureSession() = Session(
        id = "controlled-chat",
        title = "受控测试会话 \uD83D\uDE80",
        model = "fixture-model",
        messages = (0..29).map { index ->
            UiMessage(
                id = "controlled-$index",
                role = if (index % 2 == 0) "user" else "assistant",
                content = "第 $index 条受控消息\n用于检查滚动与长文字。\nNo private conversation is used.",
                isStreaming = index == 29
            )
        }.toMutableList()
    )

    private fun fixtureContext(root: File): Context = object : ContextWrapper(rule.activity.applicationContext) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = File(root, "cache")
        override fun getNoBackupFilesDir(): File = File(root, "no-backup")
    }

    @Composable
    private fun FixtureChat(
        session: MutableState<Session>,
        sending: MutableState<Boolean>,
        text: MutableState<String> = remember { mutableStateOf("") },
        offline: Boolean = false
    ) {
        ChatContent(
            session = session.value,
            catalog = ModelCatalogState(models = listOf(ModelInfo("fixture-model", "测试模型")), loading = false),
            draft = DraftUiState(draft = ComposerDraft(text = text.value), loading = false),
            sending = sending.value,
            error = null,
            snackbar = remember { SnackbarHostState() },
            onBack = {},
            onModelSelect = {},
            onRefreshModels = {},
            onTextChange = { text.value = it },
            onSend = {},
            onStop = { sending.value = false },
            onPickText = {},
            onPickImages = {},
            onVoice = {},
            onRemoveAttachment = {},
            attachmentFile = { error("Fixture contains no attachment references") },
            onRetryDraft = {},
            onEdit = {},
            onDelete = {},
            onRegenerate = {},
            onRetry = {},
            onShare = {},
            networkUnavailable = offline
        )
    }

    @Composable
    private fun FixtureComposer(text: MutableState<String>) {
        ChatComposer(
            text = text.value,
            attachments = emptyList(),
            onTextChange = { text.value = it },
            onSend = {},
            onStop = {},
            onPickText = {},
            onPickImages = {},
            onVoice = {},
            onRemoveAttachment = {},
            onPreviewAttachment = {}
        )
    }

    @Composable
    private fun FixtureTheme(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            CopilotGoTheme(darkTheme = dark, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), content = content)
            }
        }
    }
}
