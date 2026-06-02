package com.tongxie.copilotgo.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.ui.viewmodel.UpdateViewModel

/**
 * 根据 [UpdateViewModel.State] 渲染更新流程的对话框。
 * Idle / Checking 不渲染任何内容（Checking 由调用方在按钮上显示 loading）。
 */
@Composable
fun UpdateDialog(
    state: UpdateViewModel.State,
    vm: UpdateViewModel
) {
    val context = LocalContext.current

    when (state) {
        is UpdateViewModel.State.Idle,
        is UpdateViewModel.State.Checking -> Unit

        is UpdateViewModel.State.UpToDate -> {
            AlertDialog(
                onDismissRequest = { vm.dismiss() },
                title = { Text("已是最新版本") },
                text = { Text("当前版本 ${state.currentVersion} 已是最新。") },
                confirmButton = { TextButton(onClick = { vm.dismiss() }) { Text("好的") } }
            )
        }

        is UpdateViewModel.State.Available -> {
            val info = state.info
            AlertDialog(
                onDismissRequest = { vm.dismiss() },
                title = { Text("发现新版本 ${info.versionName}") },
                text = {
                    Column {
                        if (info.releaseName.isNotBlank() && info.releaseName != info.tagName) {
                            Text(
                                info.releaseName,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Text(
                            "更新说明：",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                        Column(
                            modifier = Modifier
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                info.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.startDownload(info) }) { Text("立即更新") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { vm.skipVersion(info) }) { Text("忽略此版本") }
                        TextButton(onClick = { vm.dismiss() }) { Text("稍后") }
                    }
                }
            )
        }

        is UpdateViewModel.State.Downloading -> {
            val pct = if (state.total > 0) {
                (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f)
            } else null
            AlertDialog(
                onDismissRequest = { /* 下载中不可取消 */ },
                title = { Text("正在下载更新") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (pct != null) {
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${(pct * 100).toInt()}%  ·  ${formatSize(state.downloaded)} / ${formatSize(state.total)}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "已下载 ${formatSize(state.downloaded)}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateViewModel.State.Downloaded -> {
            AlertDialog(
                onDismissRequest = { vm.dismiss() },
                title = { Text("下载完成") },
                text = { Text("新版本已下载完成，正在唤起系统安装器。若未弹出，请点「立即安装」。") },
                confirmButton = {
                    TextButton(onClick = { vm.install(state.info, state.file) }) { Text("立即安装") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismiss() }) { Text("关闭") }
                }
            )
        }

        is UpdateViewModel.State.NeedInstallPermission -> {
            AlertDialog(
                onDismissRequest = { vm.dismiss() },
                title = { Text("需要安装权限") },
                text = { Text("系统要求允许本应用安装未知来源应用。请在设置中开启后返回，再点「我已授权，安装」。") },
                confirmButton = {
                    TextButton(onClick = { vm.openInstallPermission() }) { Text("去授权") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { vm.install(state.info, state.file) }) { Text("我已授权，安装") }
                        TextButton(onClick = { vm.dismiss() }) { Text("取消") }
                    }
                }
            )
        }

        is UpdateViewModel.State.Error -> {
            AlertDialog(
                onDismissRequest = { vm.dismiss() },
                title = { Text("更新失败") },
                text = {
                    Text(
                        state.message,
                        textAlign = TextAlign.Start
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.dismiss()
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(Constants.GITHUB_RELEASES_PAGE))
                                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            )
                        }
                    }) { Text("打开发布页") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismiss() }) { Text("关闭") }
                }
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1) String.format("%.1f MB", mb)
    else String.format("%.0f KB", bytes / 1024.0)
}
