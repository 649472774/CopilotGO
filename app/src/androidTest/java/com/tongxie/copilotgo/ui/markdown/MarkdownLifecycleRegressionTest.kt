package com.tongxie.copilotgo.ui.markdown

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tongxie.copilotgo.ui.components.SimpleMarkdownText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded reproduction of document replacement and normal Activity-owned composition disposal. */
@RunWith(AndroidJUnit4::class)
class MarkdownLifecycleRegressionTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test
    fun replacedDocumentSurvivesFiveRecreationsAndFinalDisposal() {
        val source = mutableStateOf(table(0))
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            installContent(scenario, source)
            repeat(5) { cycle ->
                rule.runOnIdle { source.value = table(cycle) }
                waitForTag("markdown-table")
                rule.onNodeWithTag("markdown-table").performScrollTo().assertIsDisplayed()
                println("Markdown lifecycle cycle=$cycle phase=table-visible")

                val marker = "Lifecycle final $cycle"
                rule.runOnIdle {
                    source.value = "# $marker\n\n" + (0 until 499).joinToString("\n") { "# Row $cycle-$it" }
                }
                assertReplacementVisible(marker)
                println("Markdown lifecycle cycle=$cycle phase=replacement-visible")

                scenario.recreate()
                installContent(scenario, source)
                assertReplacementVisible(marker)
                println("Markdown lifecycle cycle=$cycle phase=recreated-visible")
            }

            scenario.moveToState(Lifecycle.State.DESTROYED)
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            println("Markdown lifecycle phase=final-destroyed")
        } finally {
            scenario.close()
        }
    }

    private fun assertReplacementVisible(marker: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(marker).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(marker).performScrollTo().assertIsDisplayed()
        waitForTag("markdown-preview-notice")
        rule.onNodeWithTag("markdown-preview-notice").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithTag("markdown-table").assertCountEquals(0)
    }

    private fun waitForTag(tag: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun installContent(scenario: ActivityScenario<ComponentActivity>, source: MutableState<String>) {
        scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            activity.setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                                .verticalScroll(rememberScrollState()).padding(16.dp)
                        ) {
                            SimpleMarkdownText(source.value)
                        }
                    }
                }
            }
        }
    }

    private fun table(cycle: Int): String =
        "| A | B |\n| --- | --- |\n| Controlled $cycle | Value |\n"
}
