package com.tongxie.copilotgo.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tongxie.copilotgo.data.update.UpdateRoute
import com.tongxie.copilotgo.ui.viewmodel.UpdateViewModel

@Composable
fun UpdateDialog(state: UpdateViewModel.State, vm: UpdateViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> vm.onHostPaused()
                Lifecycle.Event.ON_RESUME -> vm.onHostResumed()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    when (state) {
        UpdateViewModel.State.Idle -> Unit
        is UpdateViewModel.State.Checking -> if (state.manual) {
            AlertDialog(
                onDismissRequest = vm::dismiss,
                title = { Text("正在检查更新") },
                text = {
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        Text(routeLabel(state.route))
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                        Text("网络检查超时为 30 秒，可随时取消。", modifier = Modifier.padding(top = 8.dp))
                    }
                },
                confirmButton = { ActionButton("取消", vm::dismiss) }
            )
        }
        is UpdateViewModel.State.UpToDate -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("已是最新版本") },
            text = { DialogMessage("当前版本 ${state.currentVersion} 已是最新稳定版。") },
            confirmButton = { ActionButton("好的", vm::dismiss) }
        )
        is UpdateViewModel.State.Available -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("发现新版本 ${state.info.versionName}") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text("${formatSize(state.info.apkSize)} · ${routeLabel(state.route)}")
                    Text(
                        state.info.changelog,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        "下载后会校验安装包；是否安装由你和系统确认。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            },
            confirmButton = { ActionButton("下载更新") { vm.startDownload(state.info) } },
            dismissButton = {
                Column {
                    ActionButton("忽略此版本") { vm.skipVersion(state.info) }
                    ActionButton("稍后", vm::dismiss)
                }
            }
        )
        is UpdateViewModel.State.Downloading -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("正在下载更新") },
            text = {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    val progress = if (state.total > 0) (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) else null
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "${formatSize(state.downloaded)} / ${formatSize(state.total)}",
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(routeLabel(state.route), style = MaterialTheme.typography.bodySmall)
                    Text("最多等待 5 分钟，取消会清理未完成的下载。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { ActionButton("取消下载", vm::dismiss) }
        )
        is UpdateViewModel.State.Verifying -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("正在校验安装包") },
            text = { DialogMessage("检查 SHA-256、实际包名、版本和签名身份，不会自动安装。") },
            confirmButton = { ActionButton("取消", vm::dismiss) }
        )
        is UpdateViewModel.State.Downloaded -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("安装包已就绪") },
            text = {
                DialogMessage(
                    if (state.returnedFromInstaller) "已从系统安装器返回，应用无法据此确认安装结果。若未完成，可重试安装。"
                    else "安装包已通过校验。安装前还会重新检查；系统将做最终签名验证并请求确认。"
                )
            },
            confirmButton = { ActionButton("立即安装") { vm.install(state.info, state.file) } },
            dismissButton = { ActionButton("稍后", vm::dismiss) }
        )
        is UpdateViewModel.State.NeedInstallPermission -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("需要安装权限") },
            text = {
                DialogMessage(
                    if (state.returnedWithoutPermission) "尚未获得安装权限。可以重新打开设置，也可以取消；不会自动反复跳转。"
                    else "请在系统设置中允许本应用安装未知应用。返回后会检查授权状态，再由你确认安装。"
                )
            },
            confirmButton = { ActionButton("打开权限设置", vm::openInstallPermission) },
            dismissButton = { ActionButton("取消", vm::dismiss) }
        )
        is UpdateViewModel.State.InstallerOpened -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("已请求系统安装") },
            text = { DialogMessage("请在系统安装器中确认或取消。若未弹出，可以重试；打开安装器不代表已经安装成功。") },
            confirmButton = { ActionButton("重新打开安装器") { vm.install(state.info, state.file) } },
            dismissButton = { ActionButton("关闭", vm::dismiss) }
        )
        is UpdateViewModel.State.Error -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("更新未完成") },
            text = {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Text(state.message)
                    if (state.canTryDirect) ActionButton("直连重试（不使用代理）") { vm.retry(direct = true) }
                    if (state.retry is UpdateViewModel.Retry.Download || state.retry is UpdateViewModel.Retry.Install) {
                        ActionButton("重新检查更新") { vm.check(manual = true) }
                    }
                    ActionButton("打开发布页", vm::openReleasePage)
                }
            },
            confirmButton = {
                if (state.retry != null) ActionButton("重试") { vm.retry() }
            },
            dismissButton = { ActionButton("关闭", vm::dismiss) }
        )
    }
}

@Composable
private fun DialogMessage(text: String) {
    Text(text, modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(text) }
}

private fun routeLabel(route: UpdateRoute): String = when (route) {
    UpdateRoute.APP_SETTINGS -> "使用应用网络设置"
    UpdateRoute.DIRECT -> "直连 GitHub（不使用应用代理）"
}

private fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "未知大小"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
