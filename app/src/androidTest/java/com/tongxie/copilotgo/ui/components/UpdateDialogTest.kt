package com.tongxie.copilotgo.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.update.UpdateChecker
import com.tongxie.copilotgo.data.update.UpdateInfo
import com.tongxie.copilotgo.data.update.UpdatePlatform
import com.tongxie.copilotgo.data.update.UpdatePreferences
import com.tongxie.copilotgo.data.update.UpdateRoute
import com.tongxie.copilotgo.data.update.UpdateSource
import com.tongxie.copilotgo.ui.viewmodel.UpdateViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class UpdateDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: UpdateViewModel
    private val info = UpdateInfo("0.2.0", "v0.2.0", "Fixture", "", "https://github.com/fixture.apk", 20, "")

    @After fun cleanup() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    @Test fun checking_can_be_cancelled_with_large_text() {
        showFixture()
        compose.runOnIdle { vm.check(true) }
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(UpdateViewModel.State.Idle, vm.state.value) }
    }

    @Test fun download_can_be_cancelled_with_large_text() {
        showFixture()
        compose.runOnIdle { vm.startDownload(info) }
        compose.onNodeWithText("取消下载").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(UpdateViewModel.State.Idle, vm.state.value) }
    }

    private fun showFixture() {
        val source = object : UpdateSource {
            override val hasConfiguredProxy = false
            override suspend fun check(route: UpdateRoute): UpdateChecker.CheckResult = awaitCancellation()
            override fun download(info: UpdateInfo, targetFile: File, route: UpdateRoute) = flow {
                emit(UpdateChecker.DownloadEvent.Progress(3, info.apkSize))
                awaitCancellation()
            }
        }
        val prefs = object : UpdatePreferences {
            override var skippedVersion: String? = null
            override var lastCheckAt = 0L
            override var wifiAutoDownload = false
        }
        val platform = object : UpdatePlatform {
            override suspend fun createDownloadTarget(info: UpdateInfo) = File("unused-fixture.apk")
            override suspend fun verifyApk(info: UpdateInfo, file: File) = error("Not used by this fixture")
            override fun isUnmeteredWifi() = false
            override fun canInstall() = false
            override fun openInstallPermission() = error("No external settings in fixture")
            override fun install(file: File) = error("No installer in fixture")
            override fun openReleasePage() = error("No browser in fixture")
        }
        vm = UpdateViewModel(source, prefs, platform)
        compose.setContent {
            val state by vm.state.collectAsState()
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme { UpdateDialog(state, vm) }
            }
        }
    }
}
