package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.CopilotTokenClient
import com.tongxie.copilotgo.data.auth.DeviceFlowClient
import com.tongxie.copilotgo.data.auth.MemoryCredentialStore
import com.tongxie.copilotgo.data.auth.StoredCredentials
import com.tongxie.copilotgo.data.auth.TokenStore
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.storage.AppPaths
import com.tongxie.copilotgo.data.storage.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

internal class CoreFixture(root: File, cacheLimit: Int = 2) : AutoCloseable {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val replies = LinkedBlockingQueue<MockResponse>()
    @Volatile var models: () -> MockResponse = { modelResponse() }
    val provider = object : HttpClientProvider {
        override val client = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS).build()
    }
    val credentials = MemoryCredentialStore()
    val auth: AuthRepository
    val client: CopilotChatClient
    val catalog: ModelCatalog
    val paths = AppPaths(root)
    val store = SessionStore(paths, json, maxInactiveSessions = cacheLimit)
    val center: ChatStreamCenter

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return when (request.path) {
                    "/models" -> models()
                    "/chat/completions" -> replies.poll(2, TimeUnit.SECONDS) ?: MockResponse().setResponseCode(503)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        credentials.credentials = StoredCredentials("fixture-github", TokenStore.CachedCopilot(
            "fixture-bearer", System.currentTimeMillis() / 1000 + 3600, "fixture",
            server.url("/").toString().trimEnd('/')
        ))
        auth = AuthRepository(credentials, DeviceFlowClient(provider, json), CopilotTokenClient(provider, json))
        client = CopilotChatClient(provider, json, auth, File(root, "catalog-cache.json"))
        catalog = client.modelCatalog
        center = ChatStreamCenter(
            store, client, catalog, CoroutineScope(SupervisorJob() + Dispatchers.Default), cacheLimit
        )
    }

    suspend fun create(id: String = "fixture-session", model: String = "fixture-chat"): Session {
        store.save(Session(id, "新会话", model))
        return requireNotNull(store.getSession(id))
    }

    suspend fun idle(id: String = "fixture-session") {
        withTimeout(5000) { center.sendingFlow(id).first { !it } }
    }

    fun enqueueText(text: String = "fixture reply") {
        replies.add(sse(
            "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
        ))
    }

    override fun close() {
        center.close()
        catalog.close()
        store.close()
        provider.client.connectionPool.evictAll()
        provider.client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    companion object {
        fun modelResponse() = MockResponse().setBody(
            """{"data":[{"id":"fixture-chat","is_chat_default":true,"capabilities":{"type":"chat","supports":{"vision":true,"tool_calls":true}}},{"id":"fixture-text","capabilities":{"type":"chat","supports":{"vision":false}}}]}"""
        )
        fun sse(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(text)
        fun imageDataUri(): String {
            val bytes = ByteArrayOutputStream().use {
                ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it)
                it.toByteArray()
            }
            return "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"
        }
    }
}
