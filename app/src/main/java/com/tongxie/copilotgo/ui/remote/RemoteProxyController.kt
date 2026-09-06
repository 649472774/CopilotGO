package com.tongxie.copilotgo.ui.remote

import android.os.Handler
import android.os.Looper
import android.util.AndroidRuntimeException
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.tongxie.copilotgo.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

internal interface RemoteProxyBackend {
    val supported: Boolean
    fun configure(route: RemoteProxyRoute, completion: (Boolean) -> Unit)
}

internal sealed interface RemoteProxyStatus {
    data object Applying : RemoteProxyStatus
    data class Ready(val route: RemoteProxyRoute) : RemoteProxyStatus
    data class Failed(val timedOut: Boolean) : RemoteProxyStatus
}

/**
 * ProxyController is process-global and its operations cannot be cancelled.
 * Serialize callbacks, including a clear requested while a set is still in flight.
 */
internal class RemoteProxyController(
    private val scope: CoroutineScope,
    private val backend: RemoteProxyBackend,
    private val onStatus: (RemoteProxyStatus) -> Unit
) {
    private var desired: RemoteProxyRoute = RemoteProxyRoute.System
    private var applied: RemoteProxyRoute? = RemoteProxyRoute.System
    private var inFlight = false
    private var sequence = 0L
    private var timeout: Job? = null

    val supported: Boolean get() = backend.supported

    fun request(route: RemoteProxyRoute) {
        desired = route
        if (inFlight) {
            onStatus(RemoteProxyStatus.Applying)
        } else if (applied == route) {
            onStatus(RemoteProxyStatus.Ready(route))
        } else {
            applyNext()
        }
    }

    private fun applyNext() {
        val route = desired
        val id = ++sequence
        inFlight = true
        onStatus(RemoteProxyStatus.Applying)
        timeout = scope.launch {
            delay(15_000)
            if (inFlight && id == sequence) {
                // Do not release the queue: a late native callback could otherwise undo a newer route.
                onStatus(RemoteProxyStatus.Failed(timedOut = true))
            }
        }
        backend.configure(route) { success ->
            if (!inFlight || id != sequence) return@configure
            timeout?.cancel()
            inFlight = false
            applied = if (success) route else null
            if (desired != route) {
                applyNext()
            } else if (success) {
                onStatus(RemoteProxyStatus.Ready(route))
            } else {
                onStatus(RemoteProxyStatus.Failed(timedOut = false))
            }
        }
    }
}

internal class AndroidRemoteProxyBackend : RemoteProxyBackend {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executor { mainHandler.post(it) }

    override val supported: Boolean by lazy {
        try {
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        } catch (_: AndroidRuntimeException) {
            Logger.w("Remote: WebView proxy feature detection unavailable")
            false
        }
    }

    override fun configure(route: RemoteProxyRoute, completion: (Boolean) -> Unit) {
        if (!supported) {
            completion(route == RemoteProxyRoute.System)
            return
        }
        try {
            val controller = ProxyController.getInstance()
            when (route) {
                RemoteProxyRoute.System -> controller.clearProxyOverride(executor) { completion(true) }
                is RemoteProxyRoute.Override -> controller.setProxyOverride(
                    ProxyConfig.Builder()
                        .addProxyRule(route.rule)
                        .removeImplicitRules()
                        .build(),
                    executor
                ) { completion(true) }
            }
        } catch (_: IllegalArgumentException) {
            Logger.w("Remote: WebView rejected proxy configuration")
            completion(false)
        } catch (_: UnsupportedOperationException) {
            Logger.w("Remote: WebView proxy override is unavailable")
            completion(false)
        } catch (_: IllegalStateException) {
            Logger.w("Remote: WebView proxy controller is unavailable")
            completion(false)
        } catch (_: AndroidRuntimeException) {
            Logger.w("Remote: WebView provider is unavailable during proxy update")
            completion(false)
        }
    }
}
