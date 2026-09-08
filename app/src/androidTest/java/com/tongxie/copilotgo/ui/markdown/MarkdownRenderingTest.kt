package com.tongxie.copilotgo.ui.markdown

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager as SystemClipboardManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
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
import androidx.test.platform.app.InstrumentationRegistry
import com.tongxie.copilotgo.ui.components.SimpleMarkdownText
import com.tongxie.copilotgo.ui.saveNativeScreenshotEvidence
import java.io.File
import org.junit.Assert.assertEquals
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
            .assertIsOn().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick().assertIsOff()
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
        val source = "可以选择并复制这段文字"
        val sentinel = "controlled-clipboard-before-selection"
        val clipboard = requireNotNull(rule.activity.getSystemService(SystemClipboardManager::class.java))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val serviceInfo = automation.serviceInfo
        val originalFlags = serviceInfo.flags
        serviceInfo.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = serviceInfo
        try {
            rule.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("fixture", sentinel)) }
            rule.setContent {
                MaterialTheme { Surface { SimpleMarkdownText(source) } }
            }
            waitForText(source)
            rule.onNodeWithText(source).performTouchInput { longClick(center) }
            val copyLabel = rule.activity.getString(android.R.string.copy)
            rule.waitUntil(5_000) { nativeCopyAction(automation, copyLabel, click = false) }
            val directory = File(rule.activity.getExternalFilesDir(null), "ui-acceptance")
            assertTrue(directory.isDirectory || directory.mkdirs())
            saveNativeScreenshotEvidence(rule.activity, directory, "markdown-selection-menu") {
                rule.onNodeWithText(source).captureToImage().asAndroidBitmap()
            }
            assertTrue("The real native Copy action must be clickable", nativeCopyAction(automation, copyLabel, click = true))
            var copied: String? = null
            rule.waitUntil(5_000) {
                copied = rule.runOnUiThread { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
                copied?.let { it.isNotEmpty() && it != sentinel } == true
            }
            assertTrue("Clipboard must contain text actually selected from the fixture", source.contains(requireNotNull(copied)))
        } finally {
            serviceInfo.flags = originalFlags
            automation.serviceInfo = serviceInfo
            rule.runOnUiThread { clipboard.clearPrimaryClip() }
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

    private fun nativeCopyAction(automation: UiAutomation, label: String, click: Boolean): Boolean {
        val windows = automation.windows
        try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                try {
                    if (nativeCopyNode(root, null, label, click)) return true
                } finally {
                    root.recycle()
                }
            }
            return false
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun nativeCopyNode(
        node: AccessibilityNodeInfo,
        clickableParent: AccessibilityNodeInfo?,
        label: String,
        click: Boolean
    ): Boolean {
        val target = if (node.isClickable && node.isEnabled) node else clickableParent
        if (node.isVisibleToUser && node.text?.toString()?.equals(label, ignoreCase = true) == true &&
            target != null && target.isVisibleToUser
        ) {
            return !click || target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (nativeCopyNode(child, target, label, click)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }
}
