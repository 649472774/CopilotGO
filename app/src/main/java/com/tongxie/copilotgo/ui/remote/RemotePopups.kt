package com.tongxie.copilotgo.ui.remote

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/** A transport-only WebView captures a target=_blank URL; it never loads its content. */
internal class RemotePopups(
    context: Context,
    private val navigate: (String) -> Unit,
    private val notify: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var popup: WebView? = null
    private var timeout: Runnable? = null

    fun open(userGesture: Boolean, result: Message): Boolean {
        if (!userGesture) {
            notify("已阻止网页自动弹出新窗口。")
            return false
        }
        val transport = result.obj as? WebView.WebViewTransport
        if (transport == null) {
            notify("无法打开网页请求的新窗口，请使用系统浏览器。")
            return false
        }
        close()
        val capture = WebView(appContext)
        popup = capture
        capture.settings.apply {
            javaScriptEnabled = false
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
        }
        capture.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                deliver(view, request.url.toString())
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && url != "about:blank") deliver(view, url)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (request.isForMainFrame) main.post { deliver(view, request.url.toString()) }
                return blockedResponse()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (popup === view) {
                    close()
                    notify("网页新窗口已关闭，请重试或使用系统浏览器。")
                }
                return true
            }
        }
        transport.webView = capture
        result.sendToTarget()
        timeout = Runnable {
            if (popup === capture) {
                close()
                notify("未能取得新窗口的地址，请在系统浏览器中继续。")
            }
        }.also { main.postDelayed(it, 15_000) }
        return true
    }

    private fun deliver(view: WebView, url: String) {
        if (popup !== view) return
        popup = null
        timeout?.let(main::removeCallbacks)
        timeout = null
        // Do not destroy a WebView from inside its navigation callback.
        main.post { view.destroy() }
        navigate(url)
    }

    fun close() {
        timeout?.let(main::removeCallbacks)
        timeout = null
        val old = popup
        popup = null
        old?.destroy()
    }
}
