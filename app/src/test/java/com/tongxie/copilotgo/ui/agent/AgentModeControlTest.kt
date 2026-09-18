package com.tongxie.copilotgo.ui.agent

import com.tongxie.copilotgo.data.agent.AgentLimits
import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeControlTest {
    @Test fun newAndLegacySessionsSelectAutomaticRatherThanFullAgent() {
        val fresh = AgentSessionSettings()
        val legacy = Json.decodeFromString<AgentSessionSettings>("""{"enabled":false}""")
        assertEquals(ChatMode.AUTOMATIC, fresh.chatMode())
        assertEquals(ChatMode.AUTOMATIC, legacy.chatMode())
        assertFalse(fresh.enabled)
        assertFalse(fresh.autoApprovePublicWebReads)
    }

    @Test fun explicitFullAgentAlwaysTakesPrecedenceOverTheAutomaticFlag() {
        for (automatic in listOf(false, true)) {
            assertEquals(
                ChatMode.AGENT,
                AgentSessionSettings(enabled = true, automaticWebSearch = automatic).chatMode()
            )
        }
    }

    @Test fun chatDisablesBothRoutingModesWithoutRewritingManualPermissionsOrLimits() {
        val settings = AgentSessionSettings(
            enabled = true,
            autoApprovePublicWebReads = true,
            limits = AgentLimits(maxSteps = 3)
        )
        val chat = settings.withChatMode(ChatMode.CHAT)
        assertFalse(chat.enabled)
        assertFalse(chat.automaticWebSearch)
        assertTrue(chat.autoApprovePublicWebReads)
        assertEquals(settings.limits, chat.limits)
        assertEquals(ChatMode.CHAT, chat.chatMode())
    }

    @Test fun selectingAutomaticDoesNotGrantManualWebApproval() {
        val ordinary = AgentSessionSettings(enabled = false, automaticWebSearch = false)
        val automatic = ordinary.withChatMode(ChatMode.AUTOMATIC)
        assertTrue(automatic.automaticWebSearch)
        assertFalse(automatic.enabled)
        assertFalse(automatic.autoApprovePublicWebReads)
        val agent = automatic.withChatMode(ChatMode.AGENT)
        assertTrue(agent.enabled)
        assertEquals(ChatMode.AGENT, agent.chatMode())
    }
}
