package com.tongxie.copilotgo.data.agent

import com.tongxie.copilotgo.data.chat.ChatStreamCenter
import com.tongxie.copilotgo.data.chat.CoreFixture
import com.tongxie.copilotgo.data.chat.OperationResult
import com.tongxie.copilotgo.data.chat.SendResult
import com.tongxie.copilotgo.data.tools.ToolMemoryVault
import com.tongxie.copilotgo.data.tools.ToolSettingsStore
import com.tongxie.copilotgo.data.tools.WebToolSettingsDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutomaticWebSearchLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun firstUseRequiresConsentBeforeAnyModelRequestOrDurableSend() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            assertTrue(fixture.center.needsAutomaticWebSearch(ID, "今天北京天气如何"))
            val result = fixture.center.submit(ID, "今天北京天气如何", submissionId = "unchanged-draft")
            fixture.idle()
            assertTrue(result is SendResult.Rejected)
            assertTrue(fixture.session().messages.isEmpty())
            assertTrue(fixture.core.requests.isEmpty())
            assertTrue(fixture.model.requests.isEmpty())
            assertTrue(fixture.tools.invocations.isEmpty())
        }
    }

    @Test
    fun acceptingConsentPreservesOtherSettingsAndRejectsStaleApproval() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            val web = fixture.settings.awaitReady().web
            assertTrue(fixture.center.authorizeAutomaticWebSearch(web.revision + 1) is OperationResult.Rejected)
            assertFalse(fixture.settings.awaitReady().web.automaticSearchConsent)
            assertEquals(OperationResult.Accepted, fixture.center.authorizeAutomaticWebSearch(web.revision))
            val accepted = fixture.settings.awaitReady().web
            assertTrue(accepted.searchEnabled && accepted.externalSharingConsent && accepted.automaticSearchConsent)
            assertEquals(web.pageReaderEnabled, accepted.pageReaderEnabled)
            assertEquals(web.provider, accepted.provider)
            assertEquals(web.credentialState, accepted.credentialState)
            assertTrue(fixture.core.requests.isEmpty())
        }
    }

    @Test
    fun weatherAndStockQuestionsAutomaticallyRunSearchWithoutChangingTheSavedMode() = runBlocking {
        for (question in listOf("今天北京天气如何", "今天微软股价多少")) {
            Fixture(temporary.newFolder()).use { fixture ->
                fixture.create(consented = true)
                fixture.model.enqueue(proposal())
                fixture.model.answer("Actual current-information fixture [S1].")
                assertTrue(fixture.center.submit(ID, question) is SendResult.Accepted)
                fixture.idle()
                val session = fixture.session()
                assertFalse(session.agentSettings.enabled)
                assertTrue(session.agentSettings.automaticWebSearch)
                val run = session.messages.last().agentRun!!
                assertEquals(AgentRunStatus.COMPLETED, run.status)
                assertNull(run.pendingApproval)
                assertEquals(1, fixture.tools.invocations.size)
                assertEquals("required", fixture.model.requests.first().toolChoice)
                assertEquals("S1", run.sources.single().id)
                assertTrue(session.messages.last().content.contains("[S1]"))
            }
        }
    }

    @Test
    fun ordinaryQuestionsRemainToolFreeAndReceiveTruthfulCapabilityContext() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.core.enqueueText("Kotlin is a programming language.")
            assertFalse(fixture.center.needsAutomaticWebSearch(ID, "Kotlin 是什么"))
            assertTrue(fixture.center.submit(ID, "Kotlin 是什么") is SendResult.Accepted)
            fixture.idle()
            assertNull(fixture.session().messages.last().agentRun)
            assertTrue(fixture.tools.invocations.isEmpty())
            val body = fixture.core.requests.single { it.path == "/chat/completions" }.body.readUtf8()
            assertFalse(body.contains("\"tools\":"))
            assertTrue(body.contains("not a local model"))
            assertTrue(fixture.session().messages.none { it.role == "system" })
        }
    }

    @Test
    fun explicitlyChoosingOrdinaryChatDoesNotSearchEvenForWeather() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create()
            fixture.core.store.update(ID) {
                it.copy(agentSettings = it.agentSettings.copy(automaticWebSearch = false))
            }
            fixture.core.enqueueText("Web tools are disabled for this request.")
            assertFalse(fixture.center.needsAutomaticWebSearch(ID, "今天北京天气如何"))
            assertTrue(fixture.center.submit(ID, "今天北京天气如何") is SendResult.Accepted)
            fixture.idle()
            assertNull(fixture.session().messages.last().agentRun)
            assertTrue(fixture.tools.invocations.isEmpty())
        }
    }

    @Test
    fun revokingConsentWhilePreparingCannotAdmitOrRunAStaleAutomaticRequest() = runBlocking {
        Fixture(temporary.root).use { fixture ->
            fixture.create(consented = true)
            fixture.tools.snapshotAction = {
                val web = fixture.settings.awaitReady().web
                fixture.settings.updateWeb(
                    WebToolSettingsDraft(web).copy(automaticSearchConsent = false), web.revision
                )
                fixture.alignToolRevision()
                AgentToolSnapshot(fixture.tools.revision.value, listOf(fixture.tools.descriptor))
            }
            assertTrue(fixture.center.submit(ID, "今天微软股价多少") is SendResult.Rejected)
            fixture.idle()
            assertTrue(fixture.session().messages.isEmpty())
            assertTrue(fixture.model.requests.isEmpty())
            assertTrue(fixture.tools.invocations.isEmpty())
        }
    }

    private class Fixture(root: File) : AutoCloseable {
        val core = CoreFixture(root).also { it.center.close() }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = ToolSettingsStore(ToolMemoryVault(), scope)
        val model = AgentTestModel()
        val tools = AgentTestExecutor(AgentToolKind.PUBLIC_WEB_SEARCH)
        val center = ChatStreamCenter(
            core.store, core.client, core.catalog, scope,
            agentRunner = AgentEngine(model, tools, AgentPromptBuilder(core.store.attachments)),
            toolSettings = settings
        )

        suspend fun create(consented: Boolean = false) {
            core.create(ID)
            val web = settings.awaitReady().web
            if (consented) {
                check(center.authorizeAutomaticWebSearch(web.revision) == OperationResult.Accepted)
            }
            alignToolRevision()
        }

        suspend fun alignToolRevision() {
            val revision = settings.awaitReady().web.revision
            tools.revision.value = revision
            tools.descriptor = tools.descriptor.copy(identity = tools.descriptor.identity.copy(configRevision = revision))
        }

        suspend fun idle() = withTimeout(5_000) { center.sendingFlow(ID).first { !it } }
        suspend fun session() = requireNotNull(core.store.getSession(ID))

        override fun close() {
            center.close()
            scope.cancel()
            core.close()
        }
    }

    companion object {
        private const val ID = "automatic-web-fixture"
    }
}
