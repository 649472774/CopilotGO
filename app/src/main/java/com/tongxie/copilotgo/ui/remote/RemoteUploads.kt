package com.tongxie.copilotgo.ui.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import com.tongxie.copilotgo.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume

internal class RemoteUploads(
    context: Context,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val pending = PendingWebCallback<Array<Uri>>()
    private var readJob: Job? = null
    var launchPicker: ((Long, Intent) -> Unit)? = null

    fun request(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        val launch = launchPicker
        if (launch == null) {
            callback.onReceiveValue(null)
            notify("页面已离开，文件选择已取消。")
            return true
        }
        if (params.mode != WebChromeClient.FileChooserParams.MODE_OPEN &&
            params.mode != WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        ) {
            callback.onReceiveValue(null)
            notify("Remote 仅支持从系统文件选择器上传现有文件。")
            return true
        }
        val id = pending.begin(callback::onReceiveValue)
        if (id == null) {
            notify("请先完成当前文件选择。")
            return true
        }
        val intent = try {
            params.createIntent()
        } catch (_: IllegalArgumentException) {
            fail(id, "网页请求的文件选择方式不受支持。")
            return true
        }
        launch(id, intent)
        return true
    }

    fun result(id: Long, resultCode: Int, intent: Intent?) {
        if (!pending.matches(id)) return
        if (resultCode != Activity.RESULT_OK) {
            pending.complete(id, null)
            return
        }
        val uris = try {
            WebChromeClient.FileChooserParams.parseResult(resultCode, intent)?.distinct()?.toTypedArray()
        } catch (_: BadParcelableException) {
            fail(id, "文件选择器返回了无效结果，请重新选择。")
            return
        }
        if (uris.isNullOrEmpty() || uris.size > 32 ||
            uris.any { it.scheme != "content" || it.authority == "${appContext.packageName}.fileprovider" }
        ) {
            fail(id, "未取得可上传的文件。请选择不超过 32 个系统文档。")
            return
        }
        readJob = scope.launch {
            try {
                val readable = withTimeout(15_000) { canRead(uris) }
                if (readable) pending.complete(id, uris)
                else fail(id, "无法读取所选文件。请重新选择，并允许访问该文档。")
            } catch (_: TimeoutCancellationException) {
                fail(id, "文件提供方响应超时，上传已取消。请下载到设备后重新选择。")
            }
        }
    }

    private suspend fun canRead(uris: Array<Uri>): Boolean = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        val work = scope.launch(Dispatchers.IO) {
            val readable = try {
                uris.all { uri ->
                    appContext.contentResolver.openAssetFileDescriptor(uri, "r", cancellation)?.use { true } ?: false
                }
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: OperationCanceledException) {
                false
            }
            if (continuation.isActive) continuation.resume(readable)
        }
        continuation.invokeOnCancellation {
            cancellation.cancel()
            work.cancel()
        }
    }

    fun fail(id: Long, message: String) {
        if (!pending.matches(id)) return
        Logger.w("Remote: file chooser did not complete successfully")
        pending.complete(id, null)
        notify(message)
    }

    fun detach(keepPending: Boolean) {
        launchPicker = null
        if (!keepPending) cancel()
    }

    fun cancel() {
        readJob?.cancel()
        readJob = null
        pending.cancel()
    }
}
