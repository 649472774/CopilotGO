package com.tongxie.copilotgo.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.ui.remote.RemoteBrowserStore
import com.tongxie.copilotgo.ui.remote.RemoteBrowserSession
import com.tongxie.copilotgo.ui.remote.RemoteDestination
import com.tongxie.copilotgo.ui.remote.RemoteLoadPhase
import com.tongxie.copilotgo.ui.remote.RemoteNavigationPolicy
import com.tongxie.copilotgo.ui.remote.RemoteNetworkMode
import com.tongxie.copilotgo.ui.remote.RemoteWebViewHost
import com.tongxie.copilotgo.ui.remote.findActivity
import kotlinx.coroutines.flow.StateFlow

@Composable
fun RemoteWebViewScreen(
    onBack: () -> Unit,
    homeUrl: String = Constants.REMOTE_HOME_URL,
    title: String = "Copilot",
    proxyConfig: StateFlow<ProxyConfig>? = null,
    proxyInitialized: StateFlow<Boolean>? = null,
    proxyLoadError: StateFlow<String?>? = null
) {
    val context = LocalContext.current
    val browser = remember { RemoteBrowserStore.get(context.applicationContext) }
    RemoteWebViewContent(browser, onBack, homeUrl, title, proxyConfig, proxyInitialized, proxyLoadError)
}

@OptIn(ExperimentalActivityApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun RemoteWebViewContent(
    browser: RemoteBrowserSession,
    onBack: () -> Unit,
    homeUrl: String = Constants.REMOTE_HOME_URL,
    title: String = "Copilot",
    proxyConfig: StateFlow<ProxyConfig>? = null,
    proxyInitialized: StateFlow<Boolean>? = null,
    proxyLoadError: StateFlow<String?>? = null
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val owner = remember { Any() }
    val state by browser.state.collectAsState()
    val colors = MaterialTheme.colorScheme
    val background = colors.surface.toArgb()
    val fontScale = LocalConfiguration.current.fontScale
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var logoutDialog by rememberSaveable { mutableStateOf(false) }
    var desktopDialog by rememberSaveable { mutableStateOf(false) }
    var networkDialog by rememberSaveable { mutableStateOf(false) }
    var infoDialog by rememberSaveable { mutableStateOf(false) }
    var networkDraft by rememberSaveable { mutableStateOf(RemoteNetworkMode.SYSTEM) }
    var pickerRequest by rememberSaveable { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pickerRequest
        pickerRequest = null
        if (id != null) browser.uploads.result(id, result.resultCode, result.data)
    }
    val launchPicker by rememberUpdatedState<(Long, Intent) -> Unit>({ id, intent ->
        pickerRequest = id
        try {
            picker.launch(intent)
        } catch (_: ActivityNotFoundException) {
            pickerRequest = null
            browser.uploads.fail(id, "未找到系统文件选择器，无法上传文件。")
        } catch (_: SecurityException) {
            pickerRequest = null
            browser.uploads.fail(id, "系统未允许打开文件选择器，请检查设备限制。")
        } catch (_: IllegalStateException) {
            pickerRequest = null
            browser.uploads.fail(id, "页面已离开，文件选择已取消，请返回后重试。")
        }
    })

    DisposableEffect(browser, proxyConfig, proxyInitialized, proxyLoadError) {
        browser.bindProxySources(proxyConfig, proxyInitialized, proxyLoadError)
        onDispose { /* The retained document must continue observing transport changes. */ }
    }
    DisposableEffect(browser, owner, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> browser.resume(owner)
                Lifecycle.Event.ON_PAUSE -> browser.pause(owner)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            browser.detach(owner, activity?.isChangingConfigurations == true)
        }
    }
    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice.message)
        browser.consumeNotice(notice.id)
    }

    // Let the IME consume Back first, and let Navigation own predictive route Back.
    PredictiveBackHandler(
        enabled = state.page.canGoBack && state.transportReady &&
            !state.clearingCookies && !WindowInsets.isImeVisible
    ) { events ->
        events.collect { }
        browser.back()
    }

    val address = RemoteNavigationPolicy.classify(state.page.url)
    val pageTitle = when (address.destination) {
        RemoteDestination.COPILOT -> title
        RemoteDestination.GITHUB -> "GitHub 网页"
        else -> "已阻止的网页"
    }
    val editable = state.preferencesReady && !state.savingPreferences && !state.clearingCookies
    val overlay = state.problem != null || !state.preferencesReady || !state.transportReady ||
        state.clearingCookies || !state.page.hasVisiblePage

    Surface(modifier = Modifier.fillMaxSize(), color = colors.surface) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭网页，返回会话列表")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(pageTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            address.origin,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "网页选项")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (state.page.phase == RemoteLoadPhase.LOADING) "停止加载" else "重新加载") },
                                enabled = !state.clearingCookies,
                                onClick = {
                                    menuOpen = false
                                    if (state.page.phase == RemoteLoadPhase.LOADING) browser.stop() else browser.retry()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("回到 Copilot 首页") },
                                enabled = state.transportReady && !state.clearingCookies,
                                onClick = { menuOpen = false; browser.home() }
                            )
                            DropdownMenuItem(
                                text = { Text(if (state.preferences.immersive) "关闭网页沉浸模式" else "开启网页沉浸模式") },
                                enabled = editable,
                                onClick = {
                                    menuOpen = false
                                    browser.savePreferences(state.preferences.copy(immersive = !state.preferences.immersive))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (state.preferences.desktop) "切换到移动版" else "切换到桌面版") },
                                enabled = editable,
                                onClick = { menuOpen = false; desktopDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("网页网络设置") },
                                enabled = editable,
                                onClick = {
                                    menuOpen = false
                                    networkDraft = state.preferences.networkMode
                                    networkDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("用系统浏览器打开") },
                                onClick = { menuOpen = false; browser.requestExternal(state.page.url) }
                            )
                            DropdownMenuItem(
                                text = { Text("网页登录说明") },
                                onClick = { menuOpen = false; infoDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("清除网页登录数据") },
                                enabled = !state.clearingCookies,
                                onClick = { menuOpen = false; logoutDialog = true }
                            )
                        }
                    }
                }
                // Reserve the progress track, so finishing a load never changes the viewport height.
                Box(Modifier.fillMaxWidth().height(3.dp)) {
                    if (state.page.phase == RemoteLoadPhase.LOADING && state.page.hasVisiblePage) {
                        LinearProgressIndicator(progress = { state.page.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx ->
                            RemoteWebViewHost(ctx).also { host ->
                                browser.attach(owner, host, homeUrl, background) { id, intent -> launchPicker(id, intent) }
                                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) browser.resume(owner)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { browser.detach(owner, activity?.isChangingConfigurations == true) },
                        update = { browser.updateHost(owner, background, fontScale, overlay) }
                    )
                    if (overlay) {
                        Column(
                            Modifier.fillMaxSize().background(colors.surface)
                                .verticalScroll(rememberScrollState()).padding(24.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val problem = state.problem
                            if (problem != null) {
                                Text(problem.title, style = MaterialTheme.typography.titleMedium, color = colors.error)
                                Text(problem.detail, style = MaterialTheme.typography.bodyMedium)
                                Text(state.transportLabel, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                TextButton(
                                    onClick = browser::retry,
                                    enabled = !state.clearingCookies,
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(if (state.cookieProblem != null) "重试清除" else "重试 / 恢复页面")
                                }
                            } else {
                                CircularProgressIndicator()
                                Text(
                                    when {
                                        state.clearingCookies -> "正在清除网页登录数据…"
                                        !state.preferencesReady -> "正在读取网页设置…"
                                        !state.transportReady -> state.transportLabel
                                        else -> "正在连接网页…"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (state.page.phase == RemoteLoadPhase.LOADING) {
                                    TextButton(onClick = browser::stop, modifier = Modifier.heightIn(min = 48.dp)) { Text("停止加载") }
                                }
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding())
        }
    }

    if (networkDialog) {
        AlertDialog(
            onDismissRequest = { networkDialog = false },
            title = { Text("Remote 网页网络") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.transportLabel, style = MaterialTheme.typography.bodyMedium)
                    NetworkChoice("使用系统网络", "使用 Android 的网络、VPN 与系统代理，不套用应用代理。", networkDraft == RemoteNetworkMode.SYSTEM) {
                        networkDraft = RemoteNetworkMode.SYSTEM
                    }
                    NetworkChoice("跟随应用代理", "支持无需认证的 HTTP / SOCKS5 代理。不支持或应用失败时停止加载，不会自动直连。", networkDraft == RemoteNetworkMode.APP_PROXY) {
                        networkDraft = RemoteNetworkMode.APP_PROXY
                    }
                    Text("切换会重新打开当前网页，未发送的网页内容可能丢失。代理配置只在确认完成后应用；退出页面但保留网页时仍会保持该代理。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    networkDialog = false
                    browser.savePreferences(state.preferences.copy(networkMode = networkDraft))
                }) { Text("应用") }
            },
            dismissButton = { TextButton(onClick = { networkDialog = false }) { Text("取消") } }
        )
    }
    if (desktopDialog) {
        AlertDialog(
            onDismissRequest = { desktopDialog = false },
            title = { Text(if (state.preferences.desktop) "切换到移动版？" else "切换到桌面版？") },
            text = { Text("会重新打开当前网页，未发送的网页内容可能丢失。网页登录不会被清除。") },
            confirmButton = {
                TextButton(onClick = {
                    desktopDialog = false
                    browser.savePreferences(state.preferences.copy(desktop = !state.preferences.desktop))
                }) { Text("切换并重新打开") }
            },
            dismissButton = { TextButton(onClick = { desktopDialog = false }) { Text("取消") } }
        )
    }
    if (logoutDialog) {
        AlertDialog(
            onDismissRequest = { logoutDialog = false },
            title = { Text("清除网页登录数据？") },
            text = { Text("将关闭网页并清除本应用内嵌网页的 Cookie 与网页存储。未发送的网页内容可能丢失。\n\n不会退出原生聊天的 GitHub 账号，也不会退出系统浏览器。清除完成前不会重新加载网页。") },
            confirmButton = {
                TextButton(onClick = { logoutDialog = false; browser.logout() }) { Text("清除网页登录数据") }
            },
            dismissButton = { TextButton(onClick = { logoutDialog = false }) { Text("取消") } }
        )
    }
    if (infoDialog) {
        AlertDialog(
            onDismissRequest = { infoDialog = false },
            title = { Text("Remote 使用独立网页登录") },
            text = {
                Text("网页账号由 GitHub 网页显示，不代表原生聊天的登录状态。应用不会把原生 GitHub / Copilot 凭据注入网页。\n\n仅 HTTPS GitHub 页面在应用内打开。其他网站交由系统浏览器确认打开；两者不共享 Cookie 或代理设置。需要外部身份提供方的企业 SSO，请在系统浏览器中继续。")
            },
            confirmButton = { TextButton(onClick = { infoDialog = false }) { Text("知道了") } }
        )
    }
    state.externalRequest?.let { request ->
        AlertDialog(
            onDismissRequest = browser::dismissExternal,
            title = { Text(if (request.download) "交给系统浏览器下载？" else "在系统浏览器中打开？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(request.origin, style = MaterialTheme.typography.titleSmall)
                    Text("浏览器不会继承 Remote 的网页登录或代理设置。需要登录的页面或下载可能要求重新登录。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    browser.dismissExternal()
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.url)).addCategory(Intent.CATEGORY_BROWSABLE))
                        if (request.download) browser.notify("已交给系统浏览器，下载是否成功请在浏览器中查看。")
                    } catch (_: ActivityNotFoundException) {
                        browser.notify("未找到可打开 HTTPS 网页的浏览器，请先安装或启用浏览器。")
                    } catch (_: SecurityException) {
                        browser.notify("系统未允许打开此链接，请检查设备限制。")
                    }
                }) { Text("打开浏览器") }
            },
            dismissButton = { TextButton(onClick = browser::dismissExternal) { Text("取消") } }
        )
    }
}

@Composable
private fun NetworkChoice(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
