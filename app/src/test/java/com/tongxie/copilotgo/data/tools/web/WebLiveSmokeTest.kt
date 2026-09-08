package com.tongxie.copilotgo.data.tools.web

import com.tongxie.copilotgo.data.agent.SourceKind
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.tools.SearchProvider
import com.tongxie.copilotgo.data.tools.ToolMemoryVault
import com.tongxie.copilotgo.data.tools.ToolSettingsStore
import com.tongxie.copilotgo.data.tools.WebToolSettingsDraft
import com.tongxie.copilotgo.data.tools.mcp.RemoteMcpService
import com.tongxie.copilotgo.data.tools.net.ToolHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Proxy

/** Explicit opt-in only; never reads application accounts, real vaults, cookies, or local attachments. */
class WebLiveSmokeTest {
    @Test
    fun keylessExaReturnsActualDocumentationSourcesWhenOptedIn() = runBlocking {
        assumeTrue(System.getenv("COPILOTGO_LIVE_WEB_SMOKE") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = ToolSettingsStore(ToolMemoryVault(), scope)
            val original = settings.awaitReady().web
            val web = settings.updateWeb(
                WebToolSettingsDraft(true, true, SearchProvider.EXA_KEYLESS, true), original.revision
            )
            val http = ToolHttpClient(directFixtureProvider())
            val remote = RemoteMcpService(settings, http, scope)
            val discovery = remote.discoverSearch(web.revision)
            assertTrue(discovery.tools.any { it.name == "web_search_exa" && it.supported })
            val result = WebToolService(settings, http, remote).search(
                "official Jetpack Compose documentation site:developer.android.com/develop/ui/compose",
                numResults = 3,
                expectedRevision = web.revision
            )
            assertFalse(result.isError)
            assertTrue(result.sources.isNotEmpty())
            assertTrue(result.sources.any { it.url.toHttpUrl().host == "developer.android.com" })
            assertTrue(result.sources.all { it.kind == SourceKind.SEARCH_HIT })
            println("LIVE_KEYLESS_MCP_VERSION=${discovery.protocolVersion}")
            result.sources.forEach { println("LIVE_KEYLESS_SOURCE=${it.url}") }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun publicPageUsesActualValidatedHttpsSourceWhenOptedIn() = runBlocking {
        assumeTrue(System.getenv("COPILOTGO_LIVE_WEB_SMOKE") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = ToolSettingsStore(ToolMemoryVault(), scope)
            val original = settings.awaitReady().web
            val web = settings.updateWeb(
                WebToolSettingsDraft(true, true, SearchProvider.EXA_KEYLESS, true), original.revision
            )
            val http = ToolHttpClient(directFixtureProvider())
            val remote = RemoteMcpService(settings, http, scope)
            val result = WebToolService(settings, http, remote).readPage("https://example.com/", web.revision)
            assertFalse(result.isError)
            assertEquals(SourceKind.FETCHED_PAGE, result.sources.single().kind)
            assertEquals("https://example.com/", result.sources.single().url)
            assertTrue(result.content.contains("Example Domain"))
            println("LIVE_PUBLIC_PAGE=${result.sources.single().url}")
        } finally {
            scope.cancel()
        }
    }

    private fun directFixtureProvider(): HttpClientProvider = object : HttpClientProvider {
        override val client: OkHttpClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
    }
}
