package com.tongxie.copilotgo.ui.screens

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tongxie.copilotgo.BuildConfig
import com.tongxie.copilotgo.data.Constants

// 内嵌 Copilot 的「专属深色」配色（对齐 GitHub 深色画布），与应用浅色主题无关，
// 保证顶/底系统栏区域与网页深色内容连成一片，避免刺眼白边。
private val EmbedBg = Color(0xFF0D1117)
private val EmbedBarBg = Color(0xFF161B22)
private val EmbedOnBg = Color(0xFFE6EDF3)
private val EmbedOnBgDim = Color(0xFF8B949E)

/**
 * Remote 模式：把官方 web Copilot（github.com/copilot）以「原生内嵌」的姿态呈现，
 * 而非粗糙的浏览器壳。为达到完美嵌入观感，做了以下处理（参考主流 App 内嵌 WebView 方案）：
 *
 * - 固定品牌标题「Copilot」，不随网页 <title> 抖动（浏览器感的最大来源）。
 * - 顶栏自动隐藏：向下滚动收起、向上滚动/到顶展开，像主流 App 的沉浸阅读，不再占一大块。
 * - 深色一体化：顶/底栏与系统栏区域统一深色，消除浅色主题下刺眼的白边。
 * - 键盘自适配：本页启用系统原生 ADJUST_RESIZE，弹出输入法时 OS 平滑缩放窗口，
 *   Chromium 自动把焦点输入框滚入可见区——不卡顿、不错位（不再用 Compose 逐帧 imePadding）。
 * - 沉浸模式：注入 CSS 隐藏 GitHub 全局顶栏/页脚，只留 Copilot 主体（可在菜单关闭）。
 * - 下拉刷新（SwipeRefreshLayout），原生手势。首屏品牌化 Loading，消除白屏闪烁。
 * - 登录态：CookieManager 持久化（含第三方 cookie），onPause/销毁时 flush 落盘。
 * - 文件上传桥接、外链交系统、下载交系统、主框架错误重试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RemoteWebViewScreen(
    onBack: () -> Unit,
    homeUrl: String = Constants.REMOTE_HOME_URL,
    title: String = "Copilot"
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val bgArgb = EmbedBg.toArgb()

    // 若 WebView 已在后台保活（从会话列表返回再进入），不再显示首屏 Loading，
    // 直接呈现已加载好的页面（不重载、不闪烁）。
    val resumedSession = RemoteWebHolder.isAlive

    var progress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(!resumedSession) }
    var firstLoad by remember { mutableStateOf(!resumedSession) }
    var canGoBack by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(false) }
    var immersive by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var barVisible by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var injectorsAppliedForLoad by remember { mutableStateOf(resumedSession) }

    fun applyPageInjectorsOnce(v: WebView) {
        if (!injectorsAppliedForLoad) {
            applyImmersive(v, immersive)
            applyEnterAsNewline(v)
            injectorsAppliedForLoad = true
        }
    }

    // 文件选择回调桥（WebChromeClient 在 factory 里创建，需用稳定 holder 与 Compose launcher 通信）
    val pendingFileCallback = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingFileCallback.value
        pendingFileCallback.value = null
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        cb?.onReceiveValue(uris)
    }

    // WebView 进程级单例：跨「退出会话列表 → 再进入」保活，不销毁、不重载，保留登录态、
    // 页面、滚动位置与 JS 状态。容器仍用 SwipeRefreshLayout 承载（下拉刷新手势禁用，
    // 刷新改由顶栏菜单触发）。基础 Context 用 MutableContextWrapper，附着时指向当前 Activity。
    val swipeRefresh = remember {
        RemoteWebHolder.acquire(context, homeUrl, bgArgb, BuildConfig.DEBUG)
    }
    val webView = RemoteWebHolder.web!!

    // 把本次进入所需、依赖 Compose 状态/启动器的回调挂到保活单例上；离开时清空回调并把
    // WebView 从父容器摘下、Context 回退到 application，保证下次可重新 attach 且不泄漏 Activity。
    DisposableEffect(Unit) {
        RemoteWebHolder.onScroll = { scrollY, dy ->
            barVisible = when {
                scrollY <= 4 -> true
                dy > 6 -> false
                dy < -6 -> true
                else -> barVisible
            }
        }
        RemoteWebHolder.onPageStarted = { v ->
            injectorsAppliedForLoad = false
            isLoading = true
            loadError = null
            canGoBack = v.canGoBack()
        }
        RemoteWebHolder.onPageFinished = { v ->
            isLoading = false
            firstLoad = false
            canGoBack = v.canGoBack()
            applyPageInjectorsOnce(v)
        }
        RemoteWebHolder.onHistory = { v -> canGoBack = v.canGoBack() }
        RemoteWebHolder.onMainError = { msg ->
            loadError = msg
            isLoading = false
            firstLoad = false
        }
        RemoteWebHolder.onProgress = { p ->
            progress = p / 100f
            // 进度推进时尽早注入 CSS 与回车改换行，减少顶栏闪现并尽快让换行键生效
            if (p >= 60) {
                applyPageInjectorsOnce(webView)
            }
        }
        RemoteWebHolder.onFileChooser = fc@{ callback, intent ->
            pendingFileCallback.value?.onReceiveValue(null)
            pendingFileCallback.value = callback
            return@fc try {
                fileChooserLauncher.launch(intent)
                true
            } catch (e: ActivityNotFoundException) {
                pendingFileCallback.value = null
                false
            }
        }
        RemoteWebHolder.onOpenExternal = { intent ->
            runCatching { context.startActivity(intent) }
        }
        onDispose {
            CookieManager.getInstance().flush()
            pendingFileCallback.value?.onReceiveValue(null)
            pendingFileCallback.value = null
            RemoteWebHolder.release(context.applicationContext)
        }
    }
    LaunchedEffect(immersive) {
        applyImmersive(webView, immersive)
    }

    // 本页用「系统原生窗口适配」：decorFitsSystemWindows=true + ADJUST_RESIZE。
    // 键盘弹出时由系统缩小窗口内容区 → Compose 的 weight(1f) WebView 随之变矮 →
    // github.com/copilot 自身是响应式布局，底部输入框会自动浮到键盘上方（可见可编辑）。
    // 这是 WebView 处理软键盘最稳的原生方案；本页没有用 imePadding，不存在逐帧重排卡顿。
    // 同时直接给系统状态栏/导航栏上深色，保持一体化观感。
    //
    // 关键：以上都是 Activity「全局」窗口状态。若只在 onDispose 里还原，返回会话列表时，
    // 列表会先用本页的窗口配置渲染一帧（状态栏被染成深色 EmbedBarBg + decorFitsSystemWindows=true
    // 多出状态栏占位）→ 顶部闪出一条黑边并触发列表重排。因此把还原提取成 restoreImmersiveWindow()，
    // 在退出回调里「先同步还原、再导航」，让会话列表第一帧就是干净的边到边状态；onDispose 仅作幂等兜底。
    val activity = context as? android.app.Activity
    val appWindow = activity?.window
    val insetsController = appWindow?.let { WindowCompat.getInsetsController(it, view) }
    val savedWindow = remember { mutableStateOf<SavedWindowState?>(null) }
    val windowRestored = remember { mutableStateOf(false) }

    fun restoreImmersiveWindow() {
        if (windowRestored.value) return
        val saved = savedWindow.value ?: return
        if (appWindow != null) {
            WindowCompat.setDecorFitsSystemWindows(appWindow, false)
            @Suppress("DEPRECATION") run {
                saved.statusColor?.let { appWindow.statusBarColor = it }
                saved.navColor?.let { appWindow.navigationBarColor = it }
            }
            saved.softInput?.let { appWindow.setSoftInputMode(it) }
        }
        if (insetsController != null) {
            saved.lightStatus?.let { insetsController.isAppearanceLightStatusBars = it }
            saved.lightNav?.let { insetsController.isAppearanceLightNavigationBars = it }
        }
        windowRestored.value = true
    }

    DisposableEffect(Unit) {
        if (appWindow != null) {
            @Suppress("DEPRECATION")
            savedWindow.value = SavedWindowState(
                lightStatus = insetsController?.isAppearanceLightStatusBars,
                lightNav = insetsController?.isAppearanceLightNavigationBars,
                statusColor = appWindow.statusBarColor,
                navColor = appWindow.navigationBarColor,
                softInput = appWindow.attributes?.softInputMode
            )
            WindowCompat.setDecorFitsSystemWindows(appWindow, true)
            @Suppress("DEPRECATION") run { appWindow.statusBarColor = EmbedBarBg.toArgb() }
            @Suppress("DEPRECATION") run { appWindow.navigationBarColor = EmbedBg.toArgb() }
            appWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            insetsController?.isAppearanceLightStatusBars = false
            insetsController?.isAppearanceLightNavigationBars = false
            windowRestored.value = false
        }
        onDispose { restoreImmersiveWindow() }
    }

    // 生命周期：转发 onPause/onResume，并在 pause 时把 cookie 落盘
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webView.onPause()
                    CookieManager.getInstance().flush()
                }
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 退出到会话列表前，先同步还原全局窗口状态，再导航——避免列表顶部闪黑边/重排。
    val exitToList = {
        restoreImmersiveWindow()
        onBack()
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else exitToList()
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出网页登录") },
            text = { Text("将清除本页的 GitHub 登录 cookie 与本地存储，下次需重新登录。确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies(null)
                    cm.flush()
                    WebStorage.getInstance().deleteAllData()
                    webView.clearHistory()
                    firstLoad = true
                    webView.loadUrl(homeUrl)
                }) { Text("清除并退出") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EmbedBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 注意：本页 decorFitsSystemWindows=true，系统已自动把内容下移到状态栏「下方」，
            // 且状态栏本身已染成 EmbedBarBg（见原生窗口适配 DisposableEffect）。因此这里
            // 不能再额外补一段「状态栏高度」的占位条，否则会在顶部多叠加一整个状态栏高度的
            // 深色块（顶栏看着变细了、总高度却没降）。直接让纤细顶栏紧贴系统状态栏下方即可。
            // 纤细顶栏：在 Column 布局流内，固定在状态栏正下方、网页内容区「上方」。
            // 因为它占据自己的布局高度（而非覆盖在网页上），WebView 从它「下方」开始，
            // 网页（含 Copilot 自身顶部菜单）永远不会被遮挡。固定显示、无高度动画，避免
            // WebView 反复重排造成的卡顿。错误页时隐藏顶栏（保持与原行为一致）。
            if (loadError == null) {
                Surface(color = EmbedBarBg) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { exitToList() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "退出到首页",
                                tint = EmbedOnBg,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = EmbedOnBg,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { menuOpen = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "更多",
                                tint = EmbedOnBg,
                                modifier = Modifier.size(20.dp)
                            )
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("刷新") },
                                    onClick = {
                                        menuOpen = false
                                        webView.reload()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("回到 Copilot 首页") },
                                    onClick = {
                                        menuOpen = false
                                        webView.loadUrl(homeUrl)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (immersive) "沉浸模式：开" else "沉浸模式：关") },
                                    onClick = {
                                        menuOpen = false
                                        immersive = !immersive
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (desktopMode) "切换到移动版" else "切换到桌面版") },
                                    onClick = {
                                        menuOpen = false
                                        desktopMode = !desktopMode
                                        webView.settings.userAgentString =
                                            if (desktopMode) Constants.DESKTOP_USER_AGENT else null
                                        webView.reload()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("用系统浏览器打开") },
                                    onClick = {
                                        menuOpen = false
                                        val url = webView.url ?: homeUrl
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            )
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("退出网页登录（清 cookie）") },
                                    onClick = {
                                        menuOpen = false
                                        showLogoutDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
                if (isLoading && !firstLoad && progress > 0f && progress < 1f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = EmbedOnBg,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    factory = {
                        swipeRefresh.apply {
                            setBackgroundColor(bgArgb)
                            webView.setBackgroundColor(bgArgb)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = {
                        it.setBackgroundColor(bgArgb)
                        webView.setBackgroundColor(bgArgb)
                    }
                )

                val err = loadError
                when {
                    err != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(EmbedBg)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                err,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFF85149)
                            )
                            Text(
                                "请检查网络（github.com 是否可访问），然后重试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = EmbedOnBgDim,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            TextButton(
                                onClick = {
                                    loadError = null
                                    firstLoad = true
                                    webView.reload()
                                },
                                modifier = Modifier.padding(top = 8.dp)
                            ) { Text("重试") }
                        }
                    }
                    // 首屏品牌化 Loading：深色背景 + 转圈，消除 WebView 白屏闪烁
                    firstLoad -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(EmbedBg)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = EmbedOnBg,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                "正在连接 Copilot…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EmbedOnBgDim,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 进入 Remote 页前的 Activity 全局窗口状态快照，用于离开时同步还原（避免外部页面顶部闪黑边/重排）。 */
private data class SavedWindowState(
    val lightStatus: Boolean?,
    val lightNav: Boolean?,
    val statusColor: Int?,
    val navColor: Int?,
    val softInput: Int?
)

/**
 * 进程级单例：保活内嵌 Copilot 的 WebView，使「退出会话列表 → 再进入」时不重建、不重载，
 * 保留登录态、页面、滚动位置与 JS 运行状态。WebView 以 [MutableContextWrapper] 作为基础 Context：
 * 附着到界面时指向当前 Activity，离开时回退到 applicationContext，避免泄漏已销毁的 Activity
 * （主流「保活 WebView」标准做法）。与界面相关、依赖 Compose 状态/启动器的回调通过可空字段
 * 在每次进入时注入、离开时清空，从而把长生命周期的 WebView 与短生命周期的 Composable 解耦。
 */
private object RemoteWebHolder {
    private var wrapper: MutableContextWrapper? = null
    private var webView: WebView? = null
    private var swipe: SwipeRefreshLayout? = null

    val web: WebView? get() = webView
    val isAlive: Boolean get() = webView != null

    // 每次进入时注入、离开时清空的界面回调
    var onScroll: ((scrollY: Int, dy: Int) -> Unit)? = null
    var onPageStarted: ((WebView) -> Unit)? = null
    var onPageFinished: ((WebView) -> Unit)? = null
    var onHistory: ((WebView) -> Unit)? = null
    var onMainError: ((String) -> Unit)? = null
    var onProgress: ((Int) -> Unit)? = null
    var onFileChooser: ((ValueCallback<Array<Uri>>, Intent) -> Boolean)? = null
    var onOpenExternal: ((Intent) -> Unit)? = null

    fun acquire(activity: Context, homeUrl: String, bgArgb: Int, debug: Boolean): SwipeRefreshLayout {
        val w = wrapper ?: MutableContextWrapper(activity).also { wrapper = it }
        w.baseContext = activity
        if (webView == null) {
            val wv = createWebView(w, bgArgb, debug)
            webView = wv
            swipe = SwipeRefreshLayout(w).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(bgArgb)
                // 禁用下拉刷新手势（上滑误触很烦）；刷新改由顶栏菜单触发。
                isEnabled = false
                addView(
                    wv,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            wv.loadUrl(homeUrl)
        }
        return swipe!!
    }

    fun release(appContext: Context) {
        onScroll = null
        onPageStarted = null
        onPageFinished = null
        onHistory = null
        onMainError = null
        onProgress = null
        onFileChooser = null
        onOpenExternal = null
        (swipe?.parent as? ViewGroup)?.removeView(swipe)
        wrapper?.baseContext = appContext
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: Context, bgArgb: Int, debug: Boolean): WebView {
        return WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusableInTouchMode = true
            setBackgroundColor(bgArgb)

            // 顶栏自动隐藏：根据滚动方向收起/展开；接近顶部时始终展开。
            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                onScroll?.invoke(scrollY, scrollY - oldScrollY)
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setOffscreenPreRaster(true)
                }
            }

            if (debug) {
                WebView.setWebContentsDebuggingEnabled(true)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url
                    val scheme = url.scheme?.lowercase()
                    return if (scheme == "http" || scheme == "https") {
                        false
                    } else {
                        onOpenExternal?.invoke(
                            Intent(Intent.ACTION_VIEW, url).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                        true
                    }
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    onPageStarted?.invoke(view)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    onPageFinished?.invoke(view)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    onHistory?.invoke(view)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    // 只为主框架失败展示错误页，忽略子资源（图片/分析脚本等）失败
                    if (request.isForMainFrame) {
                        onMainError?.invoke("加载失败：${error.description}（错误码 ${error.errorCode}）")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame) {
                        val reason = errorResponse.reasonPhrase
                            ?.takeIf { it.isNotBlank() }
                            ?.let { " $it" }
                            .orEmpty()
                        onMainError?.invoke("加载失败：HTTP ${errorResponse.statusCode}$reason")
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    onProgress?.invoke(newProgress)
                }

                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    val handler = onFileChooser ?: return false
                    return handler.invoke(filePathCallback, fileChooserParams.createIntent())
                }
            }

            // 下载交给系统（下载管理器 / 浏览器），WebView 自身不处理文件落地
            setDownloadListener { url, _, _, _, _ ->
                onOpenExternal?.invoke(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }
}

/**
 * 沉浸模式：注入/移除一段 CSS，隐藏 GitHub 全局顶栏与页脚，让内嵌的 Copilot 像专属页面。
 * 选择器命中失败时安全无副作用（什么都不隐藏）。
 */
private fun applyImmersive(webView: WebView, enabled: Boolean) {
    val js = """
        (function(){
          var ID='cg-immersive-style';
          var CSS='.AppHeader,header.AppHeader,.js-header-wrapper,.footer,footer.footer{display:none!important;} body{padding-top:0!important;}';
          function ensure(){
            if(!document.getElementById(ID)){
              var s=document.createElement('style');
              s.id=ID;
              s.textContent=CSS;
              (document.head||document.documentElement).appendChild(s);
            }
          }
          var ex=document.getElementById(ID);
          if(${enabled}){
            ensure();
            if(!window.__cgImmersiveObserver && window.MutationObserver){
              window.__cgImmersiveObserver=new MutationObserver(ensure);
              window.__cgImmersiveObserver.observe(document.documentElement,{childList:true,subtree:true});
            }
          } else {
            if(window.__cgImmersiveObserver){
              window.__cgImmersiveObserver.disconnect();
              window.__cgImmersiveObserver=null;
            }
            if(ex){
              ex.parentNode.removeChild(ex);
            }
          }
        })();
    """.trimIndent()
    runCatching { webView.evaluateJavascript(js, null) }
}

/**
 * 让软键盘「回车/换行」键插入换行，而不是直接发送消息。
 * 在 window 捕获阶段（最先于页面自身的监听）拦截无修饰键的 Enter：
 * - textarea：在光标处手动插入 "\n" 并派发 input 事件；
 * - contenteditable（富文本编辑器，如 Copilot 输入框）：execCommand('insertLineBreak') 触发软换行。
 * 发送改由页面自带的「发送」按钮完成。监听只绑定一次（按文档去重），SPA 切换页面也持续有效。
 */
private fun applyEnterAsNewline(webView: WebView) {
    val js = """
        (function(){
          if (window.__cgEnterNewline) return;
          window.__cgEnterNewline = true;
          window.addEventListener('keydown', function(e){
            if (e.key !== 'Enter' && e.keyCode !== 13) return;
            if (e.shiftKey || e.ctrlKey || e.metaKey || e.altKey || e.isComposing) return;
            var el = e.target;
            if (!el) return;
            var tag = (el.tagName || '').toLowerCase();
            var editable = !!el.isContentEditable;
            if (tag !== 'textarea' && !editable) return;
            e.preventDefault();
            e.stopPropagation();
            if (typeof e.stopImmediatePropagation === 'function') e.stopImmediatePropagation();
            if (tag === 'textarea') {
              var start = el.selectionStart, end = el.selectionEnd, v = el.value || '';
              el.value = v.slice(0, start) + '\n' + v.slice(end);
              el.selectionStart = el.selectionEnd = start + 1;
              try { el.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertLineBreak'})); }
              catch (err) { el.dispatchEvent(new Event('input', {bubbles:true})); }
            } else {
              var ok = false;
              try { ok = document.execCommand('insertLineBreak'); } catch (err) {}
              if (!ok) { try { document.execCommand('insertText', false, '\n'); } catch (err2) {} }
            }
          }, true);
        })();
    """.trimIndent()
    runCatching { webView.evaluateJavascript(js, null) }
}
