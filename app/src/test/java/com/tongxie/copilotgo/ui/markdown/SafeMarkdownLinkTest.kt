package com.tongxie.copilotgo.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SafeMarkdownLinkTest {
    @Test
    fun permitsOnlyUsableWebAndMailLinks() {
        listOf(
            "https://example.test/path?q=1#section",
            "http://localhost:8080/path",
            "HTTPS://example.test",
            "mailto:reader@example.test?subject=hello%20world"
        ).forEach { assertNotNull(it, SafeMarkdownLink.destination(it)) }
    }

    @Test
    fun rejectsUnsafeMalformedAndRelativeDestinations() {
        listOf(
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "data:text/html,example",
            "file:///notes.txt",
            "intent://example.test",
            "content://example.test",
            "//example.test",
            "/relative",
            "https://",
            "https:///example.test",
            "https://user@example.test",
            "https://example.test:99999",
            "https://example.test\\@another.test",
            "https://example.test/\n",
            "https://example.test/%0aexample",
            "mailto:reader@example.test?subject=%0d%0aInjected",
            "mailto:",
            "mailto://reader@example.test",
            "https://example.test/" + "a".repeat(SafeMarkdownLink.MAX_CHARACTERS)
        ).forEach { assertNull(it, SafeMarkdownLink.destination(it)) }
    }

    @Test
    fun preservesChinesePathsWithoutChangingTheScheme() {
        assertEquals(
            "https://example.test/%E6%96%87%E6%A1%A3",
            SafeMarkdownLink.destination("https://example.test/文档")
        )
    }
}
