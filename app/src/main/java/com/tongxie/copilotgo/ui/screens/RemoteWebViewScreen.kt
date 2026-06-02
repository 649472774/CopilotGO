package com.tongxie.copilotgo.ui.screens

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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

    var progress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var firstLoad by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(false) }
    var immersive by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var barVisible by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }

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

    // WebView 只创建一次，跨重组稳定
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusableInTouchMode = true
            setBackgroundColor(bgArgb)

            // 顶栏自动隐藏：根据滚动方向收起/展开；接近顶部时始终展开。
            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                val dy = scrollY - oldScrollY
                barVisible = when {
                    scrollY <= 4 -> true
                    dy > 6 -> false
                    dy < -6 -> true
                    else -> barVisible
                }
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
            }

            if (BuildConfig.DEBUG) {
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
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                        true
                    }
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    isLoading = true
                    loadError = null
                    canGoBack = view.canGoBack()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    isLoading = false
                    firstLoad = false
                    canGoBack = view.canGoBack()
                    applyImmersive(view, immersive)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    canGoBack = view.canGoBack()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    // 只为主框架失败展示错误页，忽略子资源（图片/分析脚本等）失败
                    if (request.isForMainFrame) {
                        loadError = "加载失败：${error.description}（错误码 ${error.errorCode}）"
                        isLoading = false
                        firstLoad = false
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress / 100f
                    // 进度推进时尽早注入 CSS，减少顶栏闪现
                    if (newProgress >= 60) applyImmersive(view, immersive)
                }

                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    pendingFileCallback.value?.onReceiveValue(null)
                    pendingFileCallback.value = filePathCallback
                    return try {
                        fileChooserLauncher.launch(fileChooserParams.createIntent())
                        true
                    } catch (e: ActivityNotFoundException) {
                        pendingFileCallback.value = null
                        false
                    }
                }
            }

            // 下载交给系统（下载管理器 / 浏览器），WebView 自身不处理文件落地
            setDownloadListener { url, _, _, _, _ ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }

            loadUrl(homeUrl)
        }
    }

    // 下拉刷新容器：包裹 WebView，原生手势刷新
    val swipeRefresh = remember {
        SwipeRefreshLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                webView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnRefreshListener {
                refreshing = true
                webView.reload()
            }
        }
    }

    // 页面加载完成后停止下拉刷新的转圈
    LaunchedEffect(isLoading) {
        if (!isLoading) refreshing = false
    }
    // 沉浸模式切换时即时重注入/移除 CSS
    LaunchedEffect(immersive) {
        applyImmersive(webView, immersive)
    }

    // 本页改用「系统原生窗口适配」：decorFitsSystemWindows=true + ADJUST_RESIZE，
    // 让 OS 在键盘弹出时一次性平滑缩放窗口、由 Chromium 自动把输入框滚入可见区，
    // 彻底避免 Compose 逐帧 imePadding 触发 WebView 重排导致的严重卡顿（动画卡 5 秒、错位）。
    // 同时直接给系统状态栏/导航栏上深色，保持一体化观感。离开本页时全部还原。
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousLightStatus = controller?.isAppearanceLightStatusBars
        val previousLightNav = controller?.isAppearanceLightNavigationBars
        @Suppress("DEPRECATION") val previousStatusColor = window?.statusBarColor
        @Suppress("DEPRECATION") val previousNavColor = window?.navigationBarColor
        val previousSoftInput = window?.attributes?.softInputMode

        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            @Suppress("DEPRECATION") run { window.statusBarColor = EmbedBarBg.toArgb() }
            @Suppress("DEPRECATION") run { window.navigationBarColor = EmbedBg.toArgb() }
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false

        onDispose {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                @Suppress("DEPRECATION") run {
                    if (previousStatusColor != null) window.statusBarColor = previousStatusColor
                    if (previousNavColor != null) window.navigationBarColor = previousNavColor
                }
                if (previousSoftInput != null) window.setSoftInputMode(previousSoftInput)
            }
            if (controller != null) {
                if (previousLightStatus != null) controller.isAppearanceLightStatusBars = previousLightStatus
                if (previousLightNav != null) controller.isAppearanceLightNavigationBars = previousLightNav
            }
        }
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

    // WebView 销毁绑定到自身生命周期（而非 lifecycleOwner 身份），避免被提前销毁
    DisposableEffect(webView) {
        onDispose {
            CookieManager.getInstance().flush()
            pendingFileCallback.value?.onReceiveValue(null)
            pendingFileCallback.value = null
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            (swipeRefresh.parent as? ViewGroup)?.removeView(swipeRefresh)
            webView.destroy()
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
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
        // 网页内容铺满窗口的内容区。系统栏由窗口直接着色（深色一体化），
        // 键盘弹出/收起由 OS 原生缩放窗口处理，Chromium 自动把焦点输入框滚入可见区——
        // 平滑、即时、不卡顿，且位置正确。
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            AndroidView(
                factory = { swipeRefresh },
                modifier = Modifier.fillMaxSize(),
                update = { layout ->
                    layout.isRefreshing = refreshing
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

        // 自动隐藏的悬浮顶栏：覆盖在网页之上（不挤压 WebView，开合时无重排/跳动）。
        // 向下滚动收起、向上滚动或到顶展开，腾出最大阅读空间。
        AnimatedVisibility(
            visible = barVisible && loadError == null,
            enter = expandVertically() + slideInVertically { -it },
            exit = shrinkVertically() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Column {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (webView.canGoBack()) webView.goBack() else onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
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
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = EmbedBarBg,
                        titleContentColor = EmbedOnBg,
                        navigationIconContentColor = EmbedOnBg,
                        actionIconContentColor = EmbedOnBg
                    )
                )
                // 站内导航时的细进度条；首屏用品牌 Loading
                if (isLoading && !firstLoad && progress > 0f && progress < 1f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = EmbedOnBg,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
          var ex=document.getElementById(ID);
          if(${enabled}){
            if(!ex){
              var s=document.createElement('style');
              s.id=ID;
              s.textContent='.AppHeader,header.AppHeader,.js-header-wrapper,.footer,footer.footer{display:none!important;} body{padding-top:0!important;}';
              (document.head||document.documentElement).appendChild(s);
            }
          } else if(ex){
            ex.parentNode.removeChild(ex);
          }
        })();
    """.trimIndent()
    runCatching { webView.evaluateJavascript(js, null) }
}
