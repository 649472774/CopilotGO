package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.agent.AgentChatMessage
import com.tongxie.copilotgo.data.agent.AgentStreamEvent
import com.tongxie.copilotgo.data.net.ApiException
import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AgentChatClientTest {
    @get:Rule
    val temporary = TemporaryFolder(File("build", "agent-transport-fixtures").also { it.mkdirs() })

    @Test
    fun typedAgentUsesExistingAuthenticatedChatEndpointAndHeaders() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.replies.add(CoreFixture.sse(agentSse(
                agentCompleteCall(), agentChunk(finish = "tool_calls"), SseEvent("message", "[DONE]")
            )))
            val events = fixture.client.streamAgentChat(agentTestRequest()).toList()
            val completed = events.single() as AgentStreamEvent.Completed
            assertEquals("call_fixture", completed.toolCalls.single().id)
            val sent = fixture.requests.single { it.path == "/chat/completions" }
            assertEquals("Bearer fixture-bearer", sent.getHeader("Authorization"))
            assertEquals("identity", sent.getHeader("Accept-Encoding"))
            assertEquals("text/event-stream", sent.getHeader("Accept"))
            assertEquals(Constants.COPILOT_INTEGRATION_ID, sent.getHeader("Copilot-Integration-Id"))
            assertEquals(Constants.OPENAI_INTENT, sent.getHeader("Openai-Intent"))
            assertEquals(Constants.USER_AGENT_VSCODE, sent.getHeader("User-Agent"))
            assertEquals(Constants.EDITOR_VERSION, sent.getHeader("Editor-Version"))
            assertEquals(Constants.EDITOR_PLUGIN_VERSION, sent.getHeader("Editor-Plugin-Version"))
            assertNotNull(sent.getHeader("X-Request-Id"))
            assertNotNull(sent.getHeader("VScode-SessionId"))
            assertNotNull(sent.getHeader("VScode-MachineId"))
            val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("fixture-chat", body.getValue("model").jsonPrimitive.content)
            assertTrue(body.containsKey("tools"))
            assertTrue(body.containsKey("tool_choice"))
            assertTrue(body.containsKey("parallel_tool_calls"))
            assertEquals(listOf("/models", "/chat/completions"), fixture.requests.map { it.path })
        }
    }

    @Test
    fun interleavedNetworkFragmentsEmitOnlyOneCompleteToolProposal() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.replies.add(CoreFixture.sse(agentSse(
                agentChunk(content = "checking"),
                agentChunk(listOf(agentFragment(1, "b", "lookup", """{"query":"""))),
                agentChunk(listOf(agentFragment(0, "a", "lookup", """{"query":"fi"""))),
                agentChunk(listOf(agentFragment(1, arguments = "\"other\"}"), agentFragment(0, arguments = "xture\"}"))),
                agentChunk(finish = "tool_calls")
            )))
            val events = fixture.client.streamAgentChat(agentTestRequest()).toList()
            assertEquals(2, events.size)
            assertEquals(AgentStreamEvent.TextDelta("checking"), events.first())
            val completed = events.last() as AgentStreamEvent.Completed
            assertEquals(listOf("a", "b"), completed.toolCalls.map { it.id })
            assertEquals("""{"query":"fixture"}""", completed.toolCalls.first().function.arguments)
        }
    }

    @Test
    fun slowCollectorReceivesEveryQueuedDeltaBeforeNamedAndJsonErrors() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            for (error in listOf(
                SseEvent("error", """{"code":"overloaded"}"""),
                SseEvent("message", """{"error":{"code":"overloaded"}}""")
            )) {
                val chunks = List(100) { agentChunk(content = "x") } + error
                fixture.replies.add(CoreFixture.sse(agentSse(*chunks.toTypedArray())))
                val events = mutableListOf<AgentStreamEvent>()
                val exception = failure<ApiException> {
                    fixture.client.streamAgentChat(agentTestRequest()).collect {
                        delay(2)
                        events.add(it)
                    }
                }
                assertEquals("overloaded", exception.errorCode)
                assertEquals("x".repeat(100), events.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.text })
                assertFalse(events.any { it is AgentStreamEvent.Completed })
            }
        }
    }

    @Test
    fun eofDoneAndTrailingErrorsCannotInventSuccessfulCompletion() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            for (suffix in listOf(emptyList(), listOf(SseEvent("message", "[DONE]")))) {
                fixture.replies.add(CoreFixture.sse(agentSse(
                    *(listOf(agentChunk(content = "partial")) + suffix).toTypedArray()
                )))
                val events = mutableListOf<AgentStreamEvent>()
                failure<StreamProtocolException> { fixture.client.streamAgentChat(agentTestRequest()).collect { events.add(it) } }
                assertEquals(listOf(AgentStreamEvent.TextDelta("partial")), events)
            }
            fixture.replies.add(CoreFixture.sse(agentSse(
                agentCompleteCall(), agentChunk(finish = "tool_calls"), SseEvent("error", "{}")
            )))
            val events = mutableListOf<AgentStreamEvent>()
            failure<ApiException> { fixture.client.streamAgentChat(agentTestRequest()).collect { events.add(it) } }
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun rejectedSelectedModelNeverPostsChatOrSwitchesToDefault() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { MockResponse().setBody(
                """{"data":[{"id":"fixture-chat","capabilities":{"type":"chat","supports":{"tool_calls":true}}},{"id":"fixture-text","is_chat_default":true,"capabilities":{"type":"chat","supports":{"tool_calls":false}}},{"id":"responses-only","supported_endpoints":["/responses"],"capabilities":{"supports":{"tool_calls":true}}}]}"""
            ) }
            for (id in listOf("fixture-text", "responses-only", "not-in-catalog")) {
                failure<ModelUnavailableException> { fixture.client.streamAgentChat(agentTestRequest(id)).collect() }
            }
            assertEquals(listOf("/models"), fixture.requests.map { it.path })
            assertEquals("fixture-chat", fixture.catalog.requireModel("", false, needsTools = true).id)
            assertEquals("fixture-text", fixture.catalog.requireModel("", false).id)
            assertEquals("fixture-text", fixture.catalog.requireModel("fixture-text", false).id)
        }
    }

    @Test
    fun toolsAndVisionMustBothBePresentInActualCatalog() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { MockResponse().setBody(
                """{"data":[{"id":"fixture-chat","capabilities":{"type":"chat","supports":{"tool_calls":true,"vision":false}}}]}"""
            ) }
            val request = agentTestRequest().copy(messages = listOf(AgentChatMessage("user", JsonArray(listOf(
                buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", "https://example.test/image.png") })
                }
            )))))
            failure<ModelUnavailableException> { fixture.client.streamAgentChat(request).collect() }
            assertEquals(listOf("/models"), fixture.requests.map { it.path })
        }
    }

    @Test
    fun staleOrFailedCatalogDoesNotAuthorizeAgentHttp() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.catalog.refresh()
            fixture.models = { MockResponse().setResponseCode(503).setBody("{}") }
            fixture.catalog.refresh(force = true)
            failure<ModelUnavailableException> { fixture.client.streamAgentChat(agentTestRequest()).collect() }
            assertFalse(fixture.requests.any { it.path == "/chat/completions" })
            assertTrue(fixture.catalog.state.value.isStale)
        }
    }

    @Test
    fun invalidRuntimeRequestsAreRejectedBeforeAnyHttp() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            val request = agentTestRequest()
            val tool = request.tools.single()
            for (invalid in listOf(
                request.copy(n = 2), request.copy(stream = false), request.copy(model = ""),
                request.copy(tools = List(AgentWireLimits.MAX_TOOL_DEFINITIONS + 1) {
                    tool.copy(function = tool.function.copy(name = "tool_$it"))
                })
            )) failure<StreamProtocolException> { fixture.client.streamAgentChat(invalid).collect() }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun doneClosesACompletedStreamWithoutWaitingForTheServerToCloseItsBody() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            val complete = agentSse(
                agentCompleteCall(), agentChunk(finish = "tool_calls"), SseEvent("message", "[DONE]")
            )
            fixture.replies.add(CoreFixture.sse(complete + ": delayed keepalive\n\n")
                .throttleBody(complete.toByteArray().size.toLong(), 3, TimeUnit.SECONDS))
            val events = withTimeout(1500) { fixture.client.streamAgentChat(agentTestRequest()).toList() }
            assertEquals(1, events.filterIsInstance<AgentStreamEvent.Completed>().size)
        }
    }

    @Test
    fun ordinaryTextAndVisionContinueRejectingToolCallsAfterDeliveringPartials() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            val ordinary = listOf(
                fixture.client.streamChat(ChatRequest("fixture-chat", listOf(ChatMessage("user", "fixture")))),
                fixture.client.streamVisionChat(VisionRequest("fixture-chat", listOf(
                    VisionMessage("user", listOf(VisionContentPart("text", "fixture")))
                )))
            )
            for (stream in ordinary) {
                fixture.replies.add(CoreFixture.sse(agentSse(
                    agentChunk(content = "partial"), agentCompleteCall(), agentChunk(finish = "tool_calls")
                )))
                val events = mutableListOf<CopilotChatClient.ChatDelta>()
                failure<StreamProtocolException> { stream.collect { delay(2); events.add(it) } }
                assertEquals("partial", events.joinToString("") { it.text })
                assertFalse(events.any { it.isFinal })
            }
            assertEquals(listOf("/chat/completions", "/chat/completions"), fixture.requests.map { it.path })
        }
    }

    @Test
    fun entireBodyIsConsumedOnIoAndClosedOnSuccessAndFailure() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            TrackedTransport(fixture).use { tracked ->
                Executors.newSingleThreadExecutor { Thread(it, "agent-fixture-ui") }.asCoroutineDispatcher().use { ui ->
                    fixture.replies.add(CoreFixture.sse(agentSse(agentChunk(content = "reply", finish = "stop"))))
                    val events = withContext(ui) { tracked.client.streamAgentChat(agentTestRequest()).toList() }
                    assertTrue(events.last() is AgentStreamEvent.Completed)
                    assertTrue(tracked.closed.get())
                    assertFalse(tracked.readOnUi.get())
                    tracked.closed.set(false)
                    fixture.replies.add(CoreFixture.sse("data: not-json\n\n"))
                    failure<StreamProtocolException> {
                        withContext(ui) { tracked.client.streamAgentChat(agentTestRequest()).collect() }
                    }
                    assertTrue(tracked.closed.get())
                    assertFalse(tracked.readOnUi.get())
                    assertTrue(tracked.call.get().isCanceled())
                    tracked.closed.set(false)
                    fixture.replies.add(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"overloaded"}}"""))
                    failure<ApiException> { withContext(ui) { tracked.client.streamAgentChat(agentTestRequest()).collect() } }
                    assertTrue(tracked.closed.get())
                    assertFalse(tracked.readOnUi.get())
                }
            }
        }
    }

    @Test
    fun cancellationAndLogoutCloseBlockedBodyAndRethrowCancellation() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            TrackedTransport(fixture).use { tracked ->
                fixture.replies.add(CoreFixture.sse(agentSse(agentChunk(content = "reply", finish = "stop")))
                    .setBodyDelay(1, TimeUnit.SECONDS))
                val stream = async { tracked.client.streamAgentChat(agentTestRequest()).toList() }
                withContext(Dispatchers.IO) { assertTrue(tracked.bodyStarted.await(3, TimeUnit.SECONDS)) }
                withTimeout(750) { stream.cancelAndJoin() }
                assertTrue(stream.isCancelled)
                assertTrue(tracked.call.get().isCanceled())
                assertTrue(tracked.closed.get())
            }
            TrackedTransport(fixture).use { tracked ->
                fixture.replies.add(CoreFixture.sse(agentSse(agentChunk(content = "reply", finish = "stop")))
                    .setBodyDelay(1, TimeUnit.SECONDS))
                val generation = fixture.client.accountGeneration.value
                val stream = async { tracked.client.streamAgentChat(agentTestRequest()).toList() }
                withContext(Dispatchers.IO) { assertTrue(tracked.bodyStarted.await(3, TimeUnit.SECONDS)) }
                fixture.auth.logout()
                failure<CancellationException> { withTimeout(750) { stream.await() } }
                assertTrue(fixture.client.accountGeneration.value > generation)
                assertTrue(stream.isCancelled)
                assertTrue(tracked.call.get().isCanceled())
                assertTrue(tracked.closed.get())
            }
        }
    }

    @Test
    fun consumerStoppingEarlyClosesTheResponse() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            TrackedTransport(fixture).use { tracked ->
                fixture.replies.add(CoreFixture.sse(agentSse(agentChunk(content = "partial")) + "data: ")
                    .throttleBody(1, 1, TimeUnit.MILLISECONDS))
                assertEquals(AgentStreamEvent.TextDelta("partial"),
                    tracked.client.streamAgentChat(agentTestRequest()).first())
                withTimeout(1000) { while (!tracked.closed.get()) delay(5) }
                assertTrue(tracked.call.get().isCanceled())
            }
        }
    }

    private suspend inline fun <reified T : Throwable> failure(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.simpleName}, got $error", error is T)
            return error as T
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private class TrackedTransport(fixture: CoreFixture) : AutoCloseable {
        val closed = AtomicBoolean()
        val readOnUi = AtomicBoolean()
        val bodyStarted = CountDownLatch(1)
        val call = AtomicReference<Call>()
        private val provider = object : HttpClientProvider {
            override val client = fixture.provider.client.newBuilder().addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (chain.request().url.encodedPath != "/chat/completions") return@addInterceptor response
                call.set(chain.call())
                val original = requireNotNull(response.body)
                val source = object : ForwardingSource(original.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        bodyStarted.countDown()
                        if (Thread.currentThread().name == "agent-fixture-ui") readOnUi.set(true)
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
