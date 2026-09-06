package com.tongxie.copilotgo.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.update.AndroidUpdatePlatform
import com.tongxie.copilotgo.data.update.UpdateChecker
import com.tongxie.copilotgo.data.update.UpdateException
import com.tongxie.copilotgo.data.update.UpdateInfo
import com.tongxie.copilotgo.data.update.UpdatePlatform
import com.tongxie.copilotgo.data.update.UpdatePreferences
import com.tongxie.copilotgo.data.update.UpdatePrefs
import com.tongxie.copilotgo.data.update.UpdateRoute
import com.tongxie.copilotgo.data.update.UpdateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException

class UpdateViewModel(
    private val checker: UpdateSource,
    private val prefs: UpdatePreferences,
    private val platform: UpdatePlatform,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    constructor(appContext: Context, checker: UpdateChecker, prefs: UpdatePrefs) :
        this(checker, prefs, AndroidUpdatePlatform(appContext))

    sealed interface State {
        data object Idle : State
        data class Checking(val manual: Boolean, val route: UpdateRoute) : State
        data class UpToDate(val currentVersion: String) : State
        data class Available(val info: UpdateInfo, val route: UpdateRoute = UpdateRoute.APP_SETTINGS) : State
        data class Downloading(
            val info: UpdateInfo, val downloaded: Long, val total: Long, val route: UpdateRoute
        ) : State
        data class Verifying(val info: UpdateInfo) : State
        data class Downloaded(val info: UpdateInfo, val file: File, val returnedFromInstaller: Boolean = false) : State
        data class NeedInstallPermission(val info: UpdateInfo, val file: File, val returnedWithoutPermission: Boolean = false) : State
        data class InstallerOpened(val info: UpdateInfo, val file: File) : State
        data class Error(val message: String, val retry: Retry? = null, val canTryDirect: Boolean = false) : State
    }

    sealed interface Retry {
        data class Check(val manual: Boolean, val route: UpdateRoute) : Retry
        data class Download(val info: UpdateInfo, val route: UpdateRoute) : Retry
        data class Install(val info: UpdateInfo, val file: File) : Retry
        data class Skip(val info: UpdateInfo) : Retry
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var operation: Job? = null
    private var generation = 0L
    private var autoChecked = false
    private var permissionRequested = false
    private var externalUiPaused = false

    fun autoCheckOnce() {
        if (autoChecked) return
        autoChecked = true
        runOperation(State.Checking(false, UpdateRoute.APP_SETTINGS), Retry.Check(false, UpdateRoute.APP_SETTINGS)) { id ->
            val lastCheck = withContext(ioDispatcher) { prefs.lastCheckAt }
            val now = System.currentTimeMillis()
            if (lastCheck > 0 && now >= lastCheck && now - lastCheck < CHECK_INTERVAL_MS) {
                publish(id, State.Idle)
            } else {
                performCheck(id, false, UpdateRoute.APP_SETTINGS)
            }
        }
    }

    fun autoCheckIfDue() = autoCheckOnce()

    fun check(manual: Boolean) = check(manual, UpdateRoute.APP_SETTINGS)

    private fun check(manual: Boolean, route: UpdateRoute) {
        runOperation(State.Checking(manual, route), Retry.Check(manual, route)) { id ->
            performCheck(id, manual, route)
        }
    }

    private suspend fun performCheck(id: Long, manual: Boolean, route: UpdateRoute) {
        when (val result = checker.check(route)) {
            is UpdateChecker.CheckResult.Available -> {
                val preferences = withContext(ioDispatcher) {
                    prefs.lastCheckAt = System.currentTimeMillis()
                    prefs.skippedVersion to prefs.wifiAutoDownload
                }
                when {
                    !manual && preferences.first == result.info.versionName -> publish(id, State.Idle)
                    !manual && preferences.second && platform.isUnmeteredWifi() ->
                        performDownload(id, result.info, route)
                    else -> publish(id, State.Available(result.info, route))
                }
            }
            is UpdateChecker.CheckResult.UpToDate -> {
                withContext(ioDispatcher) { prefs.lastCheckAt = System.currentTimeMillis() }
                publish(id, if (manual) State.UpToDate(result.currentVersion) else State.Idle)
            }
            is UpdateChecker.CheckResult.Failed ->
                publish(id, errorState(result.message, Retry.Check(manual, route)))
        }
    }

    fun startDownload(info: UpdateInfo) {
        val route = (_state.value as? State.Available)?.route ?: UpdateRoute.APP_SETTINGS
        startDownload(info, route)
    }

    private fun startDownload(info: UpdateInfo, route: UpdateRoute) {
        runOperation(State.Downloading(info, 0, info.apkSize, route), Retry.Download(info, route)) { id ->
            performDownload(id, info, route)
        }
    }

    private suspend fun performDownload(id: Long, info: UpdateInfo, route: UpdateRoute) {
        publish(id, State.Downloading(info, 0, info.apkSize, route))
        try {
            val target = platform.createDownloadTarget(info)
            var completed = false
            checker.download(info, target, route).collect { event ->
                when (event) {
                    is UpdateChecker.DownloadEvent.Progress ->
                        publish(id, State.Downloading(info, event.bytesRead, event.total, route))
                    UpdateChecker.DownloadEvent.Verifying -> publish(id, State.Verifying(info))
                    is UpdateChecker.DownloadEvent.Done -> {
                        val verifiedInfo = info.copy(apkSha256 = event.sha256)
                        publish(id, State.Verifying(verifiedInfo))
                        withTimeout(30_000) { platform.verifyApk(verifiedInfo, event.file) }
                        completed = true
                        publish(id, State.Downloaded(verifiedInfo, event.file))
                    }
                }
            }
            if (!completed) throw UpdateException("下载未完成，请重试")
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            publish(id, errorState("安装包校验超时，请重试", Retry.Download(info, route)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            publish(id, errorState(messageFor(e), Retry.Download(info, route)))
        }
    }

    fun install(info: UpdateInfo, file: File) {
        runOperation(State.Verifying(info), Retry.Install(info, file)) { id ->
            withTimeout(30_000) { platform.verifyApk(info, file) }
            if (!platform.canInstall()) {
                publish(id, State.NeedInstallPermission(info, file))
            } else if (id == generation) {
                publish(id, State.InstallerOpened(info, file))
                externalUiPaused = false
                platform.install(file)
            }
        }
    }

    fun openInstallPermission() {
        val pending = _state.value as? State.NeedInstallPermission ?: return
        try {
            permissionRequested = true
            externalUiPaused = false
            platform.openInstallPermission()
        } catch (e: UpdateException) {
            permissionRequested = false
            _state.value = errorState(messageFor(e), Retry.Install(pending.info, pending.file))
        }
    }

    fun onHostPaused() {
        if (permissionRequested || _state.value is State.InstallerOpened) externalUiPaused = true
    }

    fun onHostResumed() {
        if (!externalUiPaused) return
        externalUiPaused = false
        when (val current = _state.value) {
            is State.NeedInstallPermission -> {
                if (permissionRequested) {
                    permissionRequested = false
                    _state.value = try {
                        if (platform.canInstall()) State.Downloaded(current.info, current.file)
                        else current.copy(returnedWithoutPermission = true)
                    } catch (e: UpdateException) {
                        errorState(messageFor(e), Retry.Install(current.info, current.file))
                    }
                }
            }
            is State.InstallerOpened ->
                _state.value = State.Downloaded(current.info, current.file, returnedFromInstaller = true)
            else -> Unit
        }
    }

    fun skipVersion(info: UpdateInfo) {
        runOperation(State.Available(info), Retry.Skip(info)) { id ->
            withContext(ioDispatcher) { prefs.skippedVersion = info.versionName }
            publish(id, State.Idle)
        }
    }

    fun retry(direct: Boolean = false) {
        val current = _state.value as? State.Error ?: return
        if (direct && !current.canTryDirect) return
        when (val retry = current.retry) {
            is Retry.Check -> check(true, if (direct) UpdateRoute.DIRECT else retry.route)
            is Retry.Download -> startDownload(retry.info, if (direct) UpdateRoute.DIRECT else retry.route)
            is Retry.Install -> install(retry.info, retry.file)
            is Retry.Skip -> skipVersion(retry.info)
            null -> Unit
        }
    }

    fun openReleasePage() {
        try {
            platform.openReleasePage()
        } catch (e: UpdateException) {
            _state.value = State.Error(messageFor(e), (_state.value as? State.Error)?.retry)
        }
    }

    fun dismiss() {
        generation++
        operation?.cancel()
        permissionRequested = false
        externalUiPaused = false
        _state.value = State.Idle
    }

    private fun runOperation(initial: State, retry: Retry, block: suspend (Long) -> Unit) {
        if (operation?.isActive == true) return
        val previous = operation
        val id = ++generation
        permissionRequested = false
        externalUiPaused = false
        _state.value = initial
        operation = viewModelScope.launch {
            try {
                // A canceled socket/file operation finishes cleanup before its replacement starts.
                withTimeout(10_000) { previous?.join() }
                block(id)
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                publish(id, errorState("更新操作超时，请重试", retry))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                publish(id, errorState(messageFor(e), retry))
            } finally {
                if (id == generation) operation = null
            }
        }
    }

    private fun publish(id: Long, state: State) {
        if (id == generation) _state.value = state
    }

    private fun errorState(message: String, retry: Retry): State.Error {
        val route = when (retry) {
            is Retry.Check -> retry.route
            is Retry.Download -> retry.route
            else -> null
        }
        return State.Error(message, retry, route == UpdateRoute.APP_SETTINGS && checker.hasConfiguredProxy)
    }

    private fun messageFor(error: IOException): String =
        if (error is UpdateException) error.message ?: "更新失败，请重试"
        else "无法读取或保存更新数据，请检查网络与可用空间后重试"

    companion object {
        private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
