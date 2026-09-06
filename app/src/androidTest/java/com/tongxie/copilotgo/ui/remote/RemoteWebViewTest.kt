package com.tongxie.copilotgo.ui.remote

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.ui.screens.RemoteWebViewContent
import com.tongxie.copilotgo.ui.theme.CopilotGoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Run only on the integration-owned synthetic device; no account, cookie or live network fixture. */
@RunWith(AndroidJUnit4::class)
class RemoteWebViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var browser: RemoteBrowserSession
    private val preferences = MemoryPreferences()
    private val proxy = FakeProxy()
    private val webData = FakeWebData()
    private val loads = AtomicInteger()
    private val appProxy = MutableStateFlow(ProxyConfig(enabled = true, host = "10.0.2.2", port = 7890))
    private val proxyInitialized = MutableStateFlow(true)
    private val proxyError = MutableStateFlow<String?>(null)
    private var visible by mutableStateOf(true)
    private var dark by mutableStateOf(false)
    private var fontScale by mutableStateOf(1f)

    @Before
    fun createIsolatedSession() {
        compose.activityRule.scenario.onActivity { activity ->
            configureWindow(activity)
            browser = RemoteBrowserSession(activity, preferences, proxy, webData) { view, url ->
                loads.incrementAndGet()
                view.settings.blockNetworkLoads = true
                view.loadDataWithBaseURL(url, FIXTURE, "text/html", "UTF-8", null)
            }
        }
    }

    @After
    fun disposeSession() {
        compose.runOnUiThread { browser.dispose() }
    }

    @Test
    fun reentry_retains_document_scroll_draft_and_context_is_detached() {
        showFixture()
        val original = awaitPage()
        evaluate("document.getElementById('composer').value='retained draft'; window.scrollTo(0,640);")
        val scroll = evaluate("Math.round(window.scrollY)")
        closeRemote()
        assertNull(original.context.findActivity())
        returnToRemote()
        assertSame(original, awaitPage())
        assertEquals(1, loads.get())
        assertEquals("\"retained draft\"", evaluate("document.getElementById('composer').value"))
        assertEquals(scroll, evaluate("Math.round(window.scrollY)"))
        assertSame(compose.activity, original.context.findActivity())
        capture("remote-retained")
    }

    @Test
    fun activity_recreation_reattaches_same_live_document_without_reload() {
        showFixture()
        val original = awaitPage()
        evaluate("document.getElementById('composer').value='configuration draft'")
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity {
            configureWindow(it)
            it.setContent { FixtureContent() }
        }
        assertSame(original, awaitPage())
        assertEquals(1, loads.get())
        assertEquals("\"configuration draft\"", evaluate("document.getElementById('composer').value"))
        assertSame(compose.activity, original.context.findActivity())
    }

    @Test
    fun failed_load_remains_failed_across_reentry_until_explicit_retry() {
        showFixture()
        val original = awaitPage()
        compose.runOnUiThread {
            browser.pageFailed(original, RemoteProblem("Controlled network failure", "Retry the local fixture"))
            browser.pageFinished(original, browser.state.value.page.url)
        }
        closeRemote()
        returnToRemote()
        compose.onNodeWithText("Controlled network failure").assertIsDisplayed()
        assertEquals(1, loads.get())
        compose.onNodeWithText("重试 / 恢复页面").performClick()
        awaitPage()
        assertEquals(2, loads.get())
    }

    @Test
    fun post_redirect_start_or_commit_cannot_leave_external_content_under_trusted_chrome() {
        showFixture()
        awaitPage()
        listOf(false, true).forEach { commitWithoutStart ->
            val view = requireNotNull(currentWebView())
            compose.runOnUiThread {
                if (commitWithoutStart) {
                    view.webViewClient.onPageCommitVisible(view, "https://outside.example/post-redirect")
                } else {
                    view.webViewClient.onPageStarted(view, "https://outside.example/post-redirect", null)
                }
            }
            assertNull(currentWebView())
            assertEquals(RemoteDestination.EXTERNAL, RemoteNavigationPolicy.classify(browser.state.value.page.url).destination)
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("已阻止外部网页").assertIsDisplayed()
            compose.onNodeWithText("已阻止的网页").assertIsDisplayed()
            compose.onNodeWithText("重试 / 恢复页面").performClick()
            awaitPage()
        }
    }

    @Test
    fun http_headers_failure_before_page_start_stays_failed_through_commit_and_finish() {
        showFixture()
        val view = awaitPage()
        val failedUrl = "https://github.com/copilot/c/controlled-failure"
        compose.runOnUiThread {
            view.webViewClient.onReceivedHttpError(
                view,
                mainRequest(failedUrl),
                WebResourceResponse("text/html", "UTF-8", 503, "Unavailable", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            )
            view.webViewClient.onPageStarted(view, failedUrl, null)
            view.webViewClient.onPageFinished(view, failedUrl)
            view.webViewClient.onPageCommitVisible(view, failedUrl)
        }
        compose.onNodeWithText("网页服务返回错误").assertIsDisplayed()
        assertEquals(RemoteLoadPhase.FAILED, browser.state.value.page.phase)
        assertEquals(failedUrl, browser.state.value.page.url)
    }

    @Test
    fun late_aborted_same_url_finish_cannot_complete_replacement_document() {
        showFixture()
        val old = awaitPage()
        compose.runOnUiThread {
            val oldClient = old.webViewClient
            val url = browser.state.value.page.url
            browser.navigationRequested(old, url)
            browser.stop()
            browser.retry()
            oldClient.onPageFinished(old, url)
            assertFalse(browser.owns(old))
            assertEquals(RemoteLoadPhase.LOADING, browser.state.value.page.phase)
            assertFalse(browser.state.value.page.hasVisiblePage)
        }
        assertNotSame(old, awaitPage())
        assertEquals(2, loads.get())
    }

    @Test
    fun renderer_exit_callback_removes_dead_view_and_requires_recovery() {
        showFixture()
        val original = awaitPage()
        compose.runOnUiThread {
            assertTrue(original.webViewClient.onRenderProcessGone(original, object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_IMPORTANT
            }))
        }
        compose.onNodeWithText("网页进程已关闭").assertIsDisplayed()
        assertEquals(1, loads.get())
        assertNull(currentWebView())
        compose.onNodeWithText("重试 / 恢复页面").performClick()
        assertNotSame(original, awaitPage())
        assertEquals(2, loads.get())
    }

    @Test
    fun memory_pressure_only_reclaims_detached_page_and_preserves_mode() {
        compose.runOnUiThread {
            preferences.preferences.value = RemotePreferences(desktop = true, immersive = false)
        }
        showFixture()
        val original = awaitPage()
        compose.runOnUiThread { browser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) }
        assertSame(original, currentWebView())
        closeRemote()
        compose.runOnUiThread { browser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) }
        returnToRemote()
        compose.onNodeWithText("网页已释放以节省内存").assertIsDisplayed()
        assertEquals(1, loads.get())
        compose.onNodeWithText("重试 / 恢复页面").performClick()
        assertNotSame(original, awaitPage())
        assertTrue(browser.state.value.preferences.desktop)
        assertFalse(browser.state.value.preferences.immersive)
    }

    @Test
    fun proxy_waits_for_storage_and_native_callback_then_blocks_credentialed_change() {
        compose.runOnUiThread {
            preferences.preferences.value = RemotePreferences(networkMode = RemoteNetworkMode.APP_PROXY)
            proxyInitialized.value = false
            proxy.deferred = true
        }
        showFixture()
        compose.waitForIdle()
        assertEquals(0, loads.get())
        compose.runOnUiThread { proxyInitialized.value = true }
        compose.waitUntil(5_000) {
            var pending = 0
            compose.runOnUiThread { pending = proxy.pendingCount() }
            pending == 1
        }
        assertEquals(0, loads.get())
        compose.runOnUiThread { proxy.complete() }
        awaitPage()
        assertEquals(RemoteProxyRoute.Override("http://10.0.2.2:7890"), proxy.routes.first())
        compose.runOnUiThread { appProxy.value = appProxy.value.copy(username = "fixture-user") }
        compose.onNodeWithText("Remote 不支持带认证的代理").assertIsDisplayed()
        assertNull(currentWebView())
        assertEquals(1, loads.get())
        compose.runOnUiThread { proxy.complete() }
        assertEquals(RemoteProxyRoute.System, proxy.routes.last())
        assertEquals(1, loads.get())
    }

    @Test
    fun logout_waits_for_both_fake_cookie_and_storage_callbacks() {
        showFixture()
        awaitPage()
        compose.runOnUiThread { browser.logout(); browser.logout() }
        assertEquals(1, webData.removals)
        assertTrue(browser.state.value.clearingCookies)
        assertEquals(1, loads.get())
        compose.runOnUiThread { webData.cookiesRemoved() }
        assertTrue(browser.state.value.clearingCookies)
        assertEquals(1, loads.get())
        compose.runOnUiThread { webData.storageRemoved() }
        awaitPage()
        assertFalse(browser.state.value.clearingCookies)
        assertEquals(2, loads.get())
        assertTrue(webData.flushes.get() > 0)
    }

    @Test
    fun external_destination_requires_confirmation_and_temporary_download_reports_limitation() {
        showFixture()
        awaitPage()
        compose.runOnUiThread { browser.requestExternal("https://outside.example/path?private-query=fixture") }
        compose.onNodeWithText("https://outside.example").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, loads.get())
        compose.runOnUiThread { browser.requestExternal("blob:https://github.com/fixture", download = true) }
        compose.waitUntil(5_000) { browser.state.value.notice?.message?.contains("不受支持") == true }
        assertNull(browser.state.value.externalRequest)
        assertEquals(1, loads.get())
    }

    @Test
    fun large_font_light_and_dark_keep_controls_and_viewport_operable() {
        compose.runOnUiThread { fontScale = 2f }
        showFixture()
        val view = awaitPage()
        assertTouchTargets()
        compose.runOnUiThread { assertEquals(200, view.settings.textZoom) }
        capture("remote-large-font-light")
        compose.runOnUiThread { dark = true }
        compose.waitForIdle()
        assertTouchTargets()
        assertSame(view, currentWebView())
        assertEquals(1, loads.get())
        capture("remote-large-font-dark")
    }

    @Test
    fun ime_back_and_return_to_native_do_not_double_shrink_or_flip_window_policy() {
        showFixture()
        val view = awaitPage()
        val softInputMode = compose.activity.window.attributes.softInputMode
        val bars = WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
        val lightStatus = bars.isAppearanceLightStatusBars
        val lightNavigation = bars.isAppearanceLightNavigationBars
        val layouts = AtomicInteger()
        compose.runOnUiThread {
            view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) layouts.incrementAndGet()
            }
            view.requestFocus()
        }
        evaluate("document.getElementById('composer').focus({preventScroll:true})")
        compose.runOnUiThread {
            val ime = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            ime.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
        awaitIme(visible = true)
        compose.runOnUiThread {
            val root = view.rootView
            val inset = ViewCompat.getRootWindowInsets(root)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val webPosition = IntArray(2)
            val rootPosition = IntArray(2)
            view.getLocationInWindow(webPosition)
            root.getLocationInWindow(rootPosition)
            val bottom = webPosition[1] - rootPosition[1] + view.height
            assertTrue("WebView must end above the keyboard", bottom <= root.height - inset + 2)
        }
        assertTrue("Chromium should not resize every animation frame: ${layouts.get()}", layouts.get() <= 6)
        assertComposerVisible()
        evaluate("window.scrollTo(0,640)")
        val scroll = evaluate("Math.round(window.scrollY)").toInt()
        assertComposerVisible()
        capture("remote-ime")
        Espresso.pressBack()
        awaitIme(visible = false)
        compose.onNodeWithContentDescription("关闭网页，返回会话列表").assertIsDisplayed()
        assertTrue(abs(evaluate("Math.round(window.scrollY)").toInt() - scroll) <= 2)
        assertComposerVisible()
        assertEquals(softInputMode, compose.activity.window.attributes.softInputMode)
        Espresso.pressBack()
        compose.onNodeWithText("Return to Remote").assertIsDisplayed()
        assertEquals(softInputMode, compose.activity.window.attributes.softInputMode)
        assertEquals(lightStatus, bars.isAppearanceLightStatusBars)
        assertEquals(lightNavigation, bars.isAppearanceLightNavigationBars)
        capture("remote-returned-native")
    }

    private fun showFixture() = compose.setContent { FixtureContent() }

    @Composable
    private fun FixtureContent() {
        val activity = requireNotNull(LocalContext.current.findActivity())
        val configuration = Configuration(LocalConfiguration.current).apply { fontScale = this@RemoteWebViewTest.fontScale }
        val density = Density(LocalDensity.current.density, fontScale)
        CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides density) {
            CopilotGoTheme(darkTheme = dark, dynamicColor = false) {
                SideEffect {
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    BackHandler(enabled = visible) { visible = false }
                    if (visible) {
                        RemoteWebViewContent(
                            browser = browser,
                            onBack = { visible = false },
                            proxyConfig = appProxy,
                            proxyInitialized = proxyInitialized,
                            proxyLoadError = proxyError
                        )
                    } else {
                        TextButton(onClick = { visible = true }) { Text("Return to Remote") }
                    }
                }
            }
        }
    }

    private fun configureWindow(activity: ComponentActivity) {
        activity.enableEdgeToEdge()
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun closeRemote() {
        compose.onNodeWithContentDescription("关闭网页，返回会话列表").performClick()
        compose.onNodeWithText("Return to Remote").assertIsDisplayed()
    }

    private fun returnToRemote() = compose.onNodeWithText("Return to Remote").performClick()

    private fun awaitPage(): WebView {
        compose.waitUntil(10_000) {
            browser.state.value.page.phase == RemoteLoadPhase.READY && !browser.state.value.clearingCookies
        }
        compose.waitForIdle()
        return requireNotNull(currentWebView())
    }

    private fun currentWebView(): WebView? {
        var result: WebView? = null
        compose.runOnUiThread { result = findWebView(compose.activity.window.decorView) }
        return result
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun mainRequest(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        val view = requireNotNull(currentWebView())
        compose.runOnUiThread { view.evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue("JavaScript fixture callback", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun awaitIme(visible: Boolean) {
        compose.waitUntil(10_000) {
            var isVisible = false
            compose.runOnUiThread {
                isVisible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            isVisible == visible
        }
        compose.waitForIdle()
    }

    private fun assertTouchTargets() {
        val density = compose.activity.resources.displayMetrics.density
        listOf("关闭网页，返回会话列表", "网页选项").forEach { description ->
            val bounds = compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(description, bounds.width / density >= 47.9f && bounds.height / density >= 47.9f)
        }
    }

    private fun assertComposerVisible() {
        val visible = evaluate("""
            (function(){
              var rect=document.getElementById('composer').getBoundingClientRect();
              var viewport=window.visualViewport;
              var top=viewport ? viewport.offsetTop : 0;
              var height=viewport ? viewport.height : window.innerHeight;
              return rect.height > 0 && rect.top >= top-1 && rect.bottom <= top+height+1;
            })()
        """.trimIndent())
        assertEquals("The composer itself must remain inside Chromium's visible viewport", "true", visible)
    }

    private fun capture(name: String) {
        val directory = File(compose.activity.getExternalFilesDir(null), "remote-acceptance")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private class MemoryPreferences : RemoteSettings {
        override val preferences = MutableStateFlow(RemotePreferences())
        override suspend fun update(value: RemotePreferences) { preferences.value = value }
    }

    private class FakeProxy : RemoteProxyBackend {
        override val supported = true
        var deferred = false
        val routes = mutableListOf<RemoteProxyRoute>()
        private val callbacks = ArrayDeque<(Boolean) -> Unit>()
        override fun configure(route: RemoteProxyRoute, completion: (Boolean) -> Unit) {
            routes += route
            if (deferred) callbacks.addLast(completion) else completion(true)
        }
        fun pendingCount() = callbacks.size
        fun complete() = callbacks.removeFirst().invoke(true)
    }

    private class FakeWebData : RemoteWebData {
        var removals = 0
        val flushes = AtomicInteger()
        private var cookieCallback: ((Boolean) -> Unit)? = null
        private var storageCallback: ((Boolean) -> Unit)? = null
        override fun configure(view: WebView) = Unit
        override fun removeCookies(completion: (Boolean) -> Unit) { removals++; cookieCallback = completion }
        override fun clearStorage(completion: (Boolean) -> Unit) { storageCallback = completion }
        override fun flush() { flushes.incrementAndGet() }
        fun cookiesRemoved() { cookieCallback!!.invoke(false); cookieCallback = null }
        fun storageRemoved() { storageCallback!!.invoke(true); storageCallback = null }
    }

    companion object {
        private const val FIXTURE = """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'none'; form-action 'none'">
            <style>
              html,body{margin:0;background:#f6f8fa;color:#1f2328;font:18px sans-serif}
              #history{height:3200px;padding:16px;box-sizing:border-box;
                background:repeating-linear-gradient(#f6f8fa 0 79px,#d1d9e0 79px 80px)}
              #composer{position:fixed;box-sizing:border-box;left:0;bottom:0;width:100%;
                height:72px;font:18px sans-serif;padding:12px;background:white;color:#1f2328}
            </style></head><body>
              <div id="history">Local, offline Remote acceptance fixture. No account or conversation data.</div>
              <textarea id="composer" aria-label="Fixture composer">fixture draft</textarea>
            </body></html>
        """
    }
}
