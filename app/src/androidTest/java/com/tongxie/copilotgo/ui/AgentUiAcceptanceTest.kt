package com.tongxie.copilotgo.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.agent.AgentApprovalBinding
import com.tongxie.copilotgo.data.agent.AgentApprovalDecision
import com.tongxie.copilotgo.data.agent.AgentApprovalRequest
import com.tongxie.copilotgo.data.agent.AgentApprovalResponse
import com.tongxie.copilotgo.data.agent.AgentRunRecord
import com.tongxie.copilotgo.data.agent.AgentRunStatus
import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import com.tongxie.copilotgo.data.agent.AgentStepRecord
import com.tongxie.copilotgo.data.agent.AgentToolCallRecord
import com.tongxie.copilotgo.data.agent.AgentToolCallStatus
import com.tongxie.copilotgo.data.agent.AgentToolIdentity
import com.tongxie.copilotgo.data.agent.AgentToolKind
import com.tongxie.copilotgo.data.agent.SourceKind
import com.tongxie.copilotgo.data.agent.SourceReference
import com.tongxie.copilotgo.data.chat.ModelCapabilities
import com.tongxie.copilotgo.data.chat.ModelCatalogState
import com.tongxie.copilotgo.data.chat.ModelInfo
import com.tongxie.copilotgo.data.chat.ModelSupports
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.ui.agent.AgentModeContent
import com.tongxie.copilotgo.ui.agent.AgentRunDetailsContent
import com.tongxie.copilotgo.ui.agent.AgentSourceRow
import com.tongxie.copilotgo.ui.agent.AgentTags
import com.tongxie.copilotgo.ui.agent.agentCitationLinks
import com.tongxie.copilotgo.ui.agent.rememberAgentSourceOpener
import com.tongxie.copilotgo.ui.components.ChatTags
import com.tongxie.copilotgo.ui.components.MessageBubble
import com.tongxie.copilotgo.ui.draft.ComposerDraft
import com.tongxie.copilotgo.ui.screens.ChatContent
import com.tongxie.copilotgo.ui.theme.CopilotGoTheme
import com.tongxie.copilotgo.ui.viewmodel.DraftUiState
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI contract fixtures only; integration owns device runs and real transport acceptance. */
@RunWith(AndroidJUnit4::class)
class AgentUiAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun configureWindow() {
        rule.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun defaultChatAndUnavailableToolsAreExplicitAtLargeFont() {
        val openedSettings = AtomicInteger()
        rule.setContent {
            FixtureTheme {
                AgentModeContent(
                    settings = AgentSessionSettings(),
                    model = ModelInfo("controlled-no-tools"),
                    providerDisclosure = "受控服务商说明，不会发出网络请求。",
                    publicWebReady = false,
                    saving = false,
                    error = null,
                    onSave = { _, _ -> error("An unsupported model cannot enable Agent") },
                    onOpenTools = { openedSettings.incrementAndGet() },
                    onBack = {}
                )
            }
        }
        rule.onNodeWithText(text(R.string.agent_mode_chat)).assertIsSelected()
        rule.onNodeWithTag("agent-mode-choice").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText(text(R.string.agent_model_no_tools)).performScrollTo().assertIsDisplayed()
        saveScreenshot("agent-mode-disabled-200")
        rule.onNodeWithText(text(R.string.agent_tools_settings)).performScrollTo()
            .assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, openedSettings.get())
    }

    @Test fun approvalUsesExactBindingAndStopRemainsReachableAtLargeFont() {
        val request = request()
        val run = mutableStateOf(waitingRun(request))
        val decisions = mutableListOf<Pair<AgentApprovalBinding, AgentApprovalDecision>>()
        val stopped = AtomicInteger()
        rule.setContent {
            FixtureTheme {
                AgentRunDetailsContent(
                    run.value, request, false, null, {},
                    onDecision = { binding, decision ->
                        decisions += binding to decision
                        run.value = run.value.copy(status = AgentRunStatus.RUNNING, pendingApproval = null)
                    },
                    onStop = {
                        stopped.incrementAndGet()
                        run.value = run.value.copy(status = AgentRunStatus.CANCELLED, pendingApproval = null)
                    },
                    onBack = {}, onOpenSource = {}
                )
            }
        }
        waitForEnabled(AgentTags.APPROVE)
        rule.onNodeWithTag(AgentTags.APPROVE).performScrollTo().assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        saveScreenshot("agent-approval-actions-200")
        rule.onNodeWithTag(AgentTags.STOP).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(AgentTags.APPROVE).performClick()
        rule.runOnIdle { assertEquals(listOf(request.binding to AgentApprovalDecision.APPROVE), decisions) }
        rule.onNodeWithTag(AgentTags.APPROVE).assertIsNotEnabled()
        rule.onNodeWithTag(AgentTags.STOP).performClick()
        assertEquals(1, stopped.get())
        assertEquals(AgentRunStatus.CANCELLED, run.value.status)
    }

    @Test fun changedConfigurationAndArgumentsCannotRetargetAnOpenApproval() {
        val original = request()
        val current = original.copy(
            binding = original.binding.copy(
                approvalId = "approval-new", argumentsDigest = "changed-arguments",
                tool = original.binding.tool.copy(configRevision = 2)
            ),
            arguments = buildJsonObject { put("query", "changed controlled query") }
        )
        val run = mutableStateOf(waitingRun(original))
        val reviewed = mutableStateOf(original)
        val reviewedBindings = mutableListOf<AgentApprovalBinding>()
        val decisions = mutableListOf<AgentApprovalBinding>()
        rule.setContent {
            FixtureTheme {
                AgentRunDetailsContent(
                    run.value, reviewed.value, false, null,
                    { reviewedBindings += it.binding; reviewed.value = it },
                    onDecision = { binding, _ -> decisions += binding },
                    onStop = {}, onBack = {}, onOpenSource = {}
                )
            }
        }
        waitForEnabled(AgentTags.APPROVE)
        rule.runOnIdle { run.value = waitingRun(current) }
        rule.onNodeWithTag(AgentTags.APPROVE).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(AgentTags.DENY).assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.agent_approval_stale)).performScrollTo().assertIsDisplayed()
        assertTrue(decisions.isEmpty())
        saveScreenshot("agent-stale-approval-200")
        saveSemantics("agent-review-before-lazy-scroll")
        rule.onNodeWithTag(AgentTags.DETAILS).performScrollToKey("pending-${current.binding.approvalId}")
        rule.onNodeWithTag(AgentTags.REVIEW).assertIsDisplayed().assertIsEnabled()
        saveSemantics("agent-review-before-current-click")
        rule.onNodeWithTag(AgentTags.REVIEW).performClick()
        rule.runOnIdle { assertEquals(listOf(current.binding), reviewedBindings) }
        saveSemantics("agent-review-after-current-click")
        waitForEnabled(AgentTags.APPROVE)
        rule.onNodeWithTag(AgentTags.DENY).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(current.binding), decisions) }
    }

    @Test fun denialRemainsVisibleWithoutInventingAResult() {
        val proposal = request()
        val run = mutableStateOf(waitingRun(proposal))
        val decisions = mutableListOf<AgentApprovalDecision>()
        rule.setContent {
            FixtureTheme {
                AgentRunDetailsContent(
                    run.value, proposal, false, null, {},
                    onDecision = { _, decision ->
                        decisions += decision
                        run.value = run.value.copy(
                            status = AgentRunStatus.COMPLETED,
                            pendingApproval = null,
                            steps = listOf(AgentStepRecord(
                                0, toolCalls = listOf(call(proposal).copy(status = AgentToolCallStatus.DENIED))
                            ))
                        )
                    },
                    onStop = {}, onBack = {}, onOpenSource = {}
                )
            }
        }
        rule.onNodeWithTag(AgentTags.DENY).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.agent_call_denied)).performScrollTo()
        saveSemantics("agent-denial-before-lazy-scroll")
        rule.onNodeWithTag(AgentTags.DETAILS).performScrollToKey("${run.value.id}-${proposal.binding.callId}")
        saveSemantics("agent-denial")
        val callRecord = rule.onNodeWithTag("agent-call-record-${proposal.binding.callId}").fetchSemanticsNode()
        val callStatus = rule.onNodeWithTag("agent-call-status-${proposal.binding.callId}").fetchSemanticsNode()
        assertTrue("The call record must actually be placed", callRecord.layoutInfo.isPlaced && callStatus.layoutInfo.isPlaced)
        rule.onNodeWithText(text(R.string.agent_call_denied)).assertIsDisplayed()
        rule.onNodeWithTag(AgentTags.DETAILS).performScrollToKey("review-${proposal.binding.approvalId}")
        rule.onNodeWithTag(AgentTags.APPROVE).performScrollTo().assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(listOf(AgentApprovalDecision.DENY), decisions)
            assertTrue(run.value.sources.isEmpty())
            assertTrue(run.value.steps.single().toolCalls.single().result == null)
        }
    }

    @Test fun unknownRemoteOutcomeKeepsPartialAnswerAndBlocksDestructiveReplay() {
        val proposal = request()
        val partial = "受控的部分回答保留在这里"
        val run = waitingRun(proposal).copy(
            status = AgentRunStatus.INTERRUPTED,
            pendingApproval = null,
            steps = listOf(AgentStepRecord(
                0, toolCalls = listOf(call(proposal).copy(
                    status = AgentToolCallStatus.INTERRUPTED, startedAt = 1, outcomeUnknown = true
                ))
            ))
        )
        val replayed = AtomicInteger()
        rule.setContent {
            FixtureTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MessageBubble(
                        UiMessage("message", "assistant", partial, agentRun = run),
                        replayBlocked = true,
                        onRegenerate = { replayed.incrementAndGet() },
                        onDelete = { replayed.incrementAndGet() }
                    )
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText(partial).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(partial).performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.message_actions, text(R.string.message_author_assistant)))
            .performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.message_regenerate)).assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.action_delete)).assertIsNotEnabled()
        assertEquals(0, replayed.get())
    }

    @Test fun sourceCardsOpenExactRuntimeDestinationsAndExposeProvenance() {
        val opened = mutableListOf<String>()
        val hit = SourceReference(
            "https://example.com/search?fixture=one#section", "受控搜索结果",
            SourceKind.SEARCH_HIT, "S1", "call-search", "实际搜索摘要，不声称已读取全文。"
        )
        val page = SourceReference(
            "https://example.com/final-page", "受控已读取网页",
            SourceKind.FETCHED_PAGE, "S2", "call-page"
        )
        rule.setContent {
            FixtureTheme(dark = true) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AgentSourceRow(hit, { opened += it })
                    AgentSourceRow(page, { opened += it })
                }
            }
        }
        rule.onNodeWithTag("agent-source-S1").performScrollTo().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        rule.onNode(
            hasTestTag("agent-source-S1") and SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, text(R.string.agent_source_search_hit)
            )
        ).performClick()
        rule.onNodeWithTag("agent-source-S2").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(hit.url, page.url), opened) }
        saveScreenshot("agent-actual-sources-dark-200")
    }

    @Test fun inlineCitationOpensOnlyItsRealSourceAndCodeStaysLiteral() {
        val opened = mutableListOf<String>()
        val sourceCardsOpened = mutableListOf<String>()
        val source = SourceReference("https://example.com/actual", "受控来源", SourceKind.FETCHED_PAGE, "S1")
        val message = UiMessage(
            "message", "assistant", "[S1]\n\n`[S1]`\n\n[S999]",
            agentRun = AgentRunRecord("run", 7, AgentRunStatus.COMPLETED, sources = listOf(source))
        )
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        rule.setContent {
            FixtureTheme {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MessageBubble(
                            message,
                            citationLinks = agentCitationLinks(listOf(message), message.id),
                            onOpenAgentSource = { sourceCardsOpened += it }
                        )
                    }
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(textOwner("[S1]", code = true)).fetchSemanticsNodes().isNotEmpty() }
        val body = rule.onNodeWithTag("agent-message-body-${message.id}").fetchSemanticsNode()
        val sources = rule.onNodeWithTag("agent-message-sources-${message.id}").fetchSemanticsNode()
        assertTrue(
            "Sources must follow the answer in flow, never overlap its links or literal text",
            body.positionInWindow.y + body.size.height <= sources.positionInWindow.y + 1f
        )
        rule.onNode(textOwner("[S1]", code = false)).performScrollTo().assertIsDisplayed()
        saveSemantics("agent-citation-before-link")
        rule.onNode(textOwner("[S1]", code = false)).performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(listOf(source.url), opened) }
        rule.onNode(textOwner("[S1]", code = true)).performScrollTo().assertIsDisplayed()
        assertNoLinkAnnotations("[S1]", code = true)
        saveSemantics("agent-citation-before-code")
        rule.onNode(textOwner("[S1]", code = true)).performTouchInput { click(center) }
        rule.onNode(textOwner("[S999]", code = false)).performScrollTo().assertIsDisplayed()
        assertNoLinkAnnotations("[S999]", code = false)
        saveSemantics("agent-citation-before-unknown")
        rule.onNode(textOwner("[S999]", code = false)).performTouchInput { click(center) }
        saveSemantics("agent-citation-after-taps")
        rule.runOnIdle {
            assertEquals(listOf(source.url), opened)
            assertTrue("Non-source text must not dispatch a source-card action", sourceCardsOpened.isEmpty())
        }
    }

    @Test fun productionSourceHandlerDispatchesTheExactAndroidBrowserIntent() {
        val intents = CopyOnWriteArrayList<Intent>()
        val feedback = CopyOnWriteArrayList<String>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                intents += Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val source = SourceReference(
            "https://example.com/actual-source?fixture=one#section", "受控来源的原生浏览器操作",
            SourceKind.FETCHED_PAGE, "S7", "actual-fixture-call"
        )
        instrumentation.addMonitor(monitor)
        try {
            rule.setContent {
                FixtureTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        AgentSourceRow(source, rememberAgentSourceOpener { feedback += it })
                    }
                }
            }
            rule.onNodeWithTag("agent-source-S7").performScrollTo().performClick()
            rule.waitUntil(5_000) { intents.isNotEmpty() }
            assertEquals(1, intents.size)
            assertEquals(Intent.ACTION_VIEW, intents.single().action)
            assertEquals(source.url, intents.single().dataString)
            assertTrue(feedback.isEmpty())
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun systemBackLeavesReviewWithoutCancellingTheRunOrClearingDraft() {
        val session = mutableStateOf(chatSession())
        val sending = mutableStateOf(true)
        val stopped = AtomicInteger()
        rule.setContent {
            FixtureTheme {
                FixtureChat(session, sending, onStop = { stopped.incrementAndGet(); sending.value = false })
            }
        }
        rule.onNodeWithTag(AgentTags.TOOLBAR_ACTIVITY).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        Espresso.pressBack()
        rule.waitUntil(10_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        assertTrue(sending.value)
        assertEquals(0, stopped.get())
        rule.onNodeWithTag(ChatTags.STOP).assertIsDisplayed().performClick()
        assertFalse(sending.value)
        assertEquals(1, stopped.get())
        rule.onNodeWithText("下一条受控草稿").assertIsDisplayed()
    }

    @Test fun toolProgressAndCompletionRespectManualReadingPosition() {
        val session = mutableStateOf(chatSession(history = true))
        val sending = mutableStateOf(true)
        rule.setContent { FixtureTheme { FixtureChat(session, sending) } }
        rule.waitForIdle()
        rule.onNodeWithTag(ChatTags.MESSAGES).performTouchInput { swipeDown() }
        rule.onNodeWithTag(ChatTags.LATEST).assertIsDisplayed()
        val before = scrollPosition()
        rule.runOnIdle {
            val current = session.value
            val messages = current.messages.toMutableList()
            val last = messages.last()
            messages[messages.lastIndex] = last.copy(
                content = "受控的新增部分回答",
                agentRun = requireNotNull(last.agentRun).copy(status = AgentRunStatus.COMPLETED, pendingApproval = null)
            )
            session.value = current.copy(messages = messages, revision = current.revision + 1)
            sending.value = false
        }
        rule.waitForIdle()
        assertEquals(before, scrollPosition(), 0.5f)
        rule.onNodeWithTag(ChatTags.LATEST).assertIsDisplayed()
        saveScreenshot("agent-manual-reading")
        rule.onNodeWithTag(ChatTags.LATEST).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ChatTags.LATEST).assertDoesNotExist()
    }

    @Composable
    private fun FixtureChat(
        session: MutableState<Session>,
        sending: MutableState<Boolean>,
        onStop: () -> Unit = { sending.value = false }
    ) {
        ChatContent(
            session = session.value,
            catalog = ModelCatalogState(models = listOf(ModelInfo(
                "fixture-model", capabilities = ModelCapabilities(supports = ModelSupports(toolCalls = true))
            ))),
            draft = DraftUiState(draft = ComposerDraft(text = "下一条受控草稿"), loading = false),
            sending = sending.value,
            error = null,
            snackbar = remember { SnackbarHostState() },
            onBack = {}, onModelSelect = {}, onRefreshModels = {}, onTextChange = {}, onSend = {},
            onStop = onStop, onPickText = {}, onPickImages = {}, onVoice = {}, onRemoveAttachment = {},
            attachmentFile = { error("This fixture has no attachments") },
            onRetryDraft = {}, onEdit = {}, onDelete = {}, onRegenerate = {}, onRetry = {}, onShare = {},
            onOpenAgentMode = {},
            onAgentDecision = { _, _ -> AgentApprovalResponse.Rejected("受控回调不执行真实工具。") }
        )
    }

    private fun chatSession(history: Boolean = false): Session {
        val messages = if (history) (0..19).map {
            UiMessage("history-$it", if (it % 2 == 0) "user" else "assistant", "受控历史 $it\n".repeat(12))
        }.toMutableList() else mutableListOf()
        messages += UiMessage("user", "user", "受控工具请求")
        messages += UiMessage("agent", "assistant", "", isStreaming = true, agentRun = waitingRun(request()))
        return Session(
            "fixture-session", "受控 Agent 会话", "fixture-model", messages = messages,
            agentSettings = AgentSessionSettings(enabled = true)
        )
    }

    private fun request(): AgentApprovalRequest {
        val identity = AgentToolIdentity("fixture-server", 1, "fixture_read", "fixture-definition")
        val binding = AgentApprovalBinding("approval-fixture", "run-fixture", 7, "call-fixture", identity, "fixture-arguments", "fixture-descriptor")
        return AgentApprovalRequest(
            binding, "fixture_read", "https://example.com/controlled-mcp",
            buildJsonObject { put("query", "受控查询：明确显示实际参数与目标") },
            System.currentTimeMillis() - 1_000, System.currentTimeMillis() + 300_000
        )
    }

    private fun call(request: AgentApprovalRequest) = AgentToolCallRecord(
        request.binding.callId, request.toolName, request.binding.tool, request.destination,
        kind = AgentToolKind.MCP, arguments = request.arguments, argumentsDigest = request.binding.argumentsDigest,
        status = AgentToolCallStatus.AWAITING_APPROVAL
    )

    private fun waitingRun(request: AgentApprovalRequest) = AgentRunRecord(
        request.binding.runId, request.binding.accountGeneration, AgentRunStatus.AWAITING_APPROVAL,
        steps = listOf(AgentStepRecord(0, toolCalls = listOf(call(request)))), pendingApproval = request
    )

    private fun waitForEnabled(tag: String) {
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun scrollPosition(): Float = rule.onNodeWithTag(ChatTags.MESSAGES).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun text(id: Int, vararg arguments: Any): String = rule.activity.getString(id, *arguments)

    private fun textOwner(value: String, code: Boolean): SemanticsMatcher = SemanticsMatcher(
        "Text layout for $value with code=$code"
    ) { node ->
        node.config.contains(SemanticsActions.GetTextLayoutResult) &&
            node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].any { text ->
                text.text == value &&
                    text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace } == code
            }
    }

    @Composable
    private fun FixtureTheme(dark: Boolean = false, fontScale: Float = 2f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            CopilotGoTheme(darkTheme = dark, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun saveScreenshot(name: String) {
        val directory = File(rule.activity.getExternalFilesDir(null), "agent-acceptance")
        assertTrue(directory.isDirectory || directory.mkdirs())
        saveNativeScreenshotEvidence(rule.activity, directory, name) {
            rule.onRoot().captureToImage().asAndroidBitmap()
        }
    }

    private fun saveSemantics(name: String) {
        val directory = File(rule.activity.getExternalFilesDir(null), "agent-acceptance")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val textDetails = listOf("[S1]", "[S999]").flatMap { value ->
            rule.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
                val text = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text] else emptyList()
                "text=$value; placed=${node.layoutInfo.isPlaced}; position=${node.positionInWindow}; size=${node.size}; bounds=${node.boundsInWindow}; " +
                    "links=${text.flatMap { it.getLinkAnnotations(0, it.length) }}; spans=${text.flatMap { it.spanStyles }}"
            }
        }.joinToString("\n")
        val actionDetails = listOf(
            AgentTags.REVIEW, AgentTags.APPROVE, AgentTags.DENY,
            "agent-call-record-call-fixture", "agent-call-status-call-fixture"
        ).flatMap { tag ->
            rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
                "tag=$tag; placed=${node.layoutInfo.isPlaced}; position=${node.positionInWindow}; size=${node.size}; bounds=${node.boundsInWindow}"
            }
        }.joinToString("\n")
        File(directory, "$name.txt").writeText(
            rule.onRoot(useUnmergedTree = true).printToString() + "\n\n" + textDetails + "\n\n" + actionDetails
        )
    }

    private fun assertNoLinkAnnotations(value: String, code: Boolean) {
        val node = rule.onNode(textOwner(value, code)).fetchSemanticsNode()
        assertTrue(
            "Literal text must not contain a URL annotation",
            node.config[SemanticsProperties.Text].all { it.getLinkAnnotations(0, it.length).isEmpty() }
        )
    }
}
