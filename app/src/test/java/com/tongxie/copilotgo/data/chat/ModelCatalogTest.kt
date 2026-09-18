package com.tongxie.copilotgo.data.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ModelCatalogTest {
    @get:Rule val temporary = TemporaryFolder(File("build", "model-catalog-fixtures").also { it.mkdirs() })

    @Test
    fun transportSelectionUsesServerMetadataWithoutModelNameLists() {
        val legacy = ModelInfo("arbitrary-new-model")
        assertEquals(ModelTransport.CHAT_COMPLETIONS, legacy.transport)
        assertTrue(legacy.chatCompatible)
        assertEquals(ModelTransport.RESPONSES,
            legacy.copy(supportedEndpoints = listOf("/chat/completions", "/responses")).transport)
        for (endpoints in listOf(emptyList(), listOf("/v1/messages"), listOf("ws:/responses"), listOf("/future"))) {
            val model = legacy.copy(supportedEndpoints = endpoints)
            assertTrue(model.pickerVisible)
            assertFalse(model.chatCompatible)
            assertNotNull(model.unavailableReason())
        }
        for (policy in listOf("disabled", "unconfigured", "unknown")) {
            assertFalse(legacy.copy(policy = ModelPolicy(policy)).chatCompatible)
        }
        assertTrue(legacy.copy(policy = ModelPolicy("enabled")).chatCompatible)
        assertFalse(legacy.copy(modelPickerEnabled = false).pickerVisible)
        assertFalse(legacy.copy(capabilities = ModelCapabilities(type = "embeddings")).pickerVisible)
        assertFalse(legacy.copy(capabilities = ModelCapabilities(supports = ModelSupports(streaming = false))).chatCompatible)
        assertNotNull(legacy.unavailableReason(needsVision = true))
        assertNotNull(legacy.unavailableReason(needsTools = true))
    }

    @Test
    fun cachedUnsupportedAndDisabledEntriesRemainExplainableButCannotAuthorizeRequests() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { MockResponse().setBody(
                """{"data":[{"id":"supported","supported_endpoints":["/responses"]},{"id":"disabled","policy":{"state":"disabled"}},{"id":"unsupported","supported_endpoints":["/v1/messages"]},{"id":"internal","model_picker_enabled":false}]}"""
            ) }
            fixture.catalog.refresh()
            val expected = listOf("supported", "disabled", "unsupported")
            assertEquals(expected, fixture.catalog.state.value.models.map { it.id })
            assertEquals("supported", fixture.catalog.requireModel("", false).id)
            for (id in listOf("disabled", "unsupported")) {
                unavailable { fixture.catalog.requireModel(id, false) }
            }
            fixture.models = { MockResponse().setResponseCode(503).setBody("{}") }
            val restored = ModelCatalog(fixture.client, fixture.json, fixture.auth, File(temporary.root, "catalog-cache.json"))
            try {
                restored.refresh()
                assertTrue(restored.state.value.isStale)
                assertEquals(expected, restored.state.value.models.map { it.id })
                assertNotNull(restored.state.value.models[1].unavailableReason())
                unavailable { restored.requireModel("supported", false) }
            } finally {
                restored.close()
            }
        }
    }

    @Test
    fun anotherAccountCannotLoadPreviousAccountsCacheEvenIfRefreshFails() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.catalog.refresh()
            val previous = fixture.credentials.credentials
            fixture.auth.logout()
            fixture.credentials.credentials = previous.copy(githubToken = "different-synthetic-account")
            fixture.models = { MockResponse().setResponseCode(403).setBody("{}") }
            fixture.catalog.refresh()
            assertTrue(fixture.catalog.state.value.models.isEmpty())
            unavailable { fixture.catalog.requireModel("fixture-chat", false) }
            assertFalse(fixture.requests.any { it.path == "/chat/completions" || it.path == "/responses" })
        }
    }

    @Test
    fun logoutCancelsInflightDiscoveryAndCannotRepopulateOldModels() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { responsesModels().setBodyDelay(1, TimeUnit.SECONDS) }
            val refresh = async { fixture.catalog.refresh() }
            withContext(Dispatchers.IO) {
                assertEquals("/models", fixture.server.takeRequest(3, TimeUnit.SECONDS)?.path)
            }
            fixture.auth.logout()
            try {
                withTimeout(750) { refresh.await() }
                fail("Old account discovery completed")
            } catch (_: CancellationException) {
                assertTrue(fixture.catalog.state.value.models.isEmpty())
            }
        }
    }

    @Test
    fun ordinaryAndVisionCallsRecheckActualCatalogInsteadOfBypassingEligibility() = runBlocking {
        CoreFixture(temporary.root).use { fixture ->
            fixture.models = { MockResponse().setBody(
                """{"data":[{"id":"disabled","policy":{"state":"disabled"}},{"id":"unsupported","supported_endpoints":["/v1/messages"]},{"id":"text","capabilities":{"supports":{"vision":false}}}]}"""
            ) }
            for (id in listOf("disabled", "unsupported", "missing")) {
                unavailable {
                    fixture.client.streamChat(ChatRequest(id, listOf(ChatMessage("user", "fixture")))).collect()
                }
            }
            unavailable {
                fixture.client.streamVisionChat(VisionRequest("text", listOf(VisionMessage("user", listOf(
                    VisionContentPart("image_url", imageUrl = VisionImageUrl("https://example.test/image.png"))
                ))))).collect()
            }
            assertEquals(listOf("/models"), fixture.requests.map { it.path })
        }
    }

    private suspend fun unavailable(block: suspend () -> Unit) {
        try {
            block()
            fail("Unavailable model was authorized")
        } catch (expected: ModelUnavailableException) {
            assertFalse(expected.message.isNullOrBlank())
        }
    }
}
