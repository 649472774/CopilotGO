package com.tongxie.copilotgo.ui.remote

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature

internal interface RemoteWebData {
    fun configure(view: WebView)
    fun removeCookies(completion: (Boolean) -> Unit)
    fun clearStorage(completion: (complete: Boolean) -> Unit)
    fun flush()
}

internal class AndroidRemoteWebData : RemoteWebData {
    private val cookies: CookieManager by lazy { CookieManager.getInstance() }

    override fun configure(view: WebView) {
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
    }

    override fun removeCookies(completion: (Boolean) -> Unit) {
        cookies.removeAllCookies { completion(it) }
    }

    override fun clearStorage(completion: (Boolean) -> Unit) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) { completion(true) }
        } else {
            WebStorage.getInstance().deleteAllData()
            completion(false)
        }
    }

    override fun flush() = cookies.flush()
}
