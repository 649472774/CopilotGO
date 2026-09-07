package com.tongxie.copilotgo.ui.remote

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import java.io.ByteArrayInputStream

internal class RemoteWebClients(
    private val session: RemoteBrowserSession,
    private val resourceInterceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null
) {
    private val main = Handler(Looper.getMainLooper())

    val navigation = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) {
                return request.url.scheme != "https" && request.url.toString() != "about:blank"
            }
            val address = RemoteNavigationPolicy.classify(request.url.toString())
            if (address.isEmbedded) {
                if (request.isRedirect && session.isInterrupted(view)) return true
                if (!request.isRedirect && session.needsFreshNavigation(view)) {
                    main.post { if (session.owns(view)) session.navigate(address.url) }
                    return true
                }
                session.navigationRequested(view, address.url)
                return false
            }
            session.interceptNavigation(view, request.url.toString())
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame) {
                val address = RemoteNavigationPolicy.classify(request.url.toString())
                if (!address.isEmbedded) {
                    main.post { session.interceptNavigation(view, request.url.toString()) }
                    return blockedResponse()
                }
                main.post { session.mainFrameRequested(view, address.url) }
            }
            // App-supplied content cannot bypass the main-document origin guard.
            return resourceInterceptor?.invoke(request)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) =
            session.pageStarted(view, url)

        override fun onPageCommitVisible(view: WebView, url: String?) =
            session.pageCommitted(view, url)

        override fun onPageFinished(view: WebView, url: String?) =
            session.pageFinished(view, url)

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
            session.historyChanged(view, url)

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val problem = when (error.errorCode) {
                ERROR_HOST_LOOKUP, ERROR_CONNECT -> RemoteProblem("无法连接网页", "请检查网络、DNS 与 Remote 的网络设置，然后重试。")
                ERROR_TIMEOUT -> RemoteProblem("网页连接超时", "服务器未及时响应。请检查网络后重试。")
                ERROR_PROXY_AUTHENTICATION -> RemoteProblem("代理要求认证", "Remote 不会提供应用的代理凭据。请选择受支持的无认证代理或系统网络。")
                else -> RemoteProblem("网页加载失败", "页面未完成加载（错误码 ${error.errorCode}）。可以重试或在系统浏览器中打开。")
            }
            session.pageFailed(view, problem, request.url.toString())
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            if (request.isForMainFrame) {
                session.pageFailed(
                    view,
                    RemoteProblem("网页服务返回错误", "服务器返回 HTTP ${errorResponse.statusCode}。请稍后重试，或在系统浏览器中继续。"),
                    request.url.toString()
                )
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            session.pageFailed(view, RemoteProblem("安全连接已被阻止", "网页证书无法验证。请检查设备时间、网络或代理证书；Remote 不会跳过证书验证。"))
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
            handler.cancel()
            session.pageFailed(view, RemoteProblem("此连接要求浏览器认证", "Remote 不会自动提供本机账号或代理凭据。请使用系统浏览器，或调整代理配置。"))
        }

        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponse
        ) {
            callback.backToSafety(true)
            session.pageFailed(view, RemoteProblem("不安全网页已被阻止", "Android 安全浏览检测到风险。请返回 Copilot 首页，不要继续访问该链接。"))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            session.rendererGone(view, detail.didCrash())
            return true
        }
    }

    val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) =
            session.progressChanged(view, newProgress)

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            if (!session.owns(webView) || !RemoteNavigationPolicy.classify(webView.url).isEmbedded) {
                filePathCallback.onReceiveValue(null)
                session.notify("无法为当前网页选择文件。")
                return true
            }
            return session.uploads.request(filePathCallback, fileChooserParams)
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
            session.openPopup(view, isUserGesture, resultMsg)

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
            session.notify("Remote 未授予网页麦克风或摄像头权限。需要这些功能时，请使用系统浏览器。")
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
            session.notify("Remote 不向网页提供设备位置。")
        }
    }
}

internal fun blockedResponse() = WebResourceResponse(
    "text/plain",
    "UTF-8",
    403,
    "Blocked",
    mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(ByteArray(0))
)
