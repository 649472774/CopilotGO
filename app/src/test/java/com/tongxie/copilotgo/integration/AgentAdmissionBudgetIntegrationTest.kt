package com.tongxie.copilotgo.integration

import com.tongxie.copilotgo.data.agent.AgentContextLimitException
import com.tongxie.copilotgo.data.agent.AgentEngine
import com.tongxie.copilotgo.data.agent.AgentLimits
import com.tongxie.copilotgo.data.agent.AgentModelTransport
import com.tongxie.copilotgo.data.agent.AgentPromptBuilder
import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import com.tongxie.copilotgo.data.agent.AgentStreamEvent
import com.tongxie.copilotgo.data.agent.AgentToolDescriptor
import com.tongxie.copilotgo.data.agent.AgentToolExecutor
import com.tongxie.copilotgo.data.agent.AgentToolIdentity
import com.tongxie.copilotgo.data.agent.AgentToolInvocation
import com.tongxie.copilotgo.data.agent.AgentToolResult
import com.tongxie.copilotgo.data.agent.AgentToolSnapshot
import com.tongxie.copilotgo.data.agent.AgentToolValidation
import com.tongxie.copilotgo.data.chat.ChatStreamCenter
import com.tongxie.copilotgo.data.chat.CoreFixture
import com.tongxie.copilotgo.data.chat.SendResult
import com.tongxie.copilotgo.data.chat.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class AgentAdmissionBudgetIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun imageThatFitsWithoutToolsIsRejectedBeforeAdmissionWhenActualDefinitionsOverflow() = runBlocking {
        CoreFixture(temporary.root).use { core ->
            core.center.close()
            core.create()
            val settings = AgentSessionSettings(enabled = true)
            core.store.update("fixture-session") { it.copy(agentSettings = settings) }
            val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            val random = Random(472774)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, random.nextInt())
            val bytes = ByteArrayOutputStream().use { output ->
                assertTrue(ImageIO.write(image, "png", output))
                output.toByteArray()
            }
            val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"
            val attachment = core.store.attachments.importDataUri(dataUri)
            val model = core.catalog.requireModel("fixture-chat", needsVision = true, needsTools = true)
            val tools = (0..2).map { index ->
                AgentToolDescriptor(
                    AgentToolIdentity("fixture-config", 0, "fixture-$index", "definition-$index"),
                    "fixture_$index", "d".repeat(4000),
                    Json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false}""").jsonObject,
                    "https://example.com/controlled"
                )
            }
            val history = listOf(UiMessage("user", "user", "Keep this image", attachments = listOf(attachment)))
            val prompt = AgentPromptBuilder(core.store.attachments)
            prompt.prepare(history, model, emptyList(), limits = AgentLimits())
            var actualPromptRejected = false
            try {
                prompt.prepare(history, model, tools.map { it.modelDefinition() }, limits = AgentLimits())
            } catch (_: AgentContextLimitException) {
                actualPromptRejected = true
            }
            assertTrue("The exact fixture must fit empty definitions but exceed the real 96KiB wire budget", actualPromptRejected)
            val modelCalls = AtomicInteger()
            val job = SupervisorJob()
            val executor = object : AgentToolExecutor {
                override val revision = MutableStateFlow(0L)
                override suspend fun snapshot() = AgentToolSnapshot(0, tools)
                override fun isCurrent(identity: AgentToolIdentity) = tools.any { it.identity == identity }
                override suspend fun validate(invocation: AgentToolInvocation): AgentToolValidation =
                    throw AssertionError("Over-budget admission must not reach proposal validation")
                override suspend fun execute(invocation: AgentToolInvocation): AgentToolResult =
                    throw AssertionError("Over-budget admission must not run an external tool")
            }
            val center = ChatStreamCenter(
                core.store, core.client, core.catalog, CoroutineScope(job + Dispatchers.Default),
                agentRunner = AgentEngine(
                    AgentModelTransport {
                        modelCalls.incrementAndGet()
                        flowOf(AgentStreamEvent.TextDelta("Unexpected request"), AgentStreamEvent.Completed("stop"))
                    },
                    executor, prompt, core.json
                )
            )
            try {
                val result = center.submit(
                    "fixture-session", "Keep this image", attachmentRefs = listOf(attachment),
                    submissionId = "over-budget-draft", agentSettings = settings
                )
                withTimeout(5000) { center.sendingFlow("fixture-session").first { !it } }
                assertEquals(0, modelCalls.get())
                assertTrue("An untransmittable first request must reject before Accepted can clear the draft", result is SendResult.Rejected)
                assertTrue(core.store.getSession("fixture-session")!!.messages.isEmpty())
                assertEquals(dataUri, core.store.attachments.imageDataUri(attachment))
                assertEquals(settings, core.store.getSession("fixture-session")!!.agentSettings)
            } finally {
                center.close()
                job.cancelAndJoin()
            }
        }
    }
}
