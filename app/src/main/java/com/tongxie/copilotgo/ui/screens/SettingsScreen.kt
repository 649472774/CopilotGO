package com.tongxie.copilotgo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.BuildConfig
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.data.update.UpdatePrefs
import com.tongxie.copilotgo.ui.viewmodel.AuthViewModel
import com.tongxie.copilotgo.ui.viewmodel.ProxyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authVm: AuthViewModel,
    proxyVm: ProxyViewModel,
    onOpenAccount: () -> Unit,
    onOpenProxy: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit
) {
    val authState by authVm.state.collectAsState()
    val proxyConfig by proxyVm.config.collectAsState()
    val context = LocalContext.current
    val updatePrefs = remember(context) {
        UpdatePrefs(context.applicationContext)
    }
    var wifiAutoDownload by remember { mutableStateOf(updatePrefs.wifiAutoDownload) }

    val accountSummary = when (val s = authState) {
        is AuthState.LoggedIn -> "已登录 · ${s.sku ?: "未知 SKU"}"
        is AuthState.AwaitingUserAuthorization -> "等待 GitHub 授权"
        is AuthState.Failed -> "登录失败"
        AuthState.NotLoggedIn -> "未登录"
    }

    val proxySummary = proxySummaryText(proxyConfig)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsMenuCard(
                icon = Icons.Filled.AccountCircle,
                title = "账号",
                subtitle = accountSummary,
                onClick = onOpenAccount
            )

            SettingsMenuCard(
                icon = Icons.Filled.Settings,
                title = "代理",
                subtitle = proxySummary,
                onClick = onOpenProxy
            )

            SettingsSwitchCard(
                icon = Icons.Filled.Settings,
                title = "仅 Wi-Fi 自动下载更新",
                subtitle = "发现新版本时仅在未计费 Wi-Fi 下自动下载",
                checked = wifiAutoDownload,
                onCheckedChange = { enabled ->
                    wifiAutoDownload = enabled
                    updatePrefs.wifiAutoDownload = enabled
                }
            )

            SettingsMenuCard(
                icon = Icons.Filled.Folder,
                title = "存储",
                subtitle = "查看应用数据目录",
                onClick = onOpenStorage
            )

            SettingsMenuCard(
                icon = Icons.Filled.Info,
                title = "关于",
                subtitle = "版本 ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                onClick = onOpenAbout
            )
        }
    }
}

@Composable
private fun SettingsSwitchCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

private fun proxySummaryText(cfg: ProxyConfig): String {
    if (!cfg.enabled) return "未启用"
    val host = cfg.host.ifBlank { "—" }
    return "${cfg.type.name} · $host:${cfg.port}"
}

@Composable
private fun SettingsMenuCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
