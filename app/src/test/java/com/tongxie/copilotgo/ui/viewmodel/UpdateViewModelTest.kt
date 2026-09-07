package com.tongxie.copilotgo.ui.viewmodel

import com.tongxie.copilotgo.data.update.UpdateChecker
import com.tongxie.copilotgo.data.update.UpdateException
import com.tongxie.copilotgo.data.update.UpdateInfo
import com.tongxie.copilotgo.data.update.UpdatePlatform
import com.tongxie.copilotgo.data.update.UpdatePreferences
import com.tongxie.copilotgo.data.update.UpdateRoute
import com.tongxie.copilotgo.data.update.UpdateSource
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeSource()
    private val prefs = MemoryPreferences()
    private lateinit var platform: FakePlatform
    private lateinit var vm: UpdateViewModel
    private val info = UpdateInfo(
        "0.2.0", "v0.2.0", "Fixture", "Fixture changelog", "https://github.com/fixture.apk",
        20, "https://github.com/649472774/CopilotGO/releases",
        apkName = "fixture.apk", apkSha256 = "a".repeat(64)
    )

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        platform = FakePlatform(folder.newFile("fixture.apk"))
        vm = UpdateViewModel(source, prefs, platform, dispatcher)
    }

    @After fun cleanup() {
        vm.viewModelScope.cancel()
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    @Test fun duplicate_checks_share_one_operation() = runTest(dispatcher) {
        source.checkBlock = { awaitCancellation() }
        vm.check(true)
        vm.check(true)
        runCurrent()
        assertEquals(1, source.checks.size)
        vm.dismiss()
        runCurrent()
        assertEquals(UpdateViewModel.State.Idle, vm.state.value)
        assertEquals(0, prefs.lastCheckAt)
    }

    @Test fun cancelling_old_check_cannot_overwrite_new_state() = runTest(dispatcher) {
        val stale = CompletableDeferred<UpdateChecker.CheckResult>()
        source.checkBlock = { withContext(NonCancellable) { stale.await() } }
        vm.check(true)
        runCurrent()
        vm.dismiss()
        source.checkBlock = { UpdateChecker.CheckResult.UpToDate("0.2.0-debug") }
        vm.check(true)
        runCurrent()
        stale.complete(UpdateChecker.CheckResult.Available(info))
        advanceUntilIdle()
        assertEquals(UpdateViewModel.State.UpToDate("0.2.0-debug"), vm.state.value)
        assertEquals(2, source.checks.size)
    }

    @Test fun active_download_cannot_be_replaced_by_another_download_or_check() = runTest(dispatcher) {
        var cancelled = false
        source.downloadBlock = { _, _, _ ->
            flow {
                try {
                    emit(UpdateChecker.DownloadEvent.Progress(1, 20))
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        vm.startDownload(info)
        runCurrent()
        vm.startDownload(info)
        vm.check(true)
        assertEquals(1, source.downloads.size)
        assertEquals(0, source.checks.size)
        assertTrue(vm.state.value is UpdateViewModel.State.Downloading)
        vm.dismiss()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(UpdateViewModel.State.Idle, vm.state.value)
    }

    @Test fun download_validates_but_never_installs_without_user_action() = runTest(dispatcher) {
        vm.startDownload(info)
        advanceUntilIdle()
        val state = vm.state.value as UpdateViewModel.State.Downloaded
        assertEquals(info.apkSha256, state.info.apkSha256)
        assertEquals(1, platform.verified.size)
        assertEquals(0, platform.installs)
        vm.install(state.info, state.file)
        advanceUntilIdle()
        assertEquals(2, platform.verified.size)
        assertEquals(1, platform.installs)
        assertTrue(vm.state.value is UpdateViewModel.State.InstallerOpened)
    }

    @Test fun incompatible_apk_never_reaches_installer_and_retry_keeps_update_info() = runTest(dispatcher) {
        platform.verificationFailure = UpdateException("签名身份不匹配")
        vm.startDownload(info)
        advanceUntilIdle()
        val error = vm.state.value as UpdateViewModel.State.Error
        assertEquals("签名身份不匹配", error.message)
        assertEquals(info, (error.retry as UpdateViewModel.Retry.Download).info)
        assertEquals(0, platform.installs)
        platform.verificationFailure = null
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value is UpdateViewModel.State.Downloaded)
    }

    @Test fun incomplete_flow_is_not_a_download_success() = runTest(dispatcher) {
        source.downloadBlock = { _, _, _ -> flow { emit(UpdateChecker.DownloadEvent.Progress(2, 20)) } }
        vm.startDownload(info)
        advanceUntilIdle()
        assertTrue(vm.state.value is UpdateViewModel.State.Error)
        assertEquals(0, platform.installs)
    }

    @Test fun permission_return_is_explicit_and_does_not_auto_open_installer() = runTest(dispatcher) {
        platform.permission = false
        vm.install(info, platform.target)
        advanceUntilIdle()
        assertTrue(vm.state.value is UpdateViewModel.State.NeedInstallPermission)
        vm.openInstallPermission()
        vm.onHostPaused()
        vm.onHostResumed()
        assertTrue((vm.state.value as UpdateViewModel.State.NeedInstallPermission).returnedWithoutPermission)
        vm.openInstallPermission()
        vm.onHostPaused()
        platform.permission = true
        vm.onHostResumed()
        assertTrue(vm.state.value is UpdateViewModel.State.Downloaded)
        assertEquals(0, platform.installs)
        vm.install(info, platform.target)
        advanceUntilIdle()
        assertEquals(1, platform.installs)
        vm.onHostPaused()
        vm.onHostResumed()
        assertTrue((vm.state.value as UpdateViewModel.State.Downloaded).returnedFromInstaller)
    }

    @Test fun permission_settings_and_installer_launch_failures_remain_retryable() = runTest(dispatcher) {
        platform.permission = false
        vm.install(info, platform.target)
        advanceUntilIdle()
        platform.settingsFailure = UpdateException("无法打开设置")
        vm.openInstallPermission()
        assertTrue((vm.state.value as UpdateViewModel.State.Error).retry is UpdateViewModel.Retry.Install)
        platform.permission = true
        platform.installFailure = UpdateException("无法打开安装器")
        vm.retry()
        advanceUntilIdle()
        assertEquals("无法打开安装器", (vm.state.value as UpdateViewModel.State.Error).message)
    }

    @Test fun failed_check_does_not_mark_successful_check_time_and_direct_retry_is_explicit() = runTest(dispatcher) {
        source.hasConfiguredProxy = true
        source.checkBlock = { UpdateChecker.CheckResult.Failed("网络失败") }
        vm.check(true)
        advanceUntilIdle()
        assertEquals(0, prefs.lastCheckAt)
        assertTrue((vm.state.value as UpdateViewModel.State.Error).canTryDirect)
        vm.retry(direct = true)
        advanceUntilIdle()
        assertEquals(listOf(UpdateRoute.APP_SETTINGS, UpdateRoute.DIRECT), source.checks)
        assertFalse((vm.state.value as UpdateViewModel.State.Error).canTryDirect)
    }

    @Test fun successful_auto_check_is_throttled_and_skipped_release_stays_quiet() = runTest(dispatcher) {
        prefs.skippedVersion = info.versionName
        source.checkBlock = { UpdateChecker.CheckResult.Available(info) }
        vm.autoCheckOnce()
        advanceUntilIdle()
        assertEquals(UpdateViewModel.State.Idle, vm.state.value)
        assertTrue(prefs.lastCheckAt > 0)
        vm.autoCheckIfDue()
        advanceUntilIdle()
        assertEquals(1, source.checks.size)
        vm.check(true)
        advanceUntilIdle()
        assertTrue(vm.state.value is UpdateViewModel.State.Available)
    }

    @Test fun wifi_auto_download_requires_opt_in_and_never_auto_installs() = runTest(dispatcher) {
        prefs.wifiAutoDownload = true
        platform.wifi = true
        source.checkBlock = { UpdateChecker.CheckResult.Available(info) }
        vm.autoCheckOnce()
        advanceUntilIdle()
        assertEquals(1, source.downloads.size)
        assertTrue(vm.state.value is UpdateViewModel.State.Downloaded)
        assertEquals(0, platform.installs)
    }

    @Test fun invalid_negative_check_time_does_not_disable_checks() = runTest(dispatcher) {
        prefs.lastCheckAt = Long.MIN_VALUE
        vm.autoCheckOnce()
        advanceUntilIdle()
        assertEquals(1, source.checks.size)
        assertTrue(prefs.lastCheckAt > 0)
    }

    @Test fun preference_write_failure_is_not_reported_as_a_successful_check() = runTest(dispatcher) {
        prefs.failWrites = true
        vm.check(true)
        advanceUntilIdle()
        assertTrue(vm.state.value is UpdateViewModel.State.Error)
    }

    private class MemoryPreferences : UpdatePreferences {
        var failWrites = false
        override var skippedVersion: String? = null
        override var lastCheckAt: Long = 0
            set(value) {
                if (failWrites) throw IOException("fixture write error")
                field = value
            }
        override var wifiAutoDownload = false
    }

    private class FakeSource : UpdateSource {
        override var hasConfiguredProxy = false
        val checks = mutableListOf<UpdateRoute>()
        val downloads = mutableListOf<UpdateRoute>()
        var checkBlock: suspend (UpdateRoute) -> UpdateChecker.CheckResult = {
            UpdateChecker.CheckResult.UpToDate("0.1.33-debug")
        }
        var downloadBlock: (UpdateInfo, File, UpdateRoute) -> Flow<UpdateChecker.DownloadEvent> = { info, target, _ ->
            flow { emit(UpdateChecker.DownloadEvent.Done(target, requireNotNull(info.apkSha256))) }
        }

        override suspend fun check(route: UpdateRoute): UpdateChecker.CheckResult {
            checks += route
            return checkBlock(route)
        }
        override fun download(info: UpdateInfo, targetFile: File, route: UpdateRoute): Flow<UpdateChecker.DownloadEvent> {
            downloads += route
            return downloadBlock(info, targetFile, route)
        }
    }

    private class FakePlatform(val target: File) : UpdatePlatform {
        val verified = mutableListOf<UpdateInfo>()
        var installs = 0
        var permission = true
        var wifi = false
        var verificationFailure: UpdateException? = null
        var settingsFailure: UpdateException? = null
        var installFailure: UpdateException? = null
        override suspend fun createDownloadTarget(info: UpdateInfo) = target
        override suspend fun verifyApk(info: UpdateInfo, file: File) {
            verified += info
            verificationFailure?.let { throw it }
        }
        override fun isUnmeteredWifi() = wifi
        override fun canInstall() = permission
        override fun openInstallPermission() { settingsFailure?.let { throw it } }
        override fun install(file: File) {
            installFailure?.let { throw it }
            installs++
        }
        override fun openReleasePage() = Unit
    }
}
