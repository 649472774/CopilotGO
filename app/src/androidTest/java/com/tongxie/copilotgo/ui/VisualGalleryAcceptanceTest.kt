package com.tongxie.copilotgo.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.agent.AgentRunRecord
import com.tongxie.copilotgo.data.agent.AgentRunStatus
import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import com.tongxie.copilotgo.data.agent.AgentStepRecord
import com.tongxie.copilotgo.data.agent.AgentToolCallRecord
import com.tongxie.copilotgo.data.agent.AgentToolCallStatus
import com.tongxie.copilotgo.data.agent.AgentToolKind
import com.tongxie.copilotgo.data.agent.AgentToolResult
import com.tongxie.copilotgo.data.agent.SourceKind
import com.tongxie.copilotgo.data.agent.SourceReference
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.CopilotTokenClient
import com.tongxie.copilotgo.data.auth.CredentialStore
import com.tongxie.copilotgo.data.auth.DeviceFlowClient
import com.tongxie.copilotgo.data.auth.StoredCredentials
import com.tongxie.copilotgo.data.chat.ModelCapabilities
import com.tongxie.copilotgo.data.chat.ModelCatalogState
import com.tongxie.copilotgo.data.chat.ModelInfo
import com.tongxie.copilotgo.data.chat.ModelSupports
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.SessionSummary
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.proxy.ProxyHealthChecker
import com.tongxie.copilotgo.data.proxy.ProxySettingsStore
import com.tongxie.copilotgo.data.storage.SecretVault
import com.tongxie.copilotgo.ui.agent.AgentRunDetailsContent
import com.tongxie.copilotgo.ui.agent.AgentTags
import com.tongxie.copilotgo.ui.components.ChatTags
import com.tongxie.copilotgo.ui.draft.ComposerDraft
import com.tongxie.copilotgo.ui.screens.ChatContent
import com.tongxie.copilotgo.ui.screens.SessionListContent
import com.tongxie.copilotgo.ui.screens.SettingsScreen
import com.tongxie.copilotgo.ui.theme.CopilotGoTheme
import com.tongxie.copilotgo.ui.viewmodel.AuthViewModel
import com.tongxie.copilotgo.ui.viewmodel.DraftUiState
import com.tongxie.copilotgo.ui.viewmodel.ProxyViewModel
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Repeatable whole-window visual evidence from production Compose screens.
 * All content is synthetic. Device density/font scale and the default theme policy
 * are deliberately not overridden, so baseline and candidate use the same profile.
 */
@RunWith(AndroidJUnit4::class)
class VisualGalleryAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val fixtures = mutableListOf<SettingsFixture>()

    @After fun releaseFixtures() {
        rule.runOnIdle { fixtures.forEach { it.owner.clear() } }
        fixtures.forEach { fixture ->
            fixture.scope.cancel()
            assertEquals("Gallery fixtures must never access the network", 0, fixture.networkCalls.get())
            fixture.preferenceNames.forEach { rule.activity.deleteSharedPreferences(it) }
        }
    }

    @Test fun emptyLight() = captureChat(dark = false, empty = true)
    @Test fun emptyDark() = captureChat(dark = true, empty = true)
    @Test fun conversationLight() = captureChat(dark = false)
    @Test fun conversationDark() = captureChat(dark = true)
    @Test fun markdownSourcesLight() = captureMarkdown(dark = false)
    @Test fun markdownSourcesDark() = captureMarkdown(dark = true)
    @Test fun historyLight() = captureHistory(dark = false)
    @Test fun historyDark() = captureHistory(dark = true)
    @Test fun settingsLight() = captureSettings(dark = false)
    @Test fun settingsDark() = captureSettings(dark = true)
    @Test fun sourceDetailsLight() = captureSourceDetails(dark = false)
    @Test fun sourceDetailsDark() = captureSourceDetails(dark = true)

    private fun captureChat(dark: Boolean, empty: Boolean = false) {
        val session = Session(
            id = if (empty) "gallery-empty" else "gallery-conversation",
            title = if (empty) "新对话" else "让阅读更专注",
            model = MODEL_ID,
            messages = if (empty) mutableListOf() else mutableListOf(
                UiMessage("gallery-question", "user", "怎样把一个复杂问题讲清楚？请给我一个实用的方法。", createdAt = FIXTURE_TIME),
                UiMessage(
                    "gallery-answer", "assistant",
                    """
                    先把问题缩小到一个可以回答的句子，再补充必要的背景。

                    **可以从这三步开始：**

                    1. 说明你想解决什么，以及目前卡在哪里。
                    2. 给出一个具体例子，把事实和猜测分开。
                    3. 说清楚什么结果对你有帮助。

                    比如，与其问“怎么优化应用”，不如问“这段列表为什么在滚动时卡顿，应该先测量哪里”。

                    目标不是一次说完所有细节，而是让下一步足够清楚。
                    """.trimIndent(),
                    createdAt = FIXTURE_TIME
                )
            )
        )
        setGalleryContent(dark) { GalleryChat(session) }
        rule.onNodeWithTag(ChatTags.INPUT).assertIsDisplayed()
        rule.onNodeWithTag(ChatTags.ADD).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        if (!empty) {
            scrollToReadingStart()
            rule.onNodeWithTag("agent-message-body-gallery-question").assertIsDisplayed()
        }
        saveScreenshot("visual-${if (empty) "empty" else "conversation"}-${themeName(dark)}")
        if (!empty) assertOrdinaryMessageActions()
    }

    private fun assertOrdinaryMessageActions() {
        for (isUser in listOf(true, false)) {
            val author = rule.activity.getString(
                if (isUser) R.string.message_author_user else R.string.message_author_assistant
            )
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.message_copy, author))
                .performScrollTo().assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.message_actions, author))
                .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            rule.onNodeWithText(rule.activity.getString(R.string.message_share)).assertIsDisplayed().assertIsEnabled()
            rule.onNodeWithText(rule.activity.getString(
                if (isUser) R.string.message_edit else R.string.message_regenerate
            )).assertIsDisplayed().assertIsEnabled()
            rule.onNodeWithText(rule.activity.getString(R.string.action_delete)).assertIsDisplayed().assertIsEnabled()
            Espresso.pressBack()
        }
    }

    private fun captureMarkdown(dark: Boolean) {
        val run = completedRun()
        val session = Session(
            id = "gallery-markdown", title = "理解协程与取消", model = MODEL_ID,
            agentSettings = AgentSessionSettings(enabled = true),
            messages = mutableListOf(
                UiMessage("gallery-question", "user", "解释 Kotlin 协程的取消，并给出代码和参考来源。", createdAt = FIXTURE_TIME),
                UiMessage("gallery-answer", "assistant", MARKDOWN, createdAt = FIXTURE_TIME, agentRun = run)
            )
        )
        val opened = mutableListOf<String>()
        setGalleryContent(dark) { GalleryChat(session, onOpenSource = { opened += it }) }
        scrollToReadingStart()
        rule.onNodeWithTag("agent-message-body-gallery-question").assertIsDisplayed()
        saveScreenshot("visual-markdown-start-${themeName(dark)}")
        rule.onNodeWithTag(ChatTags.MESSAGES).performScrollToIndex(1)
        rule.waitForIdle()
        rule.onNodeWithText("取消是一种协作").assertIsDisplayed()
        rule.onNodeWithText("一个最小例子").assertIsDisplayed()
        saveScreenshot("visual-markdown-body-${themeName(dark)}")
        rule.onNodeWithTag(ChatTags.MESSAGES).performScrollToIndex(session.messages.size)
        rule.waitForIdle()
        rule.onNodeWithTag("agent-source-S2").assertIsDisplayed().assertHasClickAction()
        saveScreenshot("visual-markdown-sources-${themeName(dark)}")
        rule.onNodeWithTag("agent-source-S2").performClick()
        rule.runOnIdle { assertEquals(listOf(SOURCES[1].url), opened) }
    }

    private fun scrollToReadingStart() {
        // A real reading gesture relinquishes follow-tail before semantic positioning.
        rule.onNodeWithTag(ChatTags.MESSAGES).performTouchInput { swipeDown() }
        rule.onNodeWithTag(ChatTags.MESSAGES).performScrollToIndex(0)
        rule.waitForIdle()
    }

    private fun captureHistory(dark: Boolean) {
        val titles = listOf("理解协程与取消", "让阅读更专注", "整理周末的学习计划", "Compose 列表的滚动状态", "一份简单的旅行清单")
        val sessions = titles.mapIndexed { index, title ->
            SessionSummary(
                id = "gallery-history-$index", title = title, model = "GPT-5",
                createdAt = FIXTURE_TIME, updatedAt = FIXTURE_TIME - index * 3_600_000L,
                pinned = index == 0, revision = 1, messageCount = 4 + index * 2,
                preview = if (index == 0) "取消是协作式的，挂起点会检查当前任务的状态。" else "先明确目标，再把复杂的事情拆成下一步。",
                hasImages = false
            )
        }
        setGalleryContent(dark) {
            SessionListContent(
                sessions, loading = false, error = null, busy = false,
                snackbar = remember { SnackbarHostState() },
                onOpen = {}, onReload = {}, onNew = {}, onSettings = {}, onFiles = {}, onRemote = {},
                onTogglePin = {}, onRename = {}, onShare = {}, onDeleteRequest = {}
            )
        }
        rule.waitForIdle()
        saveScreenshot("visual-history-${themeName(dark)}")
    }

    private fun captureSettings(dark: Boolean) {
        lateinit var fixture: SettingsFixture
        rule.runOnIdle {
            fixture = SettingsFixture(rule.activity)
            fixtures += fixture
        }
        rule.waitUntil(10_000) { !fixture.authVm.initializing.value && fixture.proxyStore.initialized.value }
        assertEquals(null, fixture.proxyStore.loadError.value)
        setGalleryContent(dark) {
            CompositionLocalProvider(LocalContext provides fixture.context) {
                SettingsScreen(
                    fixture.authVm, fixture.proxyVm,
                    onOpenAccount = {}, onOpenProxy = {}, onOpenStorage = {}, onOpenAbout = {},
                    onBack = {}, onOpenTools = {}
                )
            }
        }
        val toggle = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.waitUntil(10_000) { rule.onAllNodes(toggle).fetchSemanticsNodes().size == 1 }
        saveScreenshot("visual-settings-${themeName(dark)}")
    }

    private fun captureSourceDetails(dark: Boolean) {
        setGalleryContent(dark) {
            AgentRunDetailsContent(
                run = completedRun(), reviewedApproval = null, approvalBusy = false, approvalError = null,
                onReviewApproval = {}, onDecision = { _, _ -> }, onStop = {}, onBack = {}, onOpenSource = {}
            )
        }
        rule.onNodeWithTag(AgentTags.DETAILS).assertIsDisplayed()
        saveScreenshot("visual-source-details-${themeName(dark)}")
    }

    private fun setGalleryContent(dark: Boolean, content: @Composable () -> Unit) {
        rule.activityRule.scenario.onActivity { activity ->
            val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
            activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        rule.setContent {
            CopilotGoTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), content = content)
            }
        }
        rule.waitForIdle()
    }

    @Composable
    private fun GalleryChat(session: Session, onOpenSource: (String) -> Unit = {}) {
        ChatContent(
            session = session,
            catalog = ModelCatalogState(
                models = listOf(ModelInfo(MODEL_ID, "GPT-5", capabilities = ModelCapabilities(
                    type = "chat", supports = ModelSupports(vision = true, toolCalls = true)
                ))),
                loading = false
            ),
            draft = DraftUiState(draft = ComposerDraft(), loading = false),
            sending = false, error = null, snackbar = remember { SnackbarHostState() },
            onBack = {}, onModelSelect = {}, onRefreshModels = {}, onTextChange = {}, onSend = {}, onStop = {},
            onPickText = {}, onPickImages = {}, onVoice = {}, onRemoveAttachment = {},
            attachmentFile = { error("The visual gallery contains no disk attachment references") },
            onRetryDraft = {}, onEdit = {}, onDelete = {}, onRegenerate = {}, onRetry = {}, onShare = {},
            onOpenAgentMode = {}, onOpenAgentSource = onOpenSource
        )
    }

    private fun saveScreenshot(name: String) {
        val directory = File(rule.activity.getExternalFilesDir(null), "ui-acceptance")
        assertTrue(directory.isDirectory || directory.mkdirs())
        rule.waitForIdle()
        saveNativeScreenshotEvidence(rule.activity, directory, name) {
            rule.onRoot().captureToImage().asAndroidBitmap()
        }
        File(directory, "$name-semantics.txt").writeText(rule.onRoot(useUnmergedTree = true).printToString())
    }

    private fun themeName(dark: Boolean) = if (dark) "dark" else "light"

    private fun completedRun() = AgentRunRecord(
        id = "gallery-run", accountGeneration = 7, status = AgentRunStatus.COMPLETED,
        startedAt = FIXTURE_TIME, finishedAt = FIXTURE_TIME + 2_000,
        steps = listOf(AgentStepRecord(0, toolCalls = listOf(
            AgentToolCallRecord(
                id = "gallery-read", name = "read_web_page", destination = SOURCES[0].url,
                kind = AgentToolKind.PUBLIC_WEB_READ,
                arguments = buildJsonObject { put("url", SOURCES[0].url) },
                status = AgentToolCallStatus.SUCCEEDED,
                result = AgentToolResult("Synthetic reference excerpt for layout; no network request was made.", sources = SOURCES),
                createdAt = FIXTURE_TIME, startedAt = FIXTURE_TIME, finishedAt = FIXTURE_TIME + 1_000
            )
        ))),
        sources = SOURCES
    )

    private class SettingsFixture(target: Context) {
        val owner = ViewModelStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val networkCalls = AtomicInteger()
        val preferenceNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val id = UUID.randomUUID().toString()
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val fixtureName = "visual-gallery-$id-$name"
                preferenceNames += fixtureName
                return super.getSharedPreferences(fixtureName, mode)
            }
        }
        private val credentials = object : CredentialStore {
            private val value = AtomicReference(StoredCredentials())
            override suspend fun readCredentials(): StoredCredentials = value.get()
            override suspend fun writeCredentials(credentials: StoredCredentials) { value.set(credentials) }
        }
        private val provider = object : HttpClientProvider {
            override val client = OkHttpClient.Builder().addInterceptor {
                networkCalls.incrementAndGet()
                throw IOException("Visual gallery forbids network access")
            }.build()
        }
        private val json = Json { ignoreUnknownKeys = true }
        private val auth = AuthRepository(credentials, DeviceFlowClient(provider, json), CopilotTokenClient(provider, json))
        private val vault = object : SecretVault {
            private val values = ConcurrentHashMap<String, String>()
            override suspend fun read(name: String): String? = values[name]
            override suspend fun write(name: String, value: String) { values[name] = value }
        }
        val proxyStore = ProxySettingsStore(MemoryPreferences(), vault, scope)
        val authVm = ViewModelProvider(owner, SimpleVMFactory { AuthViewModel(auth) })[AuthViewModel::class.java]
        val proxyVm = ViewModelProvider(owner, SimpleVMFactory {
            ProxyViewModel(proxyStore, ProxyHealthChecker(provider, auth))
        })[ProxyViewModel::class.java]
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(data.value).also { data.value = it }
        }
    }

    companion object {
        private const val MODEL_ID = "gallery-gpt-5"
        private const val FIXTURE_TIME = 1_788_796_800_000L
        private val SOURCES = listOf(
            SourceReference(
                "https://example.com/kotlin/cancellation", "协程取消与超时",
                SourceKind.FETCHED_PAGE, "S1", "gallery-read", "合成资料：挂起函数会检查任务是否仍然活跃。"
            ),
            SourceReference(
                "https://example.org/android/structured-concurrency", "结构化并发的任务边界",
                SourceKind.FETCHED_PAGE, "S2", "gallery-read", "合成资料：子任务跟随父任务的生命周期。"
            )
        )
        private val MARKDOWN = """
            ## 取消是一种协作

            协程不会在任意一行被强制中断。它会在挂起点或显式检查时响应取消，让资源有机会被正常释放。[S1]

            ### 一个最小例子

            ```kotlin
            val job = scope.launch {
                repeat(3) { index ->
                    ensureActive()
                    println("Step " + index)
                    delay(100)
                }
            }
            job.cancel()
            ```

            `ensureActive()` 适合放在耗时循环里；`delay()` 本身就是可取消的挂起函数。

            ### 记住任务的边界

            | 场景 | 建议 |
            | --- | --- |
            | 页面关闭 | 取消页面专属任务 |
            | 对话仍在生成 | 由应用服务持有任务 |
            | 用户点击停止 | 明确取消当前请求 |

            捕获异常时，继续抛出 `CancellationException`，不要把用户停止显示成网络失败。任务的所有权比启动它的位置更重要。[S2]

            **这是一组本地合成示例，来源地址只用于验证显示和点击，不会请求网络。**
        """.trimIndent()
    }
}
