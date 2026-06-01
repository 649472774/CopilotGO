package com.tongxie.copilotgo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.data.proxy.ProxyType
import com.tongxie.copilotgo.ui.viewmodel.ProxyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsProxyScreen(
    proxyVm: ProxyViewModel,
    onBack: () -> Unit
) {
    val testState by proxyVm.testState.collectAsState()
    var editingConfig by remember { mutableStateOf(proxyVm.config.value) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("代理") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 💡 Clash 默认值速查（顶部显眼）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF8E1)
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "💡 Clash for Android 默认值",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF5D4037)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "地址：127.0.0.1\n" +
                                "HTTP 端口：7890\n" +
                                "SOCKS5 端口：7891\n" +
                                "（无需打开 VPN 模式，仅需开启「混合端口」/「HTTP 端口」）",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF5D4037)
                    )
                }
            }

            // 启用代理 Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("启用代理", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "所有 API 请求经本地代理转发",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = editingConfig.enabled,
                    onCheckedChange = { editingConfig = editingConfig.copy(enabled = it) }
                )
            }

            Spacer(Modifier.height(4.dp))

            // 类型选择 SegmentedButton
            val proxyTypes = listOf(ProxyType.HTTP, ProxyType.SOCKS5)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                proxyTypes.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = proxyTypes.size),
                        selected = editingConfig.type == type,
                        onClick = { editingConfig = editingConfig.copy(type = type) },
                        enabled = editingConfig.enabled,
                        label = { Text(type.name) }
                    )
                }
            }

            OutlinedTextField(
                value = editingConfig.host,
                onValueChange = { editingConfig = editingConfig.copy(host = it) },
                label = { Text("地址") },
                placeholder = { Text("127.0.0.1") },
                enabled = editingConfig.enabled,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = editingConfig.port.toString(),
                onValueChange = { v ->
                    val p = v.filter { it.isDigit() }.toIntOrNull() ?: editingConfig.port
                    editingConfig = editingConfig.copy(port = p)
                },
                label = { Text("端口") },
                placeholder = { Text("7890") },
                enabled = editingConfig.enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { proxyVm.update(editingConfig) },
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }

                OutlinedButton(
                    onClick = { proxyVm.runHealthCheck() },
                    enabled = testState !is ProxyViewModel.TestState.Testing,
                    modifier = Modifier.weight(1f)
                ) {
                    if (testState is ProxyViewModel.TestState.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("测试连接")
                    }
                }
            }

            when (val ts = testState) {
                is ProxyViewModel.TestState.Result -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (ts.success)
                                Color(0xFFE8F5E9)
                            else
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = ts.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ts.success) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                else -> {}
            }

            Spacer(Modifier.height(8.dp))

            // 使用说明
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "使用提示",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "• 代理由本机 Clash / V2Ray 等软件提供\n" +
                                "• Clash for Android：关闭「VPN 模式」，开启 HTTP / SOCKS5 端口（一般为 7890）和「允许局域网连接」\n" +
                                "• 模拟器 127.0.0.1 即宿主机；真机请填 Clash 监听的 IP 地址\n" +
                                "• 仅影响 API 请求（聊天 / 登录 / 模型列表），不影响其他 APP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
