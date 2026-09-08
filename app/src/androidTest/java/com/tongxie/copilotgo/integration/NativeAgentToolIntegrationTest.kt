package com.tongxie.copilotgo.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.CopilotTokenClient
import com.tongxie.copilotgo.data.auth.CredentialStore
import com.tongxie.copilotgo.data.auth.DeviceFlowClient
import com.tongxie.copilotgo.data.auth.StoredCredentials
import com.tongxie.copilotgo.data.auth.TokenStore
import com.tongxie.copilotgo.data.chat.CopilotChatClient
import com.tongxie.copilotgo.data.chat.ModelCatalog
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.storage.AppPaths
import com.tongxie.copilotgo.data.storage.SessionStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Executes the same real model/MCP/approval/persistence scenario as the JVM suite, over native HTTPS. */
@RunWith(AndroidJUnit4::class)
class NativeAgentToolIntegrationTest {
    @Test
    fun modernHttpsMcpCompletesTheNativeApprovedAgentLoop() = runBlocking {
        runScenario(legacySse = false)
    }

    @Test
    fun legacyHttpsMcpSessionCompletesTheNativeApprovedAgentLoop() = runBlocking {
        runScenario(legacySse = true)
    }

    @Test
    fun dispatchedNativeMcpRedirectRemainsUnknownAndCannotBeReplayed() = runBlocking {
        runScenario(legacySse = false, failAfterDispatch = true)
    }

    private suspend fun runScenario(legacySse: Boolean, failAfterDispatch: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.tongxie.copilotgo.debug", context.packageName)
        val root = File(context.cacheDir, "native-agent-mcp-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        try {
            AgentToolIntegrationScenario().runConversation(NativeModelFixture(root), legacySse, failAfterDispatch)
        } finally {
            require(root.canonicalFile.parentFile == context.cacheDir.canonicalFile &&
                root.name.startsWith("native-agent-mcp-"))
            assertTrue("Only the owned temporary fixture directory is removed", root.deleteRecursively())
        }
    }

    private class NativeModelFixture(root: File) : AgentIntegrationModelFixture {
        override val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        override val paths = AppPaths(root)
        override val store = SessionStore(paths, json)
        override val requests = CopyOnWriteArrayList<RecordedRequest>()
        override val replies = LinkedBlockingQueue<MockResponse>()
        private val address = InetAddress.getByName("127.0.0.1")
        private val certificate = HeldCertificate.Builder()
            .commonName("Controlled native model fixture").addSubjectAlternativeName("localhost").build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        private val server = MockWebServer()
        private val provider = object : HttpClientProvider {
            override val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (hostname != "localhost") throw UnknownHostException("Unexpected native model hostname")
                        return listOf(address)
                    }
                })
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
        }
        override val client: CopilotChatClient
        override val catalog: ModelCatalog

        init {
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when (request.path) {
                        "/models" -> MockResponse().setHeader("Content-Type", "application/json").setBody(
                            """{"data":[{"id":"fixture-chat","is_chat_default":true,"capabilities":{"type":"chat","supports":{"vision":true,"tool_calls":true}}}]}"""
                        )
                        "/chat/completions" -> replies.poll(5, TimeUnit.SECONDS)
                            ?: MockResponse().setResponseCode(503)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start(address, 0)
            val stored = AtomicReference(StoredCredentials(
                "fixture-github",
                TokenStore.CachedCopilot(
                    "fixture-bearer", System.currentTimeMillis() / 1000 + 3600, "fixture",
                    server.url("/").newBuilder().host("localhost").build().toString().trimEnd('/')
                )
            ))
            val credentials = object : CredentialStore {
                override suspend fun readCredentials(): StoredCredentials = stored.get()
                override suspend fun writeCredentials(credentials: StoredCredentials) { stored.set(credentials) }
            }
            val auth = AuthRepository(credentials, DeviceFlowClient(provider, json), CopilotTokenClient(provider, json))
            client = CopilotChatClient(provider, json, auth, File(root, "model-catalog.json"))
            catalog = client.modelCatalog
        }

        override suspend fun create(id: String): Session {
            store.save(Session(id, "Controlled native MCP session", "fixture-chat"))
            return requireNotNull(store.getSession(id))
        }

        override fun enqueueText(text: String) {
            val delta = buildJsonObject {
                put("choices", JsonArray(listOf(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject { put("content", text) })
                })))
            }
            replies.add(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: $delta\n\n" +
                    """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" +
                    "\n\ndata: [DONE]\n\n"
            ))
        }

        override fun close() {
            catalog.close()
            store.close()
            provider.client.connectionPool.evictAll()
            provider.client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }
}
