package com.tongxie.copilotgo.ui

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import com.tongxie.copilotgo.data.chat.AttachmentKind
import com.tongxie.copilotgo.data.chat.AttachmentRef
import com.tongxie.copilotgo.data.chat.ModelCapabilities
import com.tongxie.copilotgo.data.chat.ModelCatalogState
import com.tongxie.copilotgo.data.chat.ModelInfo
import com.tongxie.copilotgo.data.chat.ModelSupports
import com.tongxie.copilotgo.data.chat.OperationResult
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.data.tools.ToolProblem
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.ToolSettingsSnapshot
import com.tongxie.copilotgo.data.tools.ToolSettingsState
import com.tongxie.copilotgo.data.tools.WebToolSettings
import com.tongxie.copilotgo.ui.agent.AgentModeContent
import com.tongxie.copilotgo.ui.agent.AutomaticSearchConsentDialog
import com.tongxie.copilotgo.ui.agent.AutomaticSearchSubmission
import com.tongxie.copilotgo.ui.agent.AutomaticSearchTags
import com.tongxie.copilotgo.ui.components.ChatTags
import com.tongxie.copilotgo.ui.draft.ComposerDraft
import com.tongxie.copilotgo.ui.screens.ChatContent
import com.tongxie.copilotgo.ui.theme.CopilotGoTheme
import com.tongxie.copilotgo.ui.viewmodel.DraftUiState
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production dialog and mode UI with synthetic callbacks only; no accounts, transport or device settings. */
@RunWith(AndroidJUnit4::class)
class AutomaticSearchConsentAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun prepareWindow() {
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    }

    @Test fun cancelKeepsTextAndAttachmentsWithoutAuthorizingOrSending() {
        val fixture = Fixture()
        show(fixture)
        rule.onNodeWithTag(AutomaticSearchTags.CONTEXT).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(AutomaticSearchTags.CANCEL).performScrollTo()
            .assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle {
            assertFalse(fixture.visible.value)
            assertEquals(fixture.submission.draft, fixture.draft.value)
            assertTrue(fixture.authorizationRevisions.isEmpty())
            assertTrue(fixture.submitted.isEmpty())
        }
    }

    @Test fun systemBackLeavesTheOriginalDraftWithoutAnyCallback() {
        val fixture = Fixture()
        show(fixture)
        Espresso.pressBack()
        rule.runOnIdle {
            assertFalse(fixture.visible.value)
            assertEquals(fixture.submission.draft, fixture.draft.value)
            assertTrue(fixture.authorizationRevisions.isEmpty())
            assertTrue(fixture.submitted.isEmpty())
        }
    }

    @Test fun repeatedConfirmUsesCapturedRevisionAndSubmitsOriginalSnapshotOnlyOnce() {
        val fixture = Fixture()
        val gate = CompletableDeferred<OperationResult>()
        fixture.gate = gate
        show(fixture)
        val confirm = rule.onNodeWithTag(AutomaticSearchTags.CONFIRM).performScrollTo()
            .assertHeightIsAtLeast(48.dp).assertIsEnabled()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnIdle {
            confirm()
            confirm()
        }
        rule.waitUntil { fixture.authorizationRevisions.size == 1 }
        rule.onNodeWithTag(AutomaticSearchTags.CONFIRM).assertIsNotEnabled()
        rule.runOnIdle { gate.complete(OperationResult.Accepted) }
        rule.waitUntil { !fixture.visible.value }
        rule.runOnIdle {
            assertEquals(listOf(9L), fixture.authorizationRevisions)
            assertEquals(listOf(fixture.submission), fixture.submitted)
            assertTrue(fixture.draft.value.isEmpty)
        }
    }

    @Test fun rejectedAuthorizationRetainsDraftAndAllowsAnExplicitRetry() {
        val fixture = Fixture()
        fixture.result.value = OperationResult.Rejected("受控授权保存失败")
        show(fixture)
        confirm()
        rule.onNodeWithText("受控授权保存失败").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(AutomaticSearchTags.CONFIRM).performScrollTo().assertIsEnabled()
        rule.runOnIdle {
            assertEquals(fixture.submission.draft, fixture.draft.value)
            assertTrue(fixture.submitted.isEmpty())
            fixture.result.value = OperationResult.Accepted
        }
        confirm()
        rule.waitUntil { !fixture.visible.value }
        assertEquals(listOf(9L, 9L), fixture.authorizationRevisions)
        assertEquals(listOf(fixture.submission), fixture.submitted)
    }

    @Test fun aDifferentDraftDuringAuthorizationIsNeverSubmittedOrCleared() {
        val fixture = Fixture()
        val gate = CompletableDeferred<OperationResult>()
        fixture.gate = gate
        show(fixture)
        confirm()
        rule.waitUntil { fixture.authorizationRevisions.size == 1 }
        val replacement = fixture.submission.draft.copy(
            text = "另一个未授权的问题", revision = 8, submissionId = "replacement-draft"
        )
        rule.runOnIdle {
            fixture.draft.value = replacement
            gate.complete(OperationResult.Accepted)
        }
        rule.onNodeWithText(text(R.string.automatic_search_stale_submission)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(AutomaticSearchTags.CONFIRM).performScrollTo().assertIsNotEnabled()
        rule.runOnIdle {
            assertTrue(fixture.visible.value)
            assertEquals(replacement, fixture.draft.value)
            assertTrue(fixture.submitted.isEmpty())
        }
    }

    @Test fun aChangedAgentModeDuringAuthorizationDoesNotSilentlySendInThatMode() {
        val fixture = Fixture()
        val gate = CompletableDeferred<OperationResult>()
        fixture.gate = gate
        show(fixture)
        confirm()
        rule.waitUntil { fixture.authorizationRevisions.size == 1 }
        rule.runOnIdle {
            fixture.agentSettings.value = fixture.agentSettings.value.copy(enabled = true)
            gate.complete(OperationResult.Accepted)
        }
        rule.onNodeWithText(text(R.string.automatic_search_stale_submission)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(AutomaticSearchTags.CONFIRM).performScrollTo().assertIsNotEnabled()
        rule.runOnIdle {
            assertTrue(fixture.submitted.isEmpty())
            assertEquals(fixture.submission.draft, fixture.draft.value)
        }
    }

    @Test fun cancellingWhileSavingPreventsSubmissionAfterLateSuccess() {
        val fixture = Fixture()
        val gate = CompletableDeferred<OperationResult>()
        fixture.gate = gate
        show(fixture)
        confirm()
        rule.waitUntil { fixture.authorizationRevisions.size == 1 }
        rule.onNodeWithTag(AutomaticSearchTags.CANCEL).performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle { gate.complete(OperationResult.Accepted) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse(fixture.visible.value)
            assertTrue(fixture.submitted.isEmpty())
            assertEquals(fixture.submission.draft, fixture.draft.value)
        }
    }

    @Test fun changedSearchSettingsInvalidateTheReviewedDisclosure() {
        val fixture = Fixture()
        show(fixture)
        rule.runOnIdle {
            fixture.tools.value = fixture.tools.value.copy(
                snapshot = ToolSettingsSnapshot(WebToolSettings(revision = 10, pageReaderEnabled = false))
            )
        }
        rule.onNodeWithText(text(R.string.automatic_search_stale_configuration)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(AutomaticSearchTags.CONFIRM).performScrollTo().assertIsNotEnabled()
        rule.runOnIdle {
            assertTrue(fixture.authorizationRevisions.isEmpty())
            assertTrue(fixture.submitted.isEmpty())
            assertEquals(fixture.submission.draft, fixture.draft.value)
        }
    }

    @Test fun loadingAndFailedSettingsNeverAuthorizeDefaultsAndRecoveryPreservesDraft() {
        val fixture = Fixture()
        fixture.tools.value = ToolSettingsState()
        show(fixture)
        rule.onNodeWithTag(AutomaticSearchTags.CONFIRM).performScrollTo().assertIsNotEnabled()
        rule.runOnIdle {
            fixture.tools.value = ToolSettingsState(
                loading = false,
                snapshot = ToolSettingsSnapshot(WebToolSettings(
                    revision = 9, externalSharingConsent = true, automaticSearchConsent = true
                )),
                problem = ToolProblem(ToolProblemCode.STORAGE, "受控配置读取失败")
            )
        }
        rule.onNodeWithText(text(R.string.automatic_search_settings_failed)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(AutomaticSearchTags.CONFIRM).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(AutomaticSearchTags.RECOVER).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle {
            assertEquals(1, fixture.openedTools.get())
            assertTrue(fixture.authorizationRevisions.isEmpty())
            assertTrue(fixture.submitted.isEmpty())
            assertEquals(fixture.submission.draft, fixture.draft.value)
        }
    }

    @Test fun delayedSettingsLoadCapturesOnlyTheConfigurationActuallyDisclosed() {
        val fixture = Fixture()
        fixture.tools.value = ToolSettingsState()
        show(fixture)
        rule.onNodeWithTag(AutomaticSearchTags.CONFIRM).performScrollTo().assertIsNotEnabled()
        rule.runOnIdle {
            fixture.tools.value = ToolSettingsState(
                loading = false,
                snapshot = ToolSettingsSnapshot(WebToolSettings(revision = 12, pageReaderEnabled = false))
            )
        }
        rule.onNodeWithText(text(R.string.automatic_search_pages_disabled)).performScrollTo().assertIsDisplayed()
        confirm()
        rule.waitUntil { !fixture.visible.value }
        assertEquals(listOf(12L), fixture.authorizationRevisions)
        assertEquals(listOf(fixture.submission), fixture.submitted)
    }

    @Test fun ordinaryChatAndFullAgentRemainDistinctFromAutomaticAtLargeFont() {
        val saved = CopyOnWriteArrayList<AgentSessionSettings>()
        rule.setContent {
            FixtureTheme {
                AgentModeContent(
                    settings = AgentSessionSettings(enabled = true),
                    model = ModelInfo("synthetic-tools", capabilities = ModelCapabilities(
                        type = "chat", supports = ModelSupports(toolCalls = true)
                    )),
                    providerDisclosure = "受控说明",
                    publicWebReady = false,
                    saving = false,
                    error = null,
                    onSave = { settings, _ -> saved += settings },
                    onOpenTools = {},
                    onBack = {}
                )
            }
        }
        rule.onNodeWithTag("agent-mode-choice").assertIsSelected()
        rule.onNodeWithTag("agent-mode-automatic").assertIsNotSelected()
        rule.onNodeWithTag("agent-mode-chat").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("agent-mode-save").performScrollTo().performClick()
        assertEquals(1, saved.size)
        assertFalse(saved.single().enabled)
        assertFalse(saved.single().automaticWebSearch)
    }

    @Test fun automaticModeDoesNotBlockStaticQuestionsOnATextOnlyModelWithoutTools() {
        val sent = AtomicInteger()
        val model = ModelInfo("synthetic-text-model", capabilities = ModelCapabilities(
            type = "chat", supports = ModelSupports(toolCalls = false)
        ))
        rule.setContent {
            FixtureTheme {
                FixtureChat(
                    ModelCatalogState(models = listOf(model)), ComposerDraft("解释二分查找"),
                    onSend = { sent.incrementAndGet() }
                )
            }
        }
        rule.onNodeWithTag(ChatTags.SEND).assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, sent.get())
    }

    @Test fun loadingOrStaleCatalogBlocksSendWithoutDiscardingTheDraft() {
        val model = ModelInfo("synthetic-text-model")
        val catalog = mutableStateOf(ModelCatalogState(models = listOf(model), loading = true))
        val draft = ComposerDraft("保留尚未发送的普通问题")
        val sent = AtomicInteger()
        rule.setContent {
            FixtureTheme {
                FixtureChat(catalog.value, draft, onSend = { sent.incrementAndGet() })
            }
        }
        rule.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()
        rule.runOnIdle { catalog.value = catalog.value.copy(loading = false, isStale = true) }
        rule.onNodeWithText(text(R.string.model_refresh_before_send)).assertIsDisplayed()
        rule.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()
        rule.onNodeWithText(draft.text).assertIsDisplayed()
        assertEquals(0, sent.get())
        rule.runOnIdle { catalog.value = catalog.value.copy(isStale = false) }
        rule.onNodeWithTag(ChatTags.SEND).assertIsEnabled().performClick()
        assertEquals(1, sent.get())
    }

    @Test fun onlyTheActiveAutomaticSearchRequestRequiresToolCapability() {
        val model = ModelInfo("synthetic-no-tools")
        val needsSearch = mutableStateOf(true)
        val sent = AtomicInteger()
        rule.setContent {
            FixtureTheme {
                FixtureChat(
                    ModelCatalogState(models = listOf(model)), ComposerDraft("受控问题"),
                    onSend = { sent.incrementAndGet() },
                    automaticSearchNeeded = needsSearch.value
                )
            }
        }
        rule.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()
        rule.onNodeWithText(requireNotNull(model.unavailableReason(needsTools = true))).assertIsDisplayed()
        rule.runOnIdle { needsSearch.value = false }
        rule.onNodeWithTag(ChatTags.SEND).assertIsEnabled().performClick()
        assertEquals(1, sent.get())
    }

    @Test fun imageHistoryIsIncludedInModelRequirementsAndUnavailableChoicesStayVisible() {
        val model = ModelInfo("synthetic-no-vision")
        val session = Session(
            "synthetic-session", "受控图片历史", model.id,
            messages = mutableListOf(UiMessage(
                "synthetic-user", "user", "受控历史图片", imageUrls = listOf("https://fixture.invalid/image")
            ))
        )
        val reason = requireNotNull(model.unavailableReason(needsVision = true))
        rule.setContent {
            FixtureTheme {
                FixtureChat(
                    ModelCatalogState(models = listOf(model)), ComposerDraft("图片中的细节是什么？"),
                    onSend = { error("A text-only model cannot submit the image history") }, session = session
                )
            }
        }
        rule.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()
        rule.onNodeWithTag("model_picker").performClick()
        rule.onNode(hasAnyAncestor(isDialog()) and hasText(reason)).performScrollTo().assertIsDisplayed()
    }

    @Composable
    private fun FixtureChat(
        catalog: ModelCatalogState,
        draft: ComposerDraft,
        onSend: () -> Unit,
        session: Session = Session("synthetic-session", "受控普通问题", catalog.models.first().id),
        automaticSearchNeeded: Boolean = false
    ) {
        ChatContent(
            session = session,
            catalog = catalog,
            draft = DraftUiState(draft = draft, loading = false),
            sending = false,
            error = null,
            snackbar = remember { SnackbarHostState() },
            onBack = {},
            onModelSelect = {},
            onRefreshModels = {},
            onTextChange = {},
            onSend = onSend,
            onStop = {},
            onPickText = {},
            onPickImages = {},
            onVoice = {},
            onRemoveAttachment = {},
            attachmentFile = { File(rule.activity.filesDir, "unused-synthetic-attachment") },
            onRetryDraft = {},
            onEdit = {},
            onDelete = {},
            onRegenerate = {},
            onRetry = {},
            onShare = {},
            automaticSearchNeeded = automaticSearchNeeded
        )
    }

    private fun confirm() {
        rule.onNodeWithTag(AutomaticSearchTags.CONFIRM).performScrollTo().assertIsEnabled().performClick()
    }

    private fun show(fixture: Fixture) {
        rule.setContent {
            FixtureTheme {
                if (fixture.visible.value) {
                    AutomaticSearchConsentDialog(
                        submission = fixture.submission,
                        toolSettings = fixture.tools.value,
                        isSubmissionCurrent = {
                            it.matches(fixture.sessionId.value, fixture.draft.value, fixture.agentSettings.value)
                        },
                        onAuthorize = { revision ->
                            fixture.authorizationRevisions += revision
                            fixture.gate?.await() ?: fixture.result.value
                        },
                        onAuthorized = {
                            fixture.submitted += it
                            fixture.draft.value = fixture.draft.value.clearedIfAccepted(it.draft)
                        },
                        onOpenTools = { fixture.openedTools.incrementAndGet() },
                        onDismiss = { fixture.visible.value = false }
                    )
                }
            }
        }
        rule.onNodeWithTag(AutomaticSearchTags.DIALOG).assertIsDisplayed()
    }

    @Composable
    private fun FixtureTheme(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
            CopilotGoTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun text(resource: Int) = rule.activity.getString(resource)

    private class Fixture {
        val submission = AutomaticSearchSubmission(
            "synthetic-session",
            ComposerDraft(
                "北京今天的天气",
                attachments = listOf(AttachmentRef("synthetic-attachment", "保留附件.txt", "text/plain", 8, AttachmentKind.TEXT)),
                revision = 7,
                submissionId = "original-submission"
            ),
            AgentSessionSettings()
        )
        val draft = mutableStateOf(submission.draft)
        val agentSettings = mutableStateOf(submission.agentSettings)
        val sessionId = mutableStateOf<String?>(submission.sessionId)
        val tools = mutableStateOf(ToolSettingsState(
            loading = false,
            snapshot = ToolSettingsSnapshot(WebToolSettings(revision = 9))
        ))
        val visible = mutableStateOf(true)
        val result = mutableStateOf<OperationResult>(OperationResult.Accepted)
        var gate: CompletableDeferred<OperationResult>? = null
        val authorizationRevisions = CopyOnWriteArrayList<Long>()
        val submitted = CopyOnWriteArrayList<AutomaticSearchSubmission>()
        val openedTools = AtomicInteger()
    }
}
