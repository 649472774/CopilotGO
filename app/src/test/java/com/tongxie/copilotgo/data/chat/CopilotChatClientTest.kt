package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class CopilotChatClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val request = ChatRequest("fixture-chat", listOf(ChatMessage("user", "fixture question")))

    @Test
    fun namedErrorEventFailsInsteadOfInventingFinalSuccess() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.replies.add(CoreFixture.sse("event: error\ndata: {\"error\":{\"code\":\"overloaded\"}}\n\n"))
            val deltas = mutableListOf<CopilotChatClient.ChatDelta>()
            try {
                fixture.client.streamChat(request).collect { deltas.add(it) }
                fail("SSE error was swallowed")
            } catch (expected: ApiException) {
                assertEquals("overloaded", expected.errorCode)
                assertFalse(deltas.any { it.isFinal })
            }
        }
    }

    @Test
    fun malformedChunkIsAnErrorNotACompletion() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.replies.add(CoreFixture.sse("data: not-json\n\ndata: [DONE]\n\n"))
            try {
                fixture.client.streamChat(request).collect()
                fail("Malformed data was ignored")
            } catch (_: StreamProtocolException) {
                assertEquals(1, fixture.requests.size)
            }
        }
    }

    @Test
    fun abruptEofPreservesDeliveredTextAndFails() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.replies.add(CoreFixture.sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
            ))
            val deltas = mutableListOf<CopilotChatClient.ChatDelta>()
            try {
                fixture.client.streamChat(request).collect { deltas.add(it) }
                fail("Abrupt EOF must fail")
            } catch (_: StreamProtocolException) {
                assertEquals("partial", deltas.joinToString("") { it.text })
                assertFalse(deltas.any { it.isFinal })
            }
        }
    }

    @Test
    fun emitsExactlyOneFinalAndUsesTheCachedBearer() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.replies.add(CoreFixture.sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n" +
                    "data: [DONE]\n\n"
            ))
            val deltas = fixture.client.streamChat(request).toList()
            assertEquals("answer", deltas.joinToString("") { it.text })
            assertEquals(1, deltas.count { it.isFinal })
            assertEquals("length", deltas.last().finishReason)
            assertEquals("Bearer fixture-bearer", fixture.requests.single().getHeader("Authorization"))
            assertEquals("identity", fixture.requests.single().getHeader("Accept-Encoding"))
        }
    }

    @Test
    fun failedModelDiscoveryDoesNotReturnRetiredFallbacks() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { MockResponse().setResponseCode(403).setBody("{}") }
            try {
                fixture.client.listModels()
                fail("Discovery must fail truthfully")
            } catch (expected: ApiException) {
                assertEquals(403, expected.statusCode)
                assertTrue(Constants.FALLBACK_MODELS.isEmpty())
            }
            fixture.catalog.refresh()
            assertTrue(fixture.catalog.state.value.models.isEmpty())
            assertNotNull(fixture.catalog.state.value.error)
            assertFalse(fixture.catalog.state.value.loading)
        }
    }

    @Test
    fun failedRefreshRetainsOnlyRealCatalogAndMarksItStale() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.catalog.refresh()
            assertEquals(listOf("fixture-chat", "fixture-text"), fixture.catalog.state.value.models.map { it.id })
            fixture.models = { MockResponse().setResponseCode(503).setBody("{}") }
            fixture.catalog.refresh(force = true)
            assertEquals(listOf("fixture-chat", "fixture-text"), fixture.catalog.state.value.models.map { it.id })
            assertTrue(fixture.catalog.state.value.isStale)
            assertNotNull(fixture.catalog.state.value.error)
        }
    }

    @Test
    fun discoveryFiltersNonChatDisabledAndUnsupportedEndpointModels() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { MockResponse().setBody(
                """{"data":[{"id":"chat","capabilities":{"type":"chat","supports":{"vision":true}}},{"id":"embedding","capabilities":{"type":"embeddings"}},{"id":"disabled","policy":{"state":"disabled"}},{"id":"responses-only","supported_endpoints":["/responses"]},{"id":"hidden","model_picker_enabled":false}]}"""
            ) }
            val models = fixture.client.listModels()
            assertEquals(listOf("chat"), models.map { it.id })
            assertTrue(models.single().supportsVision)
        }
    }

    @Test
    fun accountChangeCancelsAStreamDuringBlockedBodyRead() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.replies.add(CoreFixture.sse("data: [DONE]\n\n").setBodyDelay(1, TimeUnit.SECONDS))
            val stream = async { fixture.client.streamChat(request).toList() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            }
            fixture.auth.logout()
            try {
                withTimeout(750) { stream.await() }
                fail("Account-bound stream survived logout")
            } catch (_: CancellationException) {
                assertTrue(stream.isCancelled)
            }
        }
    }

    @Test
    fun slowCollectorStillReceivesAllPartialDeltasBeforeFailure() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            val chunks = (1..20).joinToString("") {
                "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"
            }
            fixture.replies.add(CoreFixture.sse(chunks + "event: error\ndata: {}\n\n"))
            val text = StringBuilder()
            try {
                fixture.client.streamChat(request).collect {
                    delay(10)
                    text.append(it.text)
                }
                fail("Expected terminal error")
            } catch (_: ApiException) {
                assertEquals("x".repeat(20), text.toString())
            }
        }
    }

    @Test
    fun logoutInvalidatesCatalogBeforeAsynchronousObserversRun() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.catalog.refresh()
            fixture.auth.logout()
            try {
                fixture.catalog.requireModel("fixture-chat", false)
                fail("Old account catalog was usable after logout")
            } catch (_: ModelUnavailableException) {
                assertTrue(fixture.catalog.state.value.models.isEmpty())
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun delayedAccountObserverDoesNotDiscardFreshCatalogOfNewAccount() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            val scheduler = TestCoroutineScheduler()
            val catalog = ModelCatalog(
                fixture.client, fixture.json, fixture.auth,
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
            )
            try {
                val credentials = fixture.credentials.credentials
                fixture.auth.logout()
                fixture.credentials.credentials = credentials
                catalog.refresh()
                assertFalse(catalog.state.value.models.isEmpty())
                scheduler.runCurrent()
                assertFalse(catalog.state.value.models.isEmpty())
            } finally {
                catalog.close()
                scheduler.runCurrent()
            }
        }
    }
}
