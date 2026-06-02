package com.tongxie.copilotgo.data.auth

import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DeviceFlowClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DeviceFlowClient

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // 测试用 stub HttpClientProvider，固定返回一个普通 OkHttpClient。
        val provider = object : HttpClientProvider {
            override val client: OkHttpClient = OkHttpClient()
        }
        client = DeviceFlowClient(
            httpProvider = provider,
            json = json,
            clientId = "test_client",
            deviceCodeUrl = server.url("/device/code").toString(),
            accessTokenUrl = server.url("/oauth/access_token").toString()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun requestDeviceCode_parses_response() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"device_code":"abc","user_code":"AAAA-1111","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""
                )
        )
        val dc = client.requestDeviceCode()
        assertEquals("abc", dc.deviceCode)
        assertEquals("AAAA-1111", dc.userCode)
        assertEquals("https://github.com/login/device", dc.verificationUri)
        assertEquals(900, dc.expiresIn)
        assertEquals(5, dc.interval)
    }

    @Test
    fun pollOnce_returns_pending() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"authorization_pending"}""")
        )
        val r = client.pollOnce("abc")
        assertNull(r.accessToken)
        assertEquals("authorization_pending", r.error)
    }

    @Test
    fun pollOnce_returns_success() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"gho_xyz","token_type":"bearer","scope":"read:user"}""")
        )
        val r = client.pollOnce("abc")
        assertEquals("gho_xyz", r.accessToken)
        assertEquals("bearer", r.tokenType)
    }
}
