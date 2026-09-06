package com.tongxie.copilotgo.data.auth

import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HttpLifetimeTest {
    @Test
    fun deviceCodeBodyIsNeverReadOnCallingUiDispatcher() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"device_code":"fixture","user_code":"CODE","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""
            ))
            val readOnUi = AtomicBoolean(false)
            val provider = object : HttpClientProvider {
                override val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val original = requireNotNull(response.body)
                    val source = object : ForwardingSource(original.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (Thread.currentThread().name == "fixture-ui") readOnUi.set(true)
                            return super.read(sink, byteCount)
                        }
                    }.buffer()
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = original.contentType()
                        override fun contentLength() = original.contentLength()
                        override fun source() = source
                    }).build()
                }.build()
            }
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fixture-ui") }
                .asCoroutineDispatcher().use { ui ->
                    val client = DeviceFlowClient(provider, Json, deviceCodeUrl = server.url("/code").toString())
                    val code = withContext(ui) { client.requestDeviceCode() }
                    assertEquals("CODE", code.userCode)
                    assertFalse("Response body escaped onto the caller/UI thread", readOnUi.get())
                }
            provider.client.connectionPool.evictAll()
            provider.client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun cancellationAfterHeadersClosesBlockedBodyPromptly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("fixture").setBodyDelay(1, TimeUnit.SECONDS))
            val client = OkHttpClient()
            val call = client.newCall(Request.Builder().url(server.url("/slow")).build())
            val bodyStarted = CountDownLatch(1)
            val reader = launch {
                call.withResponse {
                    bodyStarted.countDown()
                    it.body!!.string()
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                assertTrue(bodyStarted.await(3, TimeUnit.SECONDS))
            }
            withTimeout(750) { reader.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue(reader.isCancelled)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun exceptionInBodyConsumerStillClosesResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("fixture"))
            val client = OkHttpClient()
            val call = client.newCall(Request.Builder().url(server.url("/")).build())
            try {
                call.withResponse<Unit> { throw IllegalStateException("fixture error") }
                fail("The consumer exception was swallowed")
            } catch (expected: IllegalStateException) {
                assertEquals("fixture error", expected.message)
            }
            assertTrue(call.isCanceled())
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
