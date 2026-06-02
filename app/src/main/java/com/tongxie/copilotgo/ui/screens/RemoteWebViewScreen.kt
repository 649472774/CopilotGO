package com.tongxie.copilotgo.ui.screens

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tongxie.copilotgo.BuildConfig
import com.tongxie.copilotgo.data.Constants

/**
 * Remote 模式：把官方 web Copilot（github.com/copilot）以「原生内嵌」的姿态呈现，
 * 而非粗糙的浏览器壳。为达到完美嵌入观感，做了以下处理（参考主流 App 内嵌 WebView 方案）：
 *
 * - 固定品牌标题「Copilot」，不随网页 <title> 抖动（浏览器感的最大来源）。
 * - 沉浸模式：注入 CSS 隐藏 GitHub 全局顶栏/页脚，只留 Copilot 主体，像一个专属页面（可在菜单关闭）。
 * - 下拉刷新（SwipeRefreshLayout），原生手势，而非工具栏一排按钮。
 * - 首次加载用品牌化全屏 Loading（主题背景 + 转圈），消除 WebView 白屏闪烁；WebView 背景同步主题色。
 * - 顶栏配色跟随 Material 主题；返回键/工具栏返回 = 能回退就回退，否则退出本页。
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
    val bgArgb = MaterialTheme.colorScheme.background.toArgb()

    var progress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var firstLoad by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(false) }
    var immersive by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
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

    Scaffold(
        topBar = {
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                // 仅在站内导航（非首屏、非错误）时显示细进度条；首屏用品牌 Loading
                if (isLoading && !firstLoad && progress > 0f && progress < 1f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "请检查网络（github.com 是否可访问），然后重试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
                // 首屏品牌化 Loading：主题背景 + 转圈，消除 WebView 白屏闪烁
                firstLoad -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Text(
                            "正在连接 Copilot…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
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
