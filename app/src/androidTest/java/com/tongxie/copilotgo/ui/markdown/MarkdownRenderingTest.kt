package com.tongxie.copilotgo.ui.markdown

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tongxie.copilotgo.ui.components.SimpleMarkdownText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic content only; no account, conversation, model or network capability is required. */
@RunWith(AndroidJUnit4::class)
class MarkdownRenderingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun codeActionsWrapAndRemain48DpAtTwoHundredPercent() {
        val clipboard = TestClipboard()
        val feedback = ArrayList<String>()
        rule.setContent {
            Fixture(clipboard, fontScale = 2f) {
                Box(Modifier.width(280.dp)) {
                    SimpleMarkdownText(
                        "```一个用于验证较长语言名称自动折行的示例\nval answer = 42\n```",
                        onFeedback = { feedback += it }
                    )
                }
            }
        }
        waitForTag("markdown-code-copy")
        rule.onNodeWithTag("markdown-code-copy")
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("markdown-code-wrap")
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        rule.runOnIdle {
            assertEquals("val answer = 42\n", clipboard.value?.text)
            assertEquals(listOf("已复制代码"), feedback)
        }
        rule.onNodeWithText("横向阅读；超长行仍会折行。").assertIsDisplayed()
    }

    @Test
    fun safeLinkClicksUseNativeHandlerAndFailuresUseFeedback() {
        val attempts = ArrayList<String>()
        val feedback = ArrayList<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                attempts += uri
                throw IllegalArgumentException("Synthetic unavailable handler")
            }
        }
        rule.setContent {
            Fixture(TestClipboard()) {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    SimpleMarkdownText("[打开来源](https://example.test/source)", onFeedback = { feedback += it })
                }
            }
        }
        waitForText("打开来源")
        rule.onNodeWithText("打开来源").performTouchInput { click(center) }
        rule.runOnIdle {
            assertEquals(listOf("https://example.test/source"), attempts)
            assertEquals(listOf("暂时无法打开链接，请复制链接后重试。"), feedback)
        }
    }

    @Test
    fun longPressReallySelectsAndCopiesText() {
        val clipboard = TestClipboard()
        val toolbar = TestToolbar()
        rule.setContent {
            Fixture(clipboard) {
                CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                    SimpleMarkdownText("可以选择并复制这段文字")
                }
            }
        }
        waitForText("可以选择并复制这段文字")
        rule.onNodeWithText("可以选择并复制这段文字").performTouchInput { longClick(center) }
        rule.runOnIdle {
            assertNotNull(toolbar.copy)
            toolbar.copy?.invoke()
            assertTrue(clipboard.value?.text?.isNotEmpty() == true)
        }
    }

    @Test
    fun finalStreamingUpdateIsNeverLost() {
        val source = mutableStateOf("开")
        val streaming = mutableStateOf(true)
        rule.setContent {
            Fixture(TestClipboard()) {
                SimpleMarkdownText(source.value, isStreaming = streaming.value)
            }
        }
        rule.runOnIdle {
            repeat(300) { source.value = "# 标题\n\n流式片段 $it" }
            source.value = "# 标题\n\n末尾完成"
            streaming.value = false
        }
        waitForText("末尾完成")
        rule.onNodeWithText("末尾完成").assertIsDisplayed()
    }

    @Test
    fun tableAndOversizedSourceExposeTheirReadingState() {
        val source = mutableStateOf("| A | B |\n| --- | --- |\n| 中文 | 值 |\n")
        rule.setContent {
            Fixture(TestClipboard()) { SimpleMarkdownText(source.value) }
        }
        waitForTag("markdown-table")
        rule.onNodeWithText("表格 · 可横向滚动").assertIsDisplayed()
        rule.runOnIdle { source.value = "# 行\n".repeat(500) }
        waitForTag("markdown-preview-notice")
    }

    private fun waitForTag(tag: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Composable
    private fun Fixture(
        clipboard: TestClipboard,
        fontScale: Float = 1f,
        content: @Composable () -> Unit
    ) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            LocalClipboardManager provides clipboard
        ) {
            MaterialTheme { Surface { content() } }
        }
    }

    private class TestClipboard : ClipboardManager {
        var value: AnnotatedString? = null
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
        override fun getText(): AnnotatedString? = value
    }

    private class TestToolbar : TextToolbar {
        var copy: (() -> Unit)? = null
        override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?
        ) {
            copy = onCopyRequested
            status = TextToolbarStatus.Shown
        }
        override fun hide() { status = TextToolbarStatus.Hidden }
    }
}
