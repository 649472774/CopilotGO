package com.tongxie.copilotgo.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.data.agent.AgentRunRecord
import com.tongxie.copilotgo.data.agent.AgentRunStatus
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.ui.components.MessageBubble
import com.tongxie.copilotgo.ui.theme.CopilotGoTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageActionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun ordinaryUserCanCopyEditExportAndRequestDeletion() = verifyActions("user")

    @Test fun ordinaryAssistantCanCopyRegenerateExportAndRequestDeletion() = verifyActions("assistant")

    @Test fun sourceFreeAgentReplyKeepsAllMessageActions() = verifyActions(
        "assistant", AgentRunRecord("source-free", 1, AgentRunStatus.COMPLETED)
    )

    @Test fun busyAndProtectedRepliesKeepCopyAndExportButBlockMutations() {
        val busy = mutableStateOf(true)
        val protected = mutableStateOf(false)
        val mutations = AtomicInteger()
        val exports = AtomicInteger()
        val clipboard = MemoryClipboard()
        val message = UiMessage("protected-message", "assistant", "Controlled preserved response.")
        rule.setContent {
            CopilotGoTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Surface {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            MessageBubble(
                                message,
                                actionsEnabled = !busy.value,
                                replayBlocked = protected.value,
                                onRegenerate = { mutations.incrementAndGet() },
                                onDelete = { mutations.incrementAndGet() },
                                onShare = { exports.incrementAndGet() }
                            )
                        }
                    }
                }
            }
        }
        val author = text(R.string.message_author_assistant)
        rule.onNodeWithContentDescription(text(R.string.message_copy, author)).performScrollTo().performClick()
        assertEquals(message.content, clipboard.value?.text)
        rule.onNodeWithContentDescription(text(R.string.message_actions, author)).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.message_regenerate)).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.action_delete)).performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { busy.value = false; protected.value = true }
        rule.onNodeWithText(text(R.string.message_regenerate)).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.action_delete)).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.message_share)).performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(0, mutations.get()); assertEquals(1, exports.get()) }
    }

    private fun verifyActions(role: String, run: AgentRunRecord? = null) {
        val isUser = role == "user"
        val clipboard = MemoryClipboard()
        val edited = AtomicInteger()
        val deleted = AtomicInteger()
        val shared = AtomicInteger()
        val message = UiMessage("plain-$role", role, "Controlled native message.", agentRun = run)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalClipboardManager provides clipboard
            ) {
                CopilotGoTheme(darkTheme = !isUser) {
                    Surface {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            MessageBubble(
                                message,
                                onEdit = if (isUser) ({ edited.incrementAndGet(); Unit }) else null,
                                onRegenerate = if (!isUser) ({ edited.incrementAndGet(); Unit }) else null,
                                onDelete = { deleted.incrementAndGet() },
                                onShare = { shared.incrementAndGet() }
                            )
                        }
                    }
                }
            }
        }
        val author = text(if (isUser) R.string.message_author_user else R.string.message_author_assistant)
        val copy = text(R.string.message_copy, author)
        val actions = text(R.string.message_actions, author)
        val edit = text(if (isUser) R.string.message_edit else R.string.message_regenerate)
        rule.onNodeWithContentDescription(copy).performScrollTo().assertIsDisplayed().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(message.content, clipboard.value?.text) }
        rule.onNodeWithContentDescription(actions).performScrollTo().assertIsDisplayed().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        listOf(text(R.string.message_share), edit, text(R.string.action_delete)).forEach { label ->
            rule.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertIsEnabled()
        }
        Espresso.pressBack()
        rule.onNodeWithContentDescription(actions).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.message_share)).performScrollTo().performClick()
        rule.onNodeWithContentDescription(actions).performScrollTo().performClick()
        rule.onNodeWithText(edit).performScrollTo().performClick()
        rule.onNodeWithContentDescription(actions).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.action_delete)).performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, edited.get())
            assertEquals(1, deleted.get())
            assertEquals(1, shared.get())
        }
    }

    private fun text(id: Int, vararg arguments: Any): String = rule.activity.getString(id, *arguments)

    private class MemoryClipboard : ClipboardManager {
        var value: AnnotatedString? = null
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
        override fun getText(): AnnotatedString? = value
    }
}
