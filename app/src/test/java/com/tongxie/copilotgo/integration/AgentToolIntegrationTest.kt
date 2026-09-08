package com.tongxie.copilotgo.integration

import com.tongxie.copilotgo.data.chat.CoreFixture
import com.tongxie.copilotgo.data.chat.Session
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun modernJsonMcpRunsApprovedCallAndReturnsCitedContinuation() = runBlocking {
        AgentToolIntegrationScenario().runConversation(fixture(), legacySse = false)
    }

    @Test
    fun legacySessionSseMcpRunsApprovedCallWithoutReplayingDeniedCall() = runBlocking {
        AgentToolIntegrationScenario().runConversation(fixture(), legacySse = true)
    }

    @Test
    fun dispatchedRedirectPreservesUnknownOutcomeAndBlocksReplay() = runBlocking {
        AgentToolIntegrationScenario().runConversation(fixture(), legacySse = false, failAfterDispatch = true)
    }

    private fun fixture(): AgentIntegrationModelFixture {
        val core = CoreFixture(temporary.newFolder())
        core.center.close()
        return object : AgentIntegrationModelFixture {
            override val json get() = core.json
            override val client get() = core.client
            override val catalog get() = core.catalog
            override val paths get() = core.paths
            override val store get() = core.store
            override val requests get() = core.requests
            override val replies get() = core.replies
            override suspend fun create(id: String): Session = core.create(id)
            override fun enqueueText(text: String) = core.enqueueText(text)
            override fun close() = core.close()
        }
    }
}
