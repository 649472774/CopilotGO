package com.tongxie.copilotgo.ui.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.os.Message
import android.util.AndroidRuntimeException
import android.view.View
import android.view.ViewGroup
import android.webkit.ServiceWorkerController
import android.webkit.ServiceWorkerWebSettings
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import com.tongxie.copilotgo.BuildConfig
import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

/** All mutable browser state belongs to the retained session, never a Composable callback. */
@MainThread
internal class RemoteBrowserSession(
    context: Context,
    private val settings: RemoteSettings = RemoteSettingsStore(context),
    proxyBackend: RemoteProxyBackend = AndroidRemoteProxyBackend(),
    private val webData: RemoteWebData = AndroidRemoteWebData(),
    private val loadDocument: (WebView, String) -> Unit = { view, url -> view.loadUrl(url) }
) : ComponentCallbacks2 {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(RemoteBrowserState())
    val state: StateFlow<RemoteBrowserState> = mutableState.asStateFlow()
    val uploads = RemoteUploads(appContext, scope, ::notify)

    private val proxy = RemoteProxyController(scope, proxyBackend, ::proxyChanged)
    private val clients = RemoteWebClients(this)
    private val popups = RemotePopups(appContext, ::navigatePopup, ::notify)
    private val cookieBarrier = CookieLogoutBarrier()
    private var cookieTimeout: Job? = null
    private var logoutSequence = 0L
    private var preferenceJob: Job? = null
    private var sourceJob: Job? = null
    private var loadTimeout: Job? = null
    private var loadSequence = 0L
    private var noticeSequence = 0L
    private var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var workerSettings: ServiceWorkerWebSettings? = null
    private var host: RemoteWebViewHost? = null
    private var owner: Any? = null
    private var resumed = false
    private var background = 0
    private var homeUrl = Constants.REMOTE_HOME_URL
    private var recoveryUrl = homeUrl
    private var waitForUser = false
    private var decision: RemoteNetworkDecision? = null
    private var configSource: StateFlow<ProxyConfig>? = null
    private var initializedSource: StateFlow<Boolean>? = null
    private var errorSource: StateFlow<String?>? = null

    init {
        appContext.registerComponentCallbacks(this)
        readPreferences()
    }

    fun bindProxySources(
        config: StateFlow<ProxyConfig>?,
        initialized: StateFlow<Boolean>?,
        loadError: StateFlow<String?>?
    ) {
        if (configSource === config && initializedSource === initialized && errorSource === loadError) return
        sourceJob?.cancel()
        configSource = config
        initializedSource = initialized
        errorSource = loadError
        if (config != null && initialized != null && loadError != null) {
            sourceJob = scope.launch {
                combine(config, initialized, loadError) { _, _, _ -> Unit }
                    .collect { updateNetwork() }
            }
        }
        updateNetwork()
    }

    fun attach(
        token: Any,
        container: RemoteWebViewHost,
        initialUrl: String,
        backgroundColor: Int,
        launchPicker: (Long, android.content.Intent) -> Unit
    ) {
        val address = RemoteNavigationPolicy.classify(initialUrl)
        if (!address.isEmbedded) {
            mutableState.value = state.value.copy(
                page = state.value.page.failed(RemoteProblem("无法打开此网页", "Remote 只内嵌 HTTPS GitHub 页面。请使用系统浏览器打开其他网站。"))
            )
            return
        }
        homeUrl = address.url
        if (web == null && state.value.page.phase == RemoteLoadPhase.IDLE) recoveryUrl = homeUrl
        owner = token
        host = container
        background = backgroundColor
        uploads.launchPicker = launchPicker
        wrapper?.baseContext = container.context
        web?.let { attachView(it) }
        maybeCreate()
    }

    fun updateHost(token: Any, backgroundColor: Int, fontScale: Float, blocked: Boolean) {
        if (owner !== token) return
        background = backgroundColor
        host?.setBackgroundColor(backgroundColor)
        web?.apply {
            setBackgroundColor(backgroundColor)
            val zoom = (fontScale * 100).roundToInt().coerceAtLeast(1)
            if (settings.textZoom != zoom) settings.textZoom = zoom
            importantForAccessibility = if (blocked) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    fun resume(token: Any) {
        if (owner !== token) return
        resumed = true
        web?.onResume()
    }

    fun pause(token: Any) {
        if (owner !== token) return
        resumed = false
        web?.onPause()
        flushCookies()
    }

    fun detach(token: Any, changingConfiguration: Boolean = false) {
        if (owner !== token) return
        pause(token)
        uploads.detach(keepPending = changingConfiguration)
        popups.close()
        web?.let { (it.parent as? ViewGroup)?.removeView(it) }
        wrapper?.baseContext = appContext
        host = null
        owner = null
        mutableState.value = state.value.copy(externalRequest = null)
        // Keep the proxy lease while the retained document can still make requests.
    }

    fun activityDestroyed(activity: Activity) {
        if (host?.context?.findActivity() === activity) {
            owner?.let { detach(it, activity.isChangingConfigurations) }
        }
    }

    private fun readPreferences() {
        preferenceJob?.cancel()
        mutableState.value = state.value.copy(preferencesProblem = null)
        preferenceJob = scope.launch {
            try {
                settings.preferences.collect { preferences ->
                    val old = state.value
                    val recreate = old.preferencesReady &&
                        (old.preferences.networkMode != preferences.networkMode ||
                            old.preferences.desktop != preferences.desktop)
                    if (recreate) {
                        rememberLocation()
                        destroyView()
                        waitForUser = false
                        mutableState.value = state.value.copy(page = RemotePageState(url = recoveryUrl))
                    }
                    mutableState.value = state.value.copy(
                        preferences = preferences,
                        preferencesReady = true,
                        preferencesProblem = null
                    )
                    if (old.preferences.immersive != preferences.immersive) applyEnhancements()
                    updateNetwork(force = recreate)
                }
            } catch (_: IOException) {
                Logger.w("Remote: saved browser preferences could not be read")
                destroyView()
                mutableState.value = state.value.copy(
                    preferencesReady = false,
                    transportReady = false,
                    preferencesProblem = RemoteProblem("无法读取网页设置", "为避免使用错误的网络配置，Remote 已停止加载。请重试。")
                )
                proxy.request(RemoteProxyRoute.System)
            }
        }
    }

    fun savePreferences(preferences: RemotePreferences) {
        if (state.value.savingPreferences) return
        mutableState.value = state.value.copy(savingPreferences = true)
        scope.launch {
            try {
                settings.update(preferences)
            } catch (_: IOException) {
                Logger.w("Remote: browser preferences could not be saved")
                notify("网页设置保存失败，未切换网络或浏览模式。请重试。")
            } finally {
                mutableState.value = state.value.copy(savingPreferences = false)
            }
        }
    }

    private fun updateNetwork(force: Boolean = false) {
        if (!state.value.preferencesReady) return
        val next = RemoteNetworkPolicy.decide(
            state.value.preferences.networkMode,
            configSource?.value?.takeIf { initializedSource != null && errorSource != null },
            initializedSource?.value == true && errorSource != null,
            errorSource?.value,
            proxy.supported
        )
        if (!force && next == decision) return
        val previousRoute = (decision as? RemoteNetworkDecision.Ready)?.route
        val nextRoute = (next as? RemoteNetworkDecision.Ready)?.route
        if (web != null && previousRoute != nextRoute) {
            rememberLocation()
            destroyView()
            waitForUser = true
            mutableState.value = state.value.copy(
                page = state.value.page.released(
                    RemoteProblem("网络设置已变更", "为避免网页继续使用旧网络，页面已关闭。恢复时会重新加载，未发送的网页内容可能丢失。")
                )
            )
        }
        decision = next
        mutableState.value = when (next) {
            is RemoteNetworkDecision.Ready -> state.value.copy(
                transportReady = false, transportProblem = null, transportLabel = next.label
            )
            is RemoteNetworkDecision.Waiting -> state.value.copy(
                transportReady = false, transportProblem = null, transportLabel = next.label
            )
            is RemoteNetworkDecision.Blocked -> state.value.copy(
                transportReady = false, transportProblem = next.problem, transportLabel = "网络已暂停"
            )
        }
        proxy.request(if (waitForUser) RemoteProxyRoute.System else nextRoute ?: RemoteProxyRoute.System)
    }

    private fun proxyChanged(status: RemoteProxyStatus) {
        when (status) {
            RemoteProxyStatus.Applying -> mutableState.value = state.value.copy(transportReady = false)
            is RemoteProxyStatus.Ready -> {
                val ready = decision as? RemoteNetworkDecision.Ready ?: return
                if (ready.route != status.route || waitForUser || !state.value.preferencesReady) return
                mutableState.value = state.value.copy(transportReady = true, transportProblem = null)
                maybeCreate()
            }
            is RemoteProxyStatus.Failed -> {
                Logger.w("Remote: WebView proxy update ${if (status.timedOut) "timed out" else "failed"}")
                mutableState.value = state.value.copy(
                    transportReady = false,
                    transportProblem = RemoteProblem(
                        "未能确认 WebView 网络设置",
                        if (status.timedOut) "系统 WebView 尚未确认切换。页面不会联网；请更新 WebView 或重启应用后重试。"
                        else "代理设置未能应用。Remote 不会自动直连，请检查配置并重试。"
                    )
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun maybeCreate() {
        val target = host ?: return
        val current = state.value
        if (web != null || waitForUser || !current.preferencesReady || !current.transportReady ||
            current.clearingCookies || current.cookieProblem != null
        ) return
        val context = MutableContextWrapper(appContext).also {
            it.baseContext = target.context
            wrapper = it
        }
        val created = try {
            WebView(context)
        } catch (_: AndroidRuntimeException) {
            wrapper?.baseContext = appContext
            wrapper = null
            waitForUser = true
            mutableState.value = state.value.copy(
                page = current.page.released(RemoteProblem("系统 WebView 无法启动", "请启用或更新 Android System WebView，然后重试。"))
            )
            proxy.request(RemoteProxyRoute.System)
            return
        }
        web = created
        workerSettings = ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
            blockNetworkLoads = false
            allowFileAccess = false
            allowContentAccess = false
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        created.apply {
            setBackgroundColor(this@RemoteBrowserSession.background)
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(true)
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                offscreenPreRaster = false
                textZoom = (target.resources.configuration.fontScale * 100).roundToInt().coerceAtLeast(1)
                if (current.preferences.desktop) userAgentString = desktopUserAgent(userAgentString)
            }
            webData.configure(this)
            webViewClient = clients.navigation
            webChromeClient = clients.chrome
            setDownloadListener { url, _, _, _, _ -> requestExternal(url, download = true) }
        }
        attachView(created)
        if (resumed) created.onResume() else created.onPause()
        navigate(recoveryUrl)
    }

    private fun attachView(view: WebView) {
        val target = host ?: return
        if (view.parent === target) return
        (view.parent as? ViewGroup)?.removeView(view)
        target.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun owns(view: WebView): Boolean = web === view

    fun needsFreshNavigation(view: WebView): Boolean = owns(view) &&
        state.value.page.phase in setOf(RemoteLoadPhase.LOADING, RemoteLoadPhase.FAILED, RemoteLoadPhase.INTERRUPTED)

    fun isInterrupted(view: WebView): Boolean = owns(view) && state.value.page.phase == RemoteLoadPhase.INTERRUPTED

    fun navigate(url: String) {
        val address = RemoteNavigationPolicy.classify(url)
        if (!address.isEmbedded) {
            requestExternal(url)
            return
        }
        if (!state.value.transportReady || state.value.clearingCookies || state.value.cookieProblem != null) {
            notify("网页网络尚未准备好，请先处理当前提示。")
            return
        }
        recoveryUrl = address.url
        // A stopped WebView may still deliver a same-URL finish. A fresh instance is
        // the only callback identity the platform gives us for an interrupted retry.
        if (state.value.page.phase in setOf(RemoteLoadPhase.LOADING, RemoteLoadPhase.FAILED, RemoteLoadPhase.INTERRUPTED)) {
            destroyView()
            mutableState.value = state.value.copy(page = RemotePageState(url = address.url))
        }
        val view = web
        if (view == null) {
            waitForUser = false
            maybeCreate()
        } else {
            mutableState.value = state.value.copy(page = state.value.page.started(address.url))
            loadDocument(view, address.url)
            scheduleLoadTimeout(view)
        }
    }

    fun retry() {
        if (state.value.clearingCookies) return
        if (state.value.cookieProblem != null) {
            logout()
            return
        }
        if (!state.value.preferencesReady) {
            readPreferences()
            return
        }
        waitForUser = false
        if (web != null && state.value.transportReady) {
            navigate(recoveryUrl)
        } else {
            updateNetwork(force = true)
        }
    }

    fun home() = navigate(homeUrl)

    fun stop() {
        val view = web ?: return
        if (state.value.page.phase != RemoteLoadPhase.LOADING) return
        loadTimeout?.cancel()
        mutableState.value = state.value.copy(page = state.value.page.interrupted())
        view.stopLoading()
    }

    fun back() {
        val view = web ?: return
        if (!view.canGoBack()) return
        val history = view.copyBackForwardList()
        val url = history.getItemAtIndex(history.currentIndex - 1)?.url ?: return
        if (!RemoteNavigationPolicy.classify(url).isEmbedded) {
            rejectDocument(view, url)
            return
        }
        navigationRequested(view, url)
        view.goBack()
    }

    fun navigationRequested(view: WebView, url: String) {
        if (!owns(view)) return
        recoveryUrl = url
        mutableState.value = state.value.copy(page = state.value.page.started(url))
        scheduleLoadTimeout(view)
    }

    fun mainFrameRequested(view: WebView, url: String) {
        if (!owns(view) || isInterrupted(view)) return
        if (state.value.page.phase != RemoteLoadPhase.LOADING || state.value.page.url != url) {
            navigationRequested(view, url)
        }
    }

    fun pageStarted(view: WebView, url: String?) {
        if (!owns(view)) return
        val address = RemoteNavigationPolicy.classify(url)
        if (!address.isEmbedded) {
            rejectDocument(view, url.orEmpty())
            return
        }
        recoveryUrl = address.url
        mutableState.value = state.value.copy(
            page = state.value.page.frameStarted(address.url).copy(canGoBack = view.canGoBack())
        )
        if (state.value.page.phase == RemoteLoadPhase.LOADING) scheduleLoadTimeout(view)
    }

    fun pageCommitted(view: WebView, url: String?) {
        if (!owns(view) || url == null) return
        val address = RemoteNavigationPolicy.classify(url)
        if (!address.isEmbedded) {
            rejectDocument(view, url)
            return
        }
        mutableState.value = state.value.copy(page = state.value.page.committed(address.url))
        if (state.value.page.phase == RemoteLoadPhase.READY) loadTimeout?.cancel()
        applyEnhancements()
    }

    fun pageFinished(view: WebView, url: String?) {
        if (!owns(view) || url == null) return
        val address = RemoteNavigationPolicy.classify(url)
        if (!address.isEmbedded) {
            rejectDocument(view, url)
            return
        }
        val page = state.value.page.finished(address.url)
        mutableState.value = state.value.copy(page = page.copy(canGoBack = view.canGoBack()))
        if (page.phase == RemoteLoadPhase.READY) loadTimeout?.cancel()
        applyEnhancements()
    }

    fun progressChanged(view: WebView, progress: Int) {
        if (!owns(view)) return
        mutableState.value = state.value.copy(page = state.value.page.progressed(progress))
    }

    fun historyChanged(view: WebView, url: String?) {
        if (!owns(view)) return
        val address = RemoteNavigationPolicy.classify(url)
        if (!address.isEmbedded) {
            rejectDocument(view, url.orEmpty())
            return
        }
        recoveryUrl = address.url
        mutableState.value = state.value.copy(
            page = state.value.page.copy(url = address.url, canGoBack = view.canGoBack())
        )
        applyEnhancements()
    }

    fun pageFailed(view: WebView, problem: RemoteProblem, url: String = state.value.page.url) {
        if (!owns(view) || state.value.page.phase == RemoteLoadPhase.INTERRUPTED) return
        val address = RemoteNavigationPolicy.classify(url)
        if (!address.isEmbedded) {
            rejectDocument(view, url)
            return
        }
        loadTimeout?.cancel()
        Logger.w("Remote: main document failed")
        recoveryUrl = address.url
        mutableState.value = state.value.copy(page = state.value.page.failedFor(address.url, problem))
    }

    private fun scheduleLoadTimeout(view: WebView) {
        loadTimeout?.cancel()
        val id = ++loadSequence
        loadTimeout = scope.launch {
            delay(60_000)
            if (owns(view) && loadSequence == id && state.value.page.phase == RemoteLoadPhase.LOADING) {
                pageFailed(view, RemoteProblem("网页加载时间过长", "连接已停止。可以检查网络后重试，或在系统浏览器中继续。"))
                view.stopLoading()
            }
        }
    }

    fun interceptNavigation(view: WebView, url: String) {
        if (!owns(view)) return
        if (state.value.page.phase == RemoteLoadPhase.LOADING) {
            loadTimeout?.cancel()
            mutableState.value = state.value.copy(page = state.value.page.interrupted())
            view.stopLoading()
        }
        requestExternal(url)
    }

    private fun rejectDocument(view: WebView, url: String) {
        if (!owns(view)) return
        val address = RemoteNavigationPolicy.classify(url)
        recoveryUrl = state.value.page.committedUrl?.takeIf {
            RemoteNavigationPolicy.classify(it).isEmbedded
        } ?: homeUrl
        destroyView()
        waitForUser = true
        mutableState.value = state.value.copy(
            transportReady = false,
            page = state.value.page.copy(url = address.url).released(
                RemoteProblem("已阻止外部网页", "此跳转不属于受信任的 GitHub 网页，已关闭页面。可以恢复上一个网页，或确认后在系统浏览器中打开。")
            )
        )
        proxy.request(RemoteProxyRoute.System)
        requestExternal(url)
    }

    fun requestExternal(url: String, download: Boolean = false) {
        val address = RemoteNavigationPolicy.classify(url)
        if (address.destination == RemoteDestination.BLOCKED) {
            notify(if (download) "该下载是临时或不受支持的链接，Remote 无法交给浏览器。请在系统浏览器中打开 Copilot 后下载。"
            else "已阻止不受支持的网页地址。Remote 不会打开本地文件、明文 HTTP 或应用指令链接。")
            return
        }
        if (host == null) {
            notify("页面离开后请求了外部跳转，已取消。")
            return
        }
        if (state.value.externalRequest != null) return
        mutableState.value = state.value.copy(
            externalRequest = RemoteExternalRequest(address.url, address.origin, download)
        )
    }

    fun dismissExternal() {
        mutableState.value = state.value.copy(externalRequest = null)
    }

    fun openPopup(view: WebView, userGesture: Boolean, result: Message): Boolean {
        if (!owns(view) || host == null) return false
        return popups.open(userGesture, result)
    }

    private fun navigatePopup(url: String) {
        if (host == null) return
        if (RemoteNavigationPolicy.classify(url).isEmbedded) navigate(url)
        else requestExternal(url)
    }

    private fun applyEnhancements() {
        val view = web ?: return
        if (!RemoteNavigationPolicy.classify(view.url).isEmbedded) return
        view.evaluateJavascript(RemotePageScripts.enhancements(state.value.preferences.immersive), null)
    }

    fun rendererGone(view: WebView, crashed: Boolean) {
        if (!owns(view)) return
        Logger.w("Remote: renderer ${if (crashed) "crashed" else "was reclaimed"}")
        destroyView(rendererGone = true)
        waitForUser = true
        mutableState.value = state.value.copy(
            transportReady = false,
            page = state.value.page.released(
                RemoteProblem("网页进程已关闭", "点击恢复页面以重新加载。网页登录通常会保留，但未发送的网页内容可能丢失。")
            )
        )
        proxy.request(RemoteProxyRoute.System)
    }

    private fun rememberLocation() {
        val address = RemoteNavigationPolicy.classify(web?.url ?: state.value.page.url)
        if (address.isEmbedded) recoveryUrl = address.url
    }

    private fun destroyView(rendererGone: Boolean = false) {
        workerSettings?.blockNetworkLoads = true
        loadTimeout?.cancel()
        uploads.cancel()
        popups.close()
        val old = web
        web = null
        try {
            if (old != null) {
                (old.parent as? ViewGroup)?.removeView(old)
                if (!rendererGone) {
                    old.setDownloadListener(null)
                    old.webChromeClient = null
                    old.webViewClient = WebViewClient()
                    old.stopLoading()
                    old.onPause()
                }
                old.destroy()
            }
        } finally {
            wrapper?.baseContext = appContext
            wrapper = null
        }
    }

    fun logout() {
        if (state.value.clearingCookies) return
        val id = ++logoutSequence
        mutableState.value = state.value.copy(clearingCookies = true, cookieProblem = null, externalRequest = null)
        cookieTimeout?.cancel()
        cookieTimeout = scope.launch {
            delay(15_000)
            if (id == logoutSequence && state.value.clearingCookies) {
                Logger.w("Remote: waiting for browser logout confirmation")
                mutableState.value = state.value.copy(
                    cookieProblem = RemoteProblem("网页登录数据清理尚未完成", "正在等待系统 WebView 确认。页面不会重新联网；如果长时间无响应，请重启应用后再试。")
                )
            }
        }
        try {
            cookieBarrier.begin(
                stopPage = {
                    destroyView()
                    recoveryUrl = homeUrl
                    waitForUser = false
                    mutableState.value = state.value.copy(page = RemotePageState(url = homeUrl))
                },
                removeCookies = webData::removeCookies,
                afterRemoval = { clearWebStorage(id) }
            )
        } catch (_: AndroidRuntimeException) {
            cookieFailed()
        } catch (_: IllegalStateException) {
            cookieFailed()
        } catch (_: UnsupportedOperationException) {
            cookieFailed()
        }
    }

    private fun clearWebStorage(id: Long) {
        if (id != logoutSequence) return
        try {
            webData.clearStorage { complete -> finishLogout(id, completeStorage = complete) }
        } catch (_: AndroidRuntimeException) {
            cookieFailed()
        } catch (_: IllegalStateException) {
            cookieFailed()
        } catch (_: UnsupportedOperationException) {
            cookieFailed()
        }
    }

    private fun finishLogout(id: Long, completeStorage: Boolean) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { webData.flush() }
                if (id != logoutSequence) return@launch
                cookieTimeout?.cancel()
                mutableState.value = state.value.copy(clearingCookies = false, cookieProblem = null)
                notify(
                    if (completeStorage) "已清除 Remote 网页登录数据；应用内的 GitHub 登录未改变。"
                    else "已清除网页登录 Cookie；旧版 WebView 无法确认全部网页存储已清除，建议更新 WebView。原生登录未改变。"
                )
                updateNetwork(force = true)
            } catch (_: AndroidRuntimeException) {
                cookieFailed()
            } catch (_: IllegalStateException) {
                cookieFailed()
            }
        }
    }

    private fun cookieFailed() {
        logoutSequence++
        cookieBarrier.invalidate()
        cookieTimeout?.cancel()
        Logger.w("Remote: browser logout did not complete")
        mutableState.value = state.value.copy(
            clearingCookies = false,
            cookieProblem = RemoteProblem("未能确认网页登录数据已清除", "页面不会自动重新加载。请重试清除，或更新系统 WebView 后重试。")
        )
    }

    private fun flushCookies() {
        if (web == null) return
        scope.launch(Dispatchers.IO) {
            try {
                webData.flush()
            } catch (_: AndroidRuntimeException) {
                withContext(Dispatchers.Main) { notify("网页登录状态未能保存，下次打开可能需要重新登录。") }
                Logger.w("Remote: cookie persistence failed")
            }
        }
    }

    fun notify(message: String) {
        mutableState.value = state.value.copy(notice = RemoteNotice(++noticeSequence, message))
    }

    fun consumeNotice(id: Long) {
        if (state.value.notice?.id == id) mutableState.value = state.value.copy(notice = null)
    }

    private fun releaseForMemoryPressure() {
        if (host != null || web == null) return
        rememberLocation()
        destroyView()
        waitForUser = true
        mutableState.value = state.value.copy(
            transportReady = false,
            page = state.value.page.released(
                RemoteProblem("网页已释放以节省内存", "点击恢复页面以重新加载。未发送的网页内容可能丢失，应用内聊天不受影响。")
            )
        )
        proxy.request(RemoteProxyRoute.System)
    }

    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) releaseForMemoryPressure()
    }

    override fun onLowMemory() = releaseForMemoryPressure()
    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    fun dispose() {
        owner?.let { detach(it) }
        destroyView()
        logoutSequence++
        cookieBarrier.invalidate()
        proxy.request(RemoteProxyRoute.System)
        appContext.unregisterComponentCallbacks(this)
        scope.cancel()
    }
}

internal fun desktopUserAgent(mobile: String): String = mobile
    .replace(Regex("\\([^)]*Android[^)]*\\)"), "(X11; Linux x86_64)")
    .replace(" Version/4.0", "")
    .replace(" Mobile ", " ")

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}

internal object RemoteBrowserStore {
    private var browser: RemoteBrowserSession? = null

    fun get(context: Context): RemoteBrowserSession =
        browser ?: RemoteBrowserSession(context.applicationContext).also { browser = it }

    fun activityDestroyed(activity: Activity) {
        browser?.activityDestroyed(activity)
    }
}
