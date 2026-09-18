package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.agent.AgentChatMessage
import com.tongxie.copilotgo.data.agent.AgentChatRequest
import com.tongxie.copilotgo.data.agent.AgentStreamEvent
import com.tongxie.copilotgo.data.net.ApiException
import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ResponsesChatClientTest {
    @get:Rule val temporary = TemporaryFolder(File("build", "responses-client-fixtures").also { it.mkdirs() })
    private val request = ChatRequest(RESPONSES_MODEL, listOf(ChatMessage("user", "fixture question")))

    @Test
    fun advertisedResponsesModelWorksForTextAndVisionUsingExistingAuthenticatedRoute() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { responsesModels(listOf("/chat/completions", "/responses")) }
            fixture.replies.add(CoreFixture.sse(agentSse(
                responseAdded(0, responseMessage(complete = false)),
                responseText("answer"),
                responseCompleted(responseMessage("answer"))
            )))
            val deltas = fixture.client.streamChat(request).toList()
            assertEquals("answer", deltas.joinToString("") { it.text })
            assertEquals(1, deltas.count { it.isFinal })
            assertEquals("stop", deltas.last().finishReason)
            val textRequest = fixture.requests.single { it.path == "/responses" }
            assertEquals(Constants.OPENAI_INTENT, textRequest.getHeader("Openai-Intent"))
            assertEquals(Constants.COPILOT_API_VERSION, textRequest.getHeader("X-GitHub-Api-Version"))
            assertEquals(Constants.COPILOT_INTEGRATION_ID, textRequest.getHeader("Copilot-Integration-Id"))
            assertEquals(Constants.EDITOR_PLUGIN_VERSION, textRequest.getHeader("Editor-Plugin-Version"))
            assertEquals("user", textRequest.getHeader("X-Initiator"))
            assertEquals("text/event-stream", textRequest.getHeader("Accept"))
            assertEquals("identity", textRequest.getHeader("Accept-Encoding"))
            assertNotNull(textRequest.getHeader("Authorization"))
            val root = Json.parseToJsonElement(textRequest.body.readUtf8()).jsonObject
            assertEquals(RESPONSES_MODEL, root.getValue("model").jsonPrimitive.content)
            assertTrue(root.containsKey("input"))
            assertFalse(root.containsKey("messages"))
            fixture.replies.add(CoreFixture.sse(agentSse(responseCompleted(responseMessage("vision answer")))))
            fixture.client.streamVisionChat(VisionRequest(RESPONSES_MODEL, listOf(
                VisionMessage("user", listOf(
                    VisionContentPart("text", "image question"),
                    VisionContentPart("image_url", imageUrl = VisionImageUrl(CoreFixture.imageDataUri()))
                ))
            ))).collect()
            val visionRequest = fixture.requests.last()
            assertEquals("/responses", visionRequest.path)
            assertEquals("true", visionRequest.getHeader("Copilot-Vision-Request"))
            assertFalse(fixture.requests.any { it.path == "/chat/completions" })
        }
    }

    @Test
    fun functionResultsReplayCallIdsAndArgumentsWithoutSwitchingTransport() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { responsesModels() }
            val proposal = responseCall("""{"query":"fixture"}""", callId = "call_for_result", id = "fc_stream_item")
            fixture.replies.add(CoreFixture.sse(agentSse(responseCompleted(proposal))))
            val first = agentTestRequest(RESPONSES_MODEL).copy(toolChoice = "required")
            val complete = fixture.client.streamAgentChat(first).toList().single() as AgentStreamEvent.Completed
            assertEquals("call_for_result", complete.toolCalls.single().id)
            fixture.replies.add(CoreFixture.sse(agentSse(responseCompleted(responseMessage("verified fixture")))))
            val next = first.copy(toolChoice = "auto", messages = first.messages + listOf(
                AgentChatMessage("assistant", toolCalls = complete.toolCalls, responsesOutput = complete.responsesOutput),
                AgentChatMessage("tool", JsonPrimitive("""{"result":"fixture"}"""), toolCallId = complete.toolCalls.single().id)
            ))
            val events = fixture.client.streamAgentChat(next).toList()
            assertEquals("verified fixture", events.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.text })
            val requests = fixture.requests.filter { it.path == "/responses" }
            assertEquals(2, requests.size)
            assertEquals("user", requests[0].getHeader("X-Initiator"))
            assertEquals("agent", requests[1].getHeader("X-Initiator"))
            assertEquals(Constants.AGENT_INTENT, requests[1].getHeader("Openai-Intent"))
            val firstBody = Json.parseToJsonElement(requests[0].body.readUtf8()).jsonObject
            val nextBody = Json.parseToJsonElement(requests[1].body.readUtf8()).jsonObject
            assertEquals("required", firstBody.getValue("tool_choice").jsonPrimitive.content)
            assertEquals("auto", nextBody.getValue("tool_choice").jsonPrimitive.content)
            val input = nextBody.getValue("input").jsonArray
            assertEquals("call_for_result", input[input.lastIndex - 1].jsonObject.getValue("call_id").jsonPrimitive.content)
            assertEquals("""{"query":"fixture"}""", input[input.lastIndex - 1].jsonObject.getValue("arguments").jsonPrimitive.content)
            assertEquals("call_for_result", input.last().jsonObject.getValue("call_id").jsonPrimitive.content)
        }
    }

    @Test
    fun realisticReasoningStreamReplaysOpaqueStateAcrossAnAuthenticatedToolRound() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { responsesModels() }
            fixture.replies.add(CoreFixture.sse(agentSse(*reasoningResponseEvents().toTypedArray())))
            val first = agentTestRequest(RESPONSES_MODEL).copy(toolChoice = "required")
            val events = fixture.client.streamAgentChat(first).toList()
            val complete = events.filterIsInstance<AgentStreamEvent.Completed>().single()
            assertEquals(REASONING_FIXTURE_TEXT,
                events.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.text })
            assertEquals(reasoningResponseOutput(), complete.responsesOutput)
            val next = first.copy(toolChoice = "auto", messages = first.messages + listOf(
                AgentChatMessage(
                    "assistant", JsonPrimitive(REASONING_FIXTURE_TEXT), toolCalls = complete.toolCalls,
                    responsesOutput = complete.responsesOutput
                ),
                AgentChatMessage("tool", JsonPrimitive("""{"result":"controlled answer"}"""), toolCallId = "call_fixture")
            ))
            fixture.replies.add(CoreFixture.sse(agentSse(responseCompleted(responseMessage("最终回复")))))
            val final = fixture.client.streamAgentChat(next).toList()
            assertEquals("最终回复", final.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.text })
            val wire = fixture.requests.last()
            assertEquals("/responses", wire.path)
            assertEquals("agent", wire.getHeader("X-Initiator"))
            val input = Json.parseToJsonElement(wire.body.readUtf8()).jsonObject.getValue("input").jsonArray
            assertEquals(reasoningResponseOutput(), input.subList(input.lastIndex - 3, input.lastIndex))
            assertEquals("call_fixture", input.last().jsonObject.getValue("call_id").jsonPrimitive.content)
            assertFalse(Json.encodeToString(
                AgentChatRequest.serializer(), next
            ).contains(REASONING_FIXTURE_CIPHERTEXT))
        }
    }

    @Test
    fun missingEncryptedToolContinuationStatePreservesVisibleTextButReturnsNoExecutableProposal() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { responsesModels() }
            val output = reasoningResponseOutput()
            val missing = JsonObject(output[0].filterKeys { it != "encrypted_content" })
            fixture.replies.add(CoreFixture.sse(agentSse(
                responseAdded(0, responseMessage(complete = false)),
                responseText("partial"),
                responseCompleted(responseMessage("partial"), missing, output[2])
            )))
            val events = mutableListOf<AgentStreamEvent>()
            try {
                fixture.client.streamAgentChat(agentTestRequest(RESPONSES_MODEL)).collect { events.add(it) }
                fail("The tool proposal lost required reasoning state")
            } catch (expected: StreamProtocolException) {
                assertTrue(expected.message.orEmpty().contains("加密推理状态"))
                assertEquals(listOf(AgentStreamEvent.TextDelta("partial")), events)
            }
        }
    }

    @Test
    fun changedCatalogTransportCannotDiscardPendingReasoningOrPostAChatCompletionsFallback() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { responsesModels() }
            fixture.replies.add(CoreFixture.sse(agentSse(*reasoningResponseEvents().toTypedArray())))
            val first = agentTestRequest(RESPONSES_MODEL)
            val complete = fixture.client.streamAgentChat(first).toList()
                .filterIsInstance<AgentStreamEvent.Completed>().single()
            fixture.models = { responsesModels(listOf("/chat/completions")) }
            fixture.catalog.refresh(force = true)
            val next = first.copy(messages = first.messages + listOf(
                AgentChatMessage(
                    "assistant", JsonPrimitive(REASONING_FIXTURE_TEXT), complete.toolCalls,
                    responsesOutput = complete.responsesOutput
                ),
                AgentChatMessage("tool", JsonPrimitive("fixture result"), toolCallId = "call_fixture")
            ))
            try {
                fixture.client.streamAgentChat(next).collect()
                fail("Responses state was dropped for a different transport")
            } catch (expected: StreamProtocolException) {
                assertTrue(expected.message.orEmpty().contains("其他协议"))
                assertEquals(listOf("/models", "/responses", "/models"), fixture.requests.map { it.path })
            }
        }
    }

    @Test
    fun slowCollectorKeepsAllPartialTextBeforeFailureIncompleteOrEof() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { responsesModels() }
            for (suffix in listOf(
                emptyList(),
                listOf(SseEvent("message", "[DONE]")),
                listOf(responseEvent("response.failed") {
                    put("response", buildJsonObject {
                        put("id", "resp_fixture"); put("status", "failed")
                        put("error", buildJsonObject { put("code", "overloaded") })
                    })
                }),
                listOf(responseEvent("response.incomplete") {
                    put("response", buildJsonObject {
                        put("id", "resp_fixture"); put("status", "incomplete")
                        put("incomplete_details", buildJsonObject { put("reason", "max_output_tokens") })
                    })
                })
            )) {
                val chunks = listOf(responseAdded(0, responseMessage(complete = false))) +
                    List(80) { responseText("x") } + suffix
                fixture.replies.add(CoreFixture.sse(agentSse(*chunks.toTypedArray())))
                val events = mutableListOf<AgentStreamEvent>()
                try {
                    fixture.client.streamAgentChat(agentTestRequest(RESPONSES_MODEL)).collect {
                        delay(2)
                        events.add(it)
                    }
                    fail("An incomplete response succeeded")
                } catch (_: java.io.IOException) {
                    assertEquals("x".repeat(80), events.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.text })
                    assertFalse(events.any { it is AgentStreamEvent.Completed })
                }
            }
        }
    }

    @Test
    fun trailingErrorAfterResponseCompletedIsNotLostAndNeverTriggersAutomaticFallback() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { responsesModels(listOf("/chat/completions", "/responses")) }
            fixture.replies.add(CoreFixture.sse(agentSse(
                responseCompleted(responseMessage("partial")), SseEvent("error", """{"code":"overloaded"}""")
            )))
            val deltas = mutableListOf<CopilotChatClient.ChatDelta>()
            try {
                fixture.client.streamChat(request).collect { deltas.add(it) }
                fail("Trailing error was ignored")
            } catch (expected: ApiException) {
                assertEquals("overloaded", expected.errorCode)
                assertEquals("partial", deltas.joinToString("") { it.text })
                assertFalse(deltas.any { it.isFinal })
                assertEquals(listOf("/models", "/responses"), fixture.requests.map { it.path })
            }
        }
    }

    @Test
    fun cancellationAndLogoutCancelTheSocketThroughoutBlockedResponsesBodyReads() = runBlocking {
        for (logout in listOf(false, true)) {
            CoreFixture(temporary.root).use { fixture ->
                fixture.models = { responsesModels() }
                fixture.replies.add(CoreFixture.sse(agentSse(responseCompleted(responseMessage("late"))))
                    .setBodyDelay(1, TimeUnit.SECONDS))
                TrackedResponsesTransport(fixture).use { tracked ->
                    val stream = async { tracked.client.streamChat(request).toList() }
                    withContext(Dispatchers.IO) { assertTrue(tracked.bodyStarted.await(3, TimeUnit.SECONDS)) }
                    if (logout) {
                        fixture.auth.logout()
                        try {
                            withTimeout(750) { stream.await() }
                            fail("Logged-out stream survived")
                        } catch (_: CancellationException) {
                            assertTrue(stream.isCancelled)
                        }
                    } else {
                        withTimeout(750) { stream.cancelAndJoin() }
                    }
                    assertTrue(tracked.call.get().isCanceled())
                    assertTrue(tracked.closed.get())
                }
            }
        }
    }

    @Test
    fun doneClosesCompletedResponsesWithoutWaitingForAnotherNetworkChunk() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { responsesModels() }
            val text = agentSse(responseCompleted(responseMessage("complete")), SseEvent("message", "[DONE]"))
            fixture.replies.add(CoreFixture.sse(text + ": delayed\n\n")
                .throttleBody(text.toByteArray().size.toLong(), 3, TimeUnit.SECONDS))
            val events = withTimeout(1500) { fixture.client.streamChat(request).toList() }
            assertTrue(events.last().isFinal)
        }
    }

    private class TrackedResponsesTransport(fixture: CoreFixture) : AutoCloseable {
        val call = AtomicReference<Call>()
        val bodyStarted = CountDownLatch(1)
        val closed = AtomicBoolean()
        private val provider = object : HttpClientProvider {
            override val client = fixture.provider.client.newBuilder().addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (chain.request().url.encodedPath != "/responses") return@addInterceptor response
                call.set(chain.call())
                val original = requireNotNull(response.body)
                val source = object : ForwardingSource(original.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        bodyStarted.countDown()
                        return super.read(sink, byteCount)
                    }
                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = source
                }).build()
            }.build()
        }
        val client = CopilotChatClient(provider, fixture.json, fixture.auth)
        override fun close() { client.modelCatalog.close() }
    }
}
