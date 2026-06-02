package com.tongxie.copilotgo.ui.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.update.ApkInstaller
import com.tongxie.copilotgo.data.update.UpdateChecker
import com.tongxie.copilotgo.data.update.UpdateInfo
import com.tongxie.copilotgo.data.update.UpdatePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class UpdateViewModel(
    private val appContext: Context,
    private val checker: UpdateChecker,
    private val prefs: UpdatePrefs
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val currentVersion: String) : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val info: UpdateInfo, val downloaded: Long, val total: Long) : State
        data class Downloaded(val info: UpdateInfo, val file: File) : State
        data class NeedInstallPermission(val info: UpdateInfo, val file: File) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var autoChecked = false

    /**
     * 冷启动自动检查：每个进程只触发一次，且只有超过间隔才联网，避免单次会话内反复检查。
     */
    fun autoCheckOnce() {
        if (autoChecked) return
        autoChecked = true
        val now = System.currentTimeMillis()
        if (now - prefs.lastCheckAt < CHECK_INTERVAL_MS) {
            _state.value = State.Idle
            return
        }
        check(manual = false)
    }

    fun autoCheckIfDue() = autoCheckOnce()

    fun check(manual: Boolean) {
        // 下载中 / 已下载时不重复检查
        val s = _state.value
        if (s is State.Downloading || s is State.Downloaded) return
        _state.value = State.Checking
        viewModelScope.launch {
            val r = checker.check()
            prefs.lastCheckAt = System.currentTimeMillis()
            when (r) {
                is UpdateChecker.CheckResult.Available -> {
                    val skipped = prefs.skippedVersion
                    if (!manual && skipped == r.info.versionName) {
                        _state.value = State.Idle
                    } else if (!manual && prefs.wifiAutoDownload && isOnWifi()) {
                        startDownload(r.info)
                    } else {
                        _state.value = State.Available(r.info)
                    }
                }
                is UpdateChecker.CheckResult.UpToDate -> {
                    _state.value = if (manual) State.UpToDate(r.currentVersion) else State.Idle
                }
                is UpdateChecker.CheckResult.Failed -> {
                    _state.value = if (manual) State.Error(r.message) else State.Idle
                }
            }
        }
    }

    fun startDownload(info: UpdateInfo) {
        _state.value = State.Downloading(info, 0, info.apkSize)
        viewModelScope.launch {
            val target = File(File(appContext.getExternalFilesDir(null), "update"), "CopilotGo-update.apk")
            try {
                checker.download(info.apkUrl, target).collect { event ->
                    when (event) {
                        is UpdateChecker.DownloadEvent.Progress ->
                            _state.value = State.Downloading(info, event.bytesRead, event.total)
                        is UpdateChecker.DownloadEvent.Done ->
                            _state.value = State.Downloaded(info, event.file)
                    }
                }
                // 下载完成后尝试安装
                tryInstall(info, target)
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "下载失败")
            }
        }
    }

    fun install(info: UpdateInfo, file: File) = tryInstall(info, file)

    private fun tryInstall(info: UpdateInfo, file: File) {
        if (!file.exists()) {
            _state.value = State.Error("安装包不存在，请重试下载")
            return
        }
        if (!ApkInstaller.canInstall(appContext)) {
            _state.value = State.NeedInstallPermission(info, file)
            return
        }
        runCatching { ApkInstaller.install(appContext, file) }
            .onFailure { _state.value = State.Error(it.message ?: "无法启动安装器") }
    }

    fun openInstallPermission() = ApkInstaller.openInstallPermissionSettings(appContext)

    fun skipVersion(info: UpdateInfo) {
        prefs.skippedVersion = info.versionName
        _state.value = State.Idle
    }

    fun dismiss() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    private fun isOnWifi(): Boolean = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        val network = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    companion object {
        private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
