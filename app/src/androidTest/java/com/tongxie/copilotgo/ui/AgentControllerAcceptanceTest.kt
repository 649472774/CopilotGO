package com.tongxie.copilotgo.ui

import android.graphics.Rect as AndroidRect
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tongxie.copilotgo.data.agent.AgentApprovalDecision
import com.tongxie.copilotgo.data.agent.AgentApprovalResponse
import com.tongxie.copilotgo.data.agent.AgentRunStatus
import com.tongxie.copilotgo.data.agent.AgentToolCallStatus
import com.tongxie.copilotgo.ui.agent.AgentTags
import com.tongxie.copilotgo.ui.components.ChatTags
import com.tongxie.copilotgo.ui.screens.ChatScreen
import com.tongxie.copilotgo.ui.theme.CopilotGoTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The actual screen, controllers, engine and durable draft/session stores, without live accounts. */
@RunWith(AndroidJUnit4::class)
class AgentControllerAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val fixtures = mutableListOf<AgentControllerFixture>()

    @Before fun prepareWindow() = configureWindow()

    @After fun releaseFixtures() {
        val jobs = rule.runOnIdle { fixtures.flatMap { it.close() } }
        runBlocking(Dispatchers.IO) { withTimeout(10_000) { jobs.joinAll() } }
        fixtures.forEach {
            assertEquals("No fixture may contact an uncontrolled endpoint", 0, it.unexpectedRequests.get())
            assertTrue(it.root.name.startsWith("agent-controller-fixture-"))
            assertTrue(it.root.deleteRecursively())
        }
    }

    @Test fun realModeSaveAdmissionApprovalAndContinuationKeepTheNextDraft() {
        val fixture = fixture(enabled = false)
        show(fixture)
        rule.onNodeWithTag(AgentTags.MODE).performClick()
        rule.onNodeWithTag("agent-mode-choice").performScrollTo().performClick()
        rule.onNodeWithTag("agent-mode-save").performScrollTo().performClick()
        rule.waitUntil(10_000) {
            fixture.center.sessionFlow(fixture.id).value?.agentSettings?.enabled == true &&
                rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(runBlocking(Dispatchers.IO) { fixture.persistedSession().agentSettings.enabled })
        sendControlledPrompt(fixture)
        val acceptedId = fixture.center.sessionFlow(fixture.id).value!!.messages.first().submissionId
        assertTrue(!acceptedId.isNullOrBlank())
        assertEquals(1, fixture.center.sessionFlow(fixture.id).value!!.messages.count { it.role == "user" })
        assertTrue(fixture.tools.invocations.isEmpty())

        rule.onNodeWithTag(ChatTags.INPUT).performClick().performTextReplacement("下一条草稿不能被工具结果清除")
        Espresso.closeSoftKeyboard()
        rule.waitUntil(10_000) { fixture.drafts.state(fixture.id).value.draft.text == "下一条草稿不能被工具结果清除" }
        rule.onNodeWithTag(AgentTags.TOOLBAR_ACTIVITY).performClick()
        waitForEnabled(AgentTags.APPROVE)
        rule.onNodeWithTag(AgentTags.APPROVE).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(10_000) { !fixture.center.sendingFlow(fixture.id).value }
        val completed = fixture.center.sessionFlow(fixture.id).value!!.messages.last()
        assertEquals(AgentRunStatus.COMPLETED, completed.agentRun?.status)
        assertEquals(1, fixture.tools.invocations.size)
        assertEquals(2, fixture.modelRequests.size)
        assertEquals("https://example.com/controller-source", completed.agentRun!!.sources.single().url)
        assertEquals(AgentToolCallStatus.SUCCEEDED, completed.agentRun!!.steps.first().toolCalls.single().status)
        Espresso.pressBack()
        rule.waitUntil(5_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag(ChatTags.INPUT).assertIsDisplayed()
        assertEquals("下一条草稿不能被工具结果清除", fixture.drafts.state(fixture.id).value.draft.text)
        val persisted = runBlocking(Dispatchers.IO) { fixture.persistedSession() }
        assertEquals(acceptedId, persisted.messages.first().submissionId)
        assertEquals(1, persisted.messages.count { it.role == "user" })
        assertEquals(AgentRunStatus.COMPLETED, persisted.messages.last().agentRun?.status)
        saveScreenshot("agent-controller-approved-200")
    }

    @Test fun realImeBackAndViewModelRecreationDoNotOwnOrDiscardAnActiveRun() {
        val fixture = fixture(enabled = true)
        val visible = mutableStateOf(true)
        show(fixture, visible)
        sendControlledPrompt(fixture)
        val request = fixture.center.sessionFlow(fixture.id).value!!.messages.last().agentRun!!.pendingApproval!!
        val next = "保留新的多行草稿\n第二行仍可编辑"
        rule.onNodeWithTag(ChatTags.INPUT).performClick().performTextReplacement(next)
        waitForIme(visible = true)
        val ime = requireNotNull(imeGeometry())
        saveImeGeometry("agent-controller-ime-before-bounds", ime, fixture)
        saveScreenshot("agent-controller-ime-200")
        rule.onNodeWithTag(ChatTags.STOP).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertAboveIme(ChatTags.STOP, ime)
        assertAboveIme(ChatTags.INPUT, ime)
        val input = fullBounds(ChatTags.INPUT, ime)
        val viewport = fullBounds(ChatTags.EDITOR_VIEWPORT, ime)
        assertTrue("The whole editor must fit, not just a clipped fragment", input.top >= viewport.top - 1 && input.bottom <= viewport.bottom + 1)

        Espresso.pressBack()
        waitForIme(visible = false)
        assertTrue("The first Back only hides the keyboard", visible.value)
        assertTrue(fixture.center.sendingFlow(fixture.id).value)
        Espresso.pressBack()
        rule.waitUntil(5_000) { !visible.value }
        assertTrue("Popping the real ChatViewModel must not own the run", fixture.center.sendingFlow(fixture.id).value)
        assertEquals(next, fixture.drafts.state(fixture.id).value.draft.text)
        rule.onNodeWithText("重新打开受控会话").performClick()
        rule.waitUntil(5_000) { fixture.createdChatViews == 2 }
        rule.onNodeWithTag(ChatTags.STOP).assertIsEnabled().performClick()
        rule.waitUntil(10_000) { !fixture.center.sendingFlow(fixture.id).value }
        assertEquals(AgentRunStatus.CANCELLED, fixture.center.sessionFlow(fixture.id).value!!.messages.last().agentRun?.status)
        assertTrue(fixture.center.respondToApproval(fixture.id, request.binding, AgentApprovalDecision.APPROVE) is AgentApprovalResponse.Rejected)
        assertTrue(fixture.tools.invocations.isEmpty())
        assertEquals(next, fixture.drafts.state(fixture.id).value.draft.text)
        rule.waitUntil(10_000) { !fixture.drafts.state(fixture.id).value.saving }
        assertEquals(next, runBlocking(Dispatchers.IO) { fixture.persistedDraft().text })
        saveScreenshot("agent-controller-back-stop-200")
    }

    @Test fun realConfigurationInvalidationRejectsTheFrozenApprovalAndNeverRunsATool() {
        val fixture = fixture(enabled = true)
        show(fixture)
        sendControlledPrompt(fixture)
        val request = fixture.center.sessionFlow(fixture.id).value!!.messages.last().agentRun!!.pendingApproval!!
        rule.onNodeWithTag(AgentTags.TOOLBAR_ACTIVITY).performClick()
        waitForEnabled(AgentTags.APPROVE)
        rule.runOnIdle { fixture.tools.invalidate() }
        rule.waitUntil(10_000) { !fixture.center.sendingFlow(fixture.id).value }
        rule.onNodeWithTag(AgentTags.APPROVE).performScrollTo().assertIsNotEnabled()
        assertTrue(fixture.center.respondToApproval(fixture.id, request.binding, AgentApprovalDecision.APPROVE) is AgentApprovalResponse.Rejected)
        assertEquals(AgentRunStatus.INTERRUPTED, fixture.center.sessionFlow(fixture.id).value!!.messages.last().agentRun?.status)
        assertTrue(fixture.tools.invocations.isEmpty())
        val persisted = runBlocking(Dispatchers.IO) { fixture.persistedSession() }
        assertTrue(persisted.messages.last().agentRun?.pendingApproval == null)
        assertEquals(AgentRunStatus.INTERRUPTED, persisted.messages.last().agentRun?.status)
    }

    private fun fixture(enabled: Boolean): AgentControllerFixture {
        val root = File(rule.activity.cacheDir, "agent-controller-fixture-${UUID.randomUUID()}")
        val fixture = runBlocking(Dispatchers.IO) {
            check(root.mkdirs())
            AgentControllerFixture(rule.activity, root).also { it.initialize(enabled) }
        }
        fixtures += fixture
        rule.runOnIdle { fixture.createUiOwners() }
        return fixture
    }

    private fun show(fixture: AgentControllerFixture, visible: MutableState<Boolean> = mutableStateOf(true)) {
        rule.setContent {
            FixtureTheme {
                if (visible.value) {
                    BackHandler { visible.value = false }
                    val chat = remember { fixture.newChatView() }
                    DisposableEffect(chat) { onDispose { fixture.chatOwner.clear() } }
                    ChatScreen(
                        fixture.id, chat, fixture.models, fixture.drafts, fixture.files,
                        toolSettings = fixture.publicSettings, onOpenTools = {},
                        onBack = { visible.value = false }
                    )
                } else {
                    Button(onClick = { visible.value = true }) { Text("重新打开受控会话") }
                }
            }
        }
        configureWindow()
        rule.waitUntil(10_000) {
            !fixture.drafts.state(fixture.id).value.loading &&
                fixture.center.sessionFlow(fixture.id).value != null
        }
        rule.onNodeWithTag(ChatTags.INPUT).assertIsDisplayed()
    }

    private fun sendControlledPrompt(fixture: AgentControllerFixture) {
        rule.onNodeWithTag(ChatTags.INPUT).performClick().performTextReplacement("请运行受控工具")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithTag(ChatTags.SEND).assertIsEnabled().performClick()
        rule.waitUntil(10_000) {
            fixture.center.sessionFlow(fixture.id).value?.messages?.lastOrNull()?.agentRun?.pendingApproval != null &&
                !fixture.drafts.state(fixture.id).value.submitting
        }
        assertEquals("", fixture.drafts.state(fixture.id).value.draft.text)
        assertTrue(fixture.center.sendingFlow(fixture.id).value)
    }

    private fun waitForEnabled(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    private data class ImeGeometry(val visible: Boolean, val bottom: Int, val topSafe: Int, val window: AndroidRect, val origin: Offset)

    private fun imeGeometry(): ImeGeometry? = rule.runOnUiThread {
        val decor = rule.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor) ?: return@runOnUiThread null
        val screen = IntArray(2)
        val local = IntArray(2)
        decor.getLocationOnScreen(screen)
        decor.getLocationInWindow(local)
        ImeGeometry(
            insets.isVisible(WindowInsetsCompat.Type.ime()), insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top,
            AndroidRect(rule.activity.windowManager.currentWindowMetrics.bounds),
            Offset((screen[0] - local[0]).toFloat(), (screen[1] - local[1]).toFloat())
        )
    }

    private fun waitForIme(visible: Boolean) {
        var previous: ImeGeometry? = null
        var stableAt = 0L
        rule.waitUntil(10_000) {
            val geometry = imeGeometry()
            val now = SystemClock.uptimeMillis()
            if (geometry == null || geometry.visible != visible || geometry != previous ||
                (visible && geometry.bottom <= 0) || (!visible && geometry.bottom != 0)
            ) {
                previous = geometry
                stableAt = now
                false
            } else now - stableAt >= 250
        }
    }

    private fun fullBounds(tag: String, ime: ImeGeometry): Rect {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val position = node.positionInWindow + ime.origin
        return Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
    }

    private fun assertAboveIme(tag: String, ime: ImeGeometry) {
        val bounds = fullBounds(tag, ime)
        assertTrue("$tag must remain below system bars: bounds=$bounds, ime=$ime", bounds.top >= ime.window.top + ime.topSafe - 1)
        assertTrue("$tag must remain above the physical IME: bounds=$bounds, ime=$ime", bounds.bottom <= ime.window.bottom - ime.bottom + 1)
        assertTrue("$tag must fit the actual window: bounds=$bounds, ime=$ime", bounds.left >= ime.window.left - 1 && bounds.right <= ime.window.right + 1)
    }

    private fun saveImeGeometry(name: String, ime: ImeGeometry, fixture: AgentControllerFixture) {
        val directory = File(rule.activity.getExternalFilesDir(null), "agent-acceptance")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val nodes = listOf(
            ChatTags.INPUT, ChatTags.EDITOR_VIEWPORT, ChatTags.STOP, ChatTags.MESSAGES, ChatTags.LATEST
        ).flatMap { tag ->
            rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
                val scroll = if (node.config.contains(SemanticsProperties.VerticalScrollAxisRange)) {
                    val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
                    "value=${range.value()},max=${range.maxValue()}"
                } else "none"
                "tag=$tag; placed=${node.layoutInfo.isPlaced}; position=${node.positionInWindow}; " +
                    "size=${node.size}; visibleBounds=${node.boundsInWindow}; scroll=$scroll"
            }
        }.joinToString("\n")
        val state = "uptime=${SystemClock.uptimeMillis()}; ime=$ime; " +
            "sending=${fixture.center.sendingFlow(fixture.id).value}; " +
            "draftCharacters=${fixture.drafts.state(fixture.id).value.draft.text.length}"
        File(directory, "$name.txt").writeText(
            state + "\n" + nodes + "\n" + rule.onRoot(useUnmergedTree = true).printToString()
        )
    }

    private fun configureWindow() {
        rule.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Composable
    private fun FixtureTheme(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
            CopilotGoTheme(dynamicColor = false) { Surface(Modifier.fillMaxSize()) { content() } }
        }
    }

    private fun saveScreenshot(name: String) {
        val directory = File(rule.activity.getExternalFilesDir(null), "agent-acceptance")
        assertTrue(directory.isDirectory || directory.mkdirs())
        saveNativeScreenshotEvidence(rule.activity, directory, name) { rule.onRoot().captureToImage().asAndroidBitmap() }
    }
}
