package com.tongxie.copilotgo.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDisplayTextTest {
    @Test fun previewsAreBoundedWithoutSplittingAnEmoji() {
        val text = "123\uD83D\uDE80tail"
        assertEquals(AgentTextPreview("123", true), agentTextPreview(text, 4))
        assertEquals(AgentTextPreview("123\uD83D\uDE80", true), agentTextPreview(text, 5))
        assertEquals(text, agentTextPreview(text, 64).text)
        assertFalse(agentTextPreview(text, 64).truncated)
    }

    @Test fun invisibleDirectionsCannotSpoofAToolDestination() {
        assertEquals("host\\u202eevil\\u0000", agentTextPreview("host\u202eevil\u0000", 64).text)
        val preview = agentTextPreview("abcd\u202etail", 8)
        assertEquals("abcd", preview.text)
        assertTrue(preview.truncated)
        assertTrue(agentTextPreview("a".repeat(100_000), 2_048).text.length <= 2_048)
    }

    @Test fun onlyExplicitSafeHttpsSourceLinksCanOpen() {
        val url = "https://developer.android.com/develop/ui/compose?source=fixture#overview"
        assertEquals(url, agentSourceDestination(url))
        assertNull(agentSourceDestination("javascript:alert(1)"))
        assertNull(agentSourceDestination("file:///data/local/file"))
        assertNull(agentSourceDestination("https://user:secret@example.com/result"))
        assertNull(agentSourceDestination("http://example.com/result"))
        assertNull(agentSourceDestination("https://example.com/\nresult"))
    }
}
