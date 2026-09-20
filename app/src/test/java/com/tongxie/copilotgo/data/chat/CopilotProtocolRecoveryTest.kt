package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.agent.AgentApprovalDecision
import com.tongxie.copilotgo.data.agent.AgentApprovalResponse
import com.tongxie.copilotgo.data.agent.AgentEngine
import com.tongxie.copilotgo.data.agent.AgentModelTransport
import com.tongxie.copilotgo.data.agent.AgentPromptBuilder
import com.tongxie.copilotgo.data.agent.AgentRunStatus
import com.tongxie.copilotgo.data.agent.AgentSessionSettings
import com.tongxie.copilotgo.data.agent.AgentTestExecutor
import com.tongxie.copilotgo.data.agent.AgentToolKind
import com.tongxie.copilotgo.data.agent.AgentToolValidation
import com.tongxie.copilotgo.data.agent.AgentValues
import com.tongxie.copilotgo.data.tools.ToolMemoryVault
import com.tongxie.copilotgo.data.tools.ToolSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class CopilotProtocolRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun normalAndAutomaticStaticChatKeepTheSelectedModelAndHistoryOnBothTransports() = runBlocking {
        for (transport in ModelTransport.entries) for (automatic in listOf(false, true)) {
            Fixture(temporary.newFolder(), transport).use { fixture ->
                fixture.create(automatic = automatic)
                for ((index, question) in listOf("解释 Kotlin 数据类", "解释 Kotlin 接口").withIndex()) {
                    fixture.enqueueAnswer("answer-$index", seed = index * 100)
                    assertTrue(fixture.center.submit(ID, question) is SendResult.Accepted)
                    fixture.idle()
                    assertNull(fixture.center.errorFlow(ID).value)
                    assertEquals("answer-$index", fixture.session().messages.last().content)
                    assertNull(fixture.session().messages.last().agentRun)
                }
                assertEquals(fixture.model.id, fixture.session().model)
                assertEquals(4, fixture.session().messages.size)
                assertTrue(fixture.tools.invocations.isEmpty())
                val requests = fixture.bodies()
                assertEquals(2, requests.size)
                requests.forEach {
                    assertEquals(fixture.model.id, it.requiredString("model"))
                    assertFalse(it.containsKey("tools"))
                }
                assertTrue(requests.last().toString().contains("answer-0"))
                assertTrue(requests.last().toString().contains("解释 Kotlin 数据类"))
                assertEquals(setOf("/models", transport.endpoint), fixture.core.requests.map { it.path }.toSet())
            }
        }
    }

    @Test
    fun automaticSearchAndManualAgentExecuteOnceThenReplayExactCallsAndPreserveLaterHistory() = runBlocking {
        for (transport in ModelTransport.entries) for (automatic in listOf(false, true)) {
            Fixture(temporary.newFolder(), transport).use { fixture ->
                fixture.create(automatic = automatic, enabled = !automatic, consented = automatic)
                if (!automatic && transport == ModelTransport.RESPONSES) {
                    fixture.tools.validateAction = {
                        AgentToolValidation(JsonObject(mapOf("query" to JsonPrimitive("[redacted]"))))
                    }
                }
                fixture.enqueueCall()
                fixture.enqueueAnswer("Verified synthetic result [S1].", seed = 100)
                assertTrue(fixture.center.submit(ID, "今天北京天气如何") is SendResult.Accepted)
                if (!automatic) {
                    val pending = withTimeout(5_000) {
                        fixture.center.sessionFlow(ID).first {
                            it?.messages?.lastOrNull()?.agentRun?.let { run ->
                                run.pendingApproval != null || run.status.isTerminal
                            } == true
                        }
                    }!!.messages.last().agentRun!!
                    assertEquals(pending.notice, AgentRunStatus.AWAITING_APPROVAL, pending.status)
                    val binding = requireNotNull(pending.pendingApproval).binding
                    assertEquals(CALL_ID, binding.callId)
                    assertEquals(AgentValues.digest(AgentValues.canonical(
                        Json.parseToJsonElement(ARGUMENTS).jsonObject
                    )), binding.argumentsDigest)
                    assertTrue(fixture.tools.invocations.isEmpty())
                    assertEquals(AgentApprovalResponse.Accepted, fixture.center.respondToApproval(
                        ID, binding, AgentApprovalDecision.APPROVE
                    ))
                    assertTrue(fixture.center.respondToApproval(
                        ID, binding, AgentApprovalDecision.APPROVE
                    ) is AgentApprovalResponse.Rejected)
                }
                fixture.idle()
                assertNull(fixture.center.errorFlow(ID).value)
                val run = requireNotNull(fixture.session().messages.last().agentRun)
                assertEquals(run.notice, AgentRunStatus.COMPLETED, run.status)
                assertEquals(CALL_ID, fixture.tools.invocations.single().callId)
                assertEquals(Json.parseToJsonElement(ARGUMENTS), fixture.tools.invocations.single().arguments)
                assertEquals(CALL_ID, run.sources.single().toolCallId)
                assertEquals("https://example.org/actual", run.sources.single().url)
                assertEquals(automatic, fixture.session().agentSettings.automaticWebSearch)
                assertEquals(!automatic, fixture.session().agentSettings.enabled)
                val requests = fixture.bodies()
                assertEquals(2, requests.size)
                assertEquals(fixture.model.id, requests.first().requiredString("model"))
                assertEquals(fixture.model.id, requests.last().requiredString("model"))
                assertEquals("required", requests.first().requiredString("tool_choice"))
                assertEquals("auto", requests.last().requiredString("tool_choice"))
                val wire = requests.last()
                if (transport == ModelTransport.RESPONSES) {
                    val input = wire.getValue("input").jsonArray
                    val call = input.map { it.jsonObject }.single { it.string("type") == "function_call" }
                    val output = input.map { it.jsonObject }.single { it.string("type") == "function_call_output" }
                    assertEquals(CALL_ID, call.requiredString("call_id"))
                    assertEquals(CALL_ID, output.requiredString("call_id"))
                    assertEquals(ARGUMENTS, call.requiredString("arguments"))
                    assertEquals(fixture.proposalOutput, input.subList(input.lastIndex - 2, input.lastIndex))
                    if (!automatic) {
                        assertEquals("[redacted]", run.steps.first().toolCalls.single()
                            .arguments!!.getValue("query").jsonPrimitive.content)
                    }
                } else {
                    val messages = wire.getValue("messages").jsonArray.map { it.jsonObject }
                    val call = messages.single { it["tool_calls"] is JsonArray }
                        .getValue("tool_calls").jsonArray.single().jsonObject
                    assertEquals(CALL_ID, call.requiredString("id"))
                    assertEquals(CALL_ID, messages.single { it.string("role") == "tool" }.requiredString("tool_call_id"))
                }
                fixture.enqueueAnswer("Kotlin explanation.", seed = 200)
                assertTrue(fixture.center.submit(ID, "解释 Kotlin 数据类") is SendResult.Accepted)
                fixture.idle()
                assertNull(fixture.center.errorFlow(ID).value)
                assertEquals(4, fixture.session().messages.size)
                assertEquals("Kotlin explanation.", fixture.session().messages.last().content)
                assertEquals(1, fixture.tools.invocations.size)
                assertTrue(fixture.bodies().last().toString().contains("Verified synthetic result"))
                if (!automatic) assertTrue(fixture.bodies().last().toString().contains("Actual fixture output"))
                assertEquals(setOf("/models", transport.endpoint), fixture.core.requests.map { it.path }.toSet())
            }
        }
    }

    @Test
    fun visionUsesTheSelectedTransportAndAcceptsRotatingResponseIds() = runBlocking {
        for (transport in ModelTransport.entries) {
            Fixture(temporary.newFolder(), transport).use { fixture ->
                fixture.enqueueAnswer("Synthetic image answer.", seed = 0)
                val deltas = fixture.core.client.streamVisionChat(VisionRequest(
                    fixture.model.id, listOf(VisionMessage("user", listOf(
                        VisionContentPart("text", "Describe the synthetic image"),
                        VisionContentPart("image_url", imageUrl = VisionImageUrl(CoreFixture.imageDataUri()))
                    )))
                )).toList()
                assertEquals("Synthetic image answer.", deltas.joinToString("") { it.text })
                assertEquals(1, deltas.count { it.isFinal })
                assertEquals(fixture.model.id, fixture.bodies().single().requiredString("model"))
                val request = fixture.core.requests.single { it.path == transport.endpoint }
                assertEquals("true", request.getHeader("Copilot-Vision-Request"))
                assertEquals(setOf("/models", transport.endpoint), fixture.core.requests.map { it.path }.toSet())
            }
        }
    }

    @Test
    fun cancellationAndAccountChangeStillCancelAfterLongRotatingIdsAndPartialText() = runBlocking {
        for (logout in listOf(false, true)) {
            Fixture(temporary.newFolder(), ModelTransport.RESPONSES).use { fixture ->
                val events = copilotTextEvents("partial")
                val prefix = agentSse(*events.dropLast(3).toTypedArray())
                val suffix = agentSse(*events.takeLast(3).toTypedArray())
                fixture.core.replies.add(CoreFixture.sse(prefix + suffix)
                    .throttleBody(prefix.toByteArray().size.toLong(), 3, TimeUnit.SECONDS))
                val received = CompletableDeferred<Unit>()
                val deltas = mutableListOf<CopilotChatClient.ChatDelta>()
                val stream = async {
                    fixture.core.client.streamChat(ChatRequest(
                        fixture.model.id, listOf(ChatMessage("user", "synthetic question"))
                    )).collect {
                        deltas.add(it)
                        if (it.text.isNotEmpty()) received.complete(Unit)
                    }
                }
                withTimeout(5_000) { received.await() }
                if (logout) {
                    fixture.core.auth.logout()
                    try {
                        withTimeout(1_000) { stream.await() }
                        fail("The prior account's response survived logout")
                    } catch (_: CancellationException) {
                        assertTrue(stream.isCancelled)
                    }
                } else withTimeout(1_000) { stream.cancelAndJoin() }
                assertTrue(deltas.any { it.text.isNotEmpty() })
                assertFalse(deltas.any { it.isFinal })
                assertTrue(fixture.tools.invocations.isEmpty())
            }
        }
    }

    private class Fixture(root: File, private val transport: ModelTransport) : AutoCloseable {
        val core = CoreFixture(root).also { it.center.close() }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = ToolSettingsStore(ToolMemoryVault(), scope)
        val tools = AgentTestExecutor(AgentToolKind.PUBLIC_WEB_SEARCH)
        val model = ModelInfo(
            if (transport == ModelTransport.RESPONSES) "gpt-5.6-sol" else "claude-sonnet-4.5",
            supportedEndpoints = listOf(transport.endpoint),
            capabilities = ModelCapabilities(type = "chat", supports = ModelSupports(
                vision = true, toolCalls = true, streaming = true
            ))
        )
        val center = ChatStreamCenter(
            core.store, core.client, core.catalog, scope,
            agentRunner = AgentEngine(
                AgentModelTransport(core.client::streamAgentChat), tools, AgentPromptBuilder(core.store.attachments)
            ),
            toolSettings = settings
        )
        var proposalOutput: List<JsonObject> = emptyList()
            private set

        init {
            core.models = { MockResponse().setBody(core.json.encodeToString(
                ModelListResponse.serializer(), ModelListResponse(listOf(model))
            )) }
        }

        suspend fun create(automatic: Boolean, enabled: Boolean = false, consented: Boolean = false) {
            core.create(ID, model.id)
            core.store.update(ID) {
                it.copy(agentSettings = AgentSessionSettings(enabled = enabled, automaticWebSearch = automatic))
            }
            if (consented) {
                assertEquals(OperationResult.Accepted, center.authorizeAutomaticWebSearch(settings.awaitReady().web.revision))
            }
            val revision = settings.awaitReady().web.revision
            tools.revision.value = revision
            tools.descriptor = tools.descriptor.copy(identity = tools.descriptor.identity.copy(configRevision = revision))
        }

        fun enqueueAnswer(text: String, seed: Int) {
            val events = if (transport == ModelTransport.RESPONSES) copilotTextEvents(text, model.id, seed)
            else listOf(agentChunk(content = text, finish = "stop"))
            core.replies.add(CoreFixture.sse(agentSse(*events.toTypedArray())))
        }

        fun enqueueCall() {
            val name = tools.descriptor.name
            val events = if (transport == ModelTransport.RESPONSES) {
                val call = responseCall(ARGUMENTS, CALL_ID, name = name)
                copilotResponseEvents(listOf(
                    responseAdded(0, responseReasoning()),
                    responseItemDone(0, responseReasoning()),
                    responseAdded(1, responseCall("", CALL_ID, name = name, complete = false)),
                    responseArguments(ARGUMENTS.take(12), index = 1),
                    responseArguments(ARGUMENTS.drop(12), index = 1),
                    responseItemDone(1, call),
                    responseCompleted(responseReasoning(), call)
                ), model.id).also { stream ->
                    proposalOutput = Json.parseToJsonElement(stream.last().data).jsonObject
                        .getValue("response").jsonObject.getValue("output").jsonArray.map { it.jsonObject }
                }
            } else listOf(
                agentChunk(listOf(agentFragment(id = CALL_ID, name = name, arguments = ARGUMENTS.take(12)))),
                agentChunk(listOf(agentFragment(arguments = ARGUMENTS.drop(12)))),
                agentChunk(finish = "tool_calls")
            )
            core.replies.add(CoreFixture.sse(agentSse(*events.toTypedArray())))
        }

        fun bodies(): List<JsonObject> = core.requests.filter { it.path == transport.endpoint }.map {
            Json.parseToJsonElement(it.body.clone().readUtf8()).jsonObject
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
        private const val ID = "protocol-recovery"
        private const val CALL_ID = "call.v1/opaque+pair==:1"
        private const val ARGUMENTS = """{ "query": "synthetic current weather" }"""
    }
}
