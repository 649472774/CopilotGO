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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tongxie.copilotgo.BuildConfig
import com.tongxie.copilotgo.data.Constants

/**
 * Remote 模式：内嵌官方 web Copilot（github.com/copilot）的 WebView 壳。
 *
 * 设计要点（实际可用 + 严谨）：
 * - 登录态：CookieManager 持久化（含第三方 cookie），下次进 App 自动登录；onPause flush 落盘。
 * - JS / DOM Storage 开启，SPA 才能跑；支持 pinch 缩放。
 * - 文件上传：WebChromeClient.onShowFileChooser 桥接 Compose ActivityResult，Copilot 可传附件。
 * - 导航：http(s) 留在 WebView 内（OAuth 回跳也在内完成）；其他 scheme（mailto/intent/market…）交系统。
 * - 返回键：WebView 能回退就回退，否则退出本页。
 * - 进度条 + 主框架错误重试 + 溢出菜单（刷新 / 回首页 / 桌面切换 / 外部浏览器打开 / 退出登录清 cookie）。
 * - 生命周期：onPause/onResume 转发；离开页面销毁 WebView，避免泄漏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RemoteWebViewScreen(
    onBack: () -> Unit,
    homeUrl: String = Constants.REMOTE_HOME_URL
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var pageTitle by remember { mutableStateOf("Remote · Copilot") }
    var progress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(false) }
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
                        // 站内 / OAuth 回跳都留在 WebView 里
                        false
                    } else {
                        // mailto:, tel:, intent:, market: 等交给系统
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
                    canGoBack = view.canGoBack()
                    view.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
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
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress / 100f
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                }

                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    // 取消上一个未完成的选择，避免回调泄漏
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
            // 取消挂起的文件选择回调
            pendingFileCallback.value?.onReceiveValue(null)
            pendingFileCallback.value = null
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
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
                    title = {
                        Text(
                            pageTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (webView.canGoBack()) webView.goBack() else onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { webView.reload() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("回到 Copilot 首页") },
                                onClick = {
                                    menuOpen = false
                                    webView.loadUrl(homeUrl)
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
                )
                if (isLoading && progress > 0f && progress < 1f) {
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
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )

            val err = loadError
            if (err != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
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
                            webView.reload()
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("重试") }
                }
            }
        }
    }
}
