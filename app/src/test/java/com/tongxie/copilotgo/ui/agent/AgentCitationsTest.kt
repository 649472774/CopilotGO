package com.tongxie.copilotgo.ui.agent

import com.tongxie.copilotgo.data.agent.AgentRunRecord
import com.tongxie.copilotgo.data.agent.AgentRunStatus
import com.tongxie.copilotgo.data.agent.SourceKind
import com.tongxie.copilotgo.data.agent.SourceReference
import com.tongxie.copilotgo.data.chat.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentCitationsTest {
    @Test fun citationsUseActualPriorAndCurrentSourcesButNeverFutureMessages() {
        val messages = listOf(
            message("first", SourceReference("https://example.com/first", "First", SourceKind.SEARCH_HIT, "S1")),
            message("second", SourceReference("https://example.com/second", "Second", SourceKind.FETCHED_PAGE, "S2")),
            message("future", SourceReference("https://example.com/future", "Future", SourceKind.FETCHED_PAGE, "S3"))
        )
        assertEquals(
            mapOf("S1" to "https://example.com/first", "S2" to "https://example.com/second"),
            agentCitationLinks(messages, "second")
        )
        assertEquals(emptyMap<String, String>(), agentCitationLinks(messages, "not-a-message"))
    }

    @Test fun ambiguousAndUnsafeIdsAreNotSilentlyAssignedADestination() {
        val messages = listOf(
            message("first", SourceReference("https://example.com/first", "First", SourceKind.SEARCH_HIT, "S1")),
            message("second", SourceReference("https://example.com/second", "Second", SourceKind.SEARCH_HIT, "S1")),
            message("third", SourceReference("file:///data/local", "Not a web page", SourceKind.TOOL_RESOURCE, "S2")),
            message("last", SourceReference("https://example.com/last", "Invalid id", SourceKind.SEARCH_HIT, "[fake]"))
        )
        assertFalse(agentCitationLinks(messages, "last").isNotEmpty())
    }

    private fun message(id: String, source: SourceReference) = UiMessage(
        id, "assistant", "Controlled response",
        agentRun = AgentRunRecord("run-$id", 7, AgentRunStatus.COMPLETED, sources = listOf(source))
    )
}
