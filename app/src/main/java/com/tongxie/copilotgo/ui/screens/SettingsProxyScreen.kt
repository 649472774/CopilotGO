package com.tongxie.copilotgo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.data.proxy.ProxyConfig
import com.tongxie.copilotgo.data.proxy.ProxyType
import com.tongxie.copilotgo.ui.viewmodel.ProxyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsProxyScreen(
    proxyVm: ProxyViewModel,
    onBack: () -> Unit
) {
    val savedConfig by proxyVm.config.collectAsState()
    val testState by proxyVm.testState.collectAsState()
    var editingConfig by remember { mutableStateOf(proxyVm.config.value) }
    var portText by remember { mutableStateOf(proxyVm.config.value.port.toString()) }
    var formDirty by remember { mutableStateOf(false) }
    var authExpanded by remember { mutableStateOf(proxyVm.config.value.requiresAuth) }
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(savedConfig) {
        if (!formDirty) {
            editingConfig = savedConfig
            portText = savedConfig.port.toString()
            authExpanded = savedConfig.requiresAuth
        }
    }

    val portValue = portText.toIntOrNull()
    val hostError = editingConfig.host.isBlank()
    val portError = portValue == null || portValue !in 1..65535
    val isFormValid = !hostError && !portError
    val hasUnsavedChanges = editingConfig != savedConfig
    val inputsEnabled = editingConfig.enabled
    val testing = testState is ProxyViewModel.TestState.Testing

    fun updateDraft(config: ProxyConfig, newPortText: String = config.port.toString()) {
        editingConfig = config
        portText = newPortText
        formDirty = true
    }

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
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusHeader(savedConfig)

            SectionCard(title = "基本设置") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用代理", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "所有 API 请求经本地代理转发",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = editingConfig.enabled,
                        onCheckedChange = {
                            updateDraft(editingConfig.copy(enabled = it), portText)
                        }
                    )
                }

                val proxyTypes = listOf(ProxyType.HTTP, ProxyType.SOCKS5)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    proxyTypes.forEachIndexed { index, type ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = proxyTypes.size),
                            selected = editingConfig.type == type,
                            onClick = { updateDraft(editingConfig.copy(type = type), portText) },
                            enabled = inputsEnabled,
                            label = { Text(type.name) }
                        )
                    }
                }

                OutlinedTextField(
                    value = editingConfig.host,
                    onValueChange = { updateDraft(editingConfig.copy(host = it), portText) },
                    label = { Text("地址") },
                    placeholder = { Text("127.0.0.1") },
                    enabled = inputsEnabled,
                    isError = hostError,
                    supportingText = {
                        if (hostError) Text("地址不能为空") else Text("例如 127.0.0.1 或 10.0.2.2")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { value ->
                        val digits = value.filter { it.isDigit() }
                        val parsedPort = digits.toIntOrNull()
                        editingConfig = editingConfig.copy(port = parsedPort ?: 0)
                        portText = digits
                        formDirty = true
                    },
                    label = { Text("端口") },
                    placeholder = { Text("7890") },
                    enabled = inputsEnabled,
                    isError = portError,
                    supportingText = {
                        if (portError) Text("端口必须在 1–65535 之间") else Text("HTTP 常用 7890，SOCKS5 常用 7891")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            SectionCard(title = "认证") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("需要认证", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "用于需要用户名和密码的代理服务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = authExpanded,
                        enabled = inputsEnabled,
                        onCheckedChange = { checked ->
                            authExpanded = checked
                            updateDraft(
                                if (checked) editingConfig else editingConfig.copy(username = "", password = ""),
                                portText
                            )
                        }
                    )
                }

                if (authExpanded) {
                    OutlinedTextField(
                        value = editingConfig.username,
                        onValueChange = { updateDraft(editingConfig.copy(username = it), portText) },
                        label = { Text("用户名") },
                        enabled = inputsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editingConfig.password,
                        onValueChange = { updateDraft(editingConfig.copy(password = it), portText) },
                        label = { Text("密码") },
                        enabled = inputsEnabled,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            SectionCard(title = "快速预设") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { updateDraft(editingConfig.copy(type = ProxyType.HTTP, port = 7890)) },
                        label = { Text("Clash HTTP 7890") },
                        enabled = inputsEnabled
                    )
                    AssistChip(
                        onClick = { updateDraft(editingConfig.copy(type = ProxyType.SOCKS5, port = 7891)) },
                        label = { Text("Clash SOCKS5 7891") },
                        enabled = inputsEnabled
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { updateDraft(editingConfig.copy(host = "10.0.2.2"), portText) },
                        label = { Text("模拟器 10.0.2.2") },
                        enabled = inputsEnabled
                    )
                    AssistChip(
                        onClick = { updateDraft(editingConfig.copy(host = "127.0.0.1"), portText) },
                        label = { Text("本机 127.0.0.1") },
                        enabled = inputsEnabled
                    )
                }
            }

            SectionCard(title = "操作") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            proxyVm.update(editingConfig)
                            formDirty = false
                        },
                        enabled = hasUnsavedChanges && isFormValid,
                        modifier = Modifier.weight(1f)
                    ) { Text("保存") }

                    OutlinedButton(
                        onClick = { proxyVm.testConfig(editingConfig) },
                        enabled = isFormValid && !testing,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("测试连接")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasUnsavedChanges) {
                        Text(
                            "● 有未保存的修改",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFA000)
                        )
                    } else {
                        Spacer(Modifier.height(1.dp))
                    }
                    TextButton(
                        onClick = {
                            editingConfig = savedConfig
                            portText = savedConfig.port.toString()
                            authExpanded = savedConfig.requiresAuth
                            formDirty = false
                        },
                        enabled = hasUnsavedChanges
                    ) { Text("重置") }
                }
            }

            TestResultCard(testState)

            SectionCard(title = "说明") {
                Text(
                    "• 代理由本地 Clash / V2Ray 等软件提供\n" +
                        "• Clash：关闭 VPN 模式，开启 HTTP / SOCKS5 端口（7890 / 7891）和「允许局域网连接」\n" +
                        "• 模拟器主机回环地址为 10.0.2.2\n" +
                        "• 真机填写 Clash 监听的局域网 IP；若 Clash 运行在手机上可用 127.0.0.1\n" +
                        "• 仅影响 API 请求（聊天 / 登录 / 模型列表）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusHeader(savedConfig: ProxyConfig) {
    val enabled = savedConfig.enabled
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                Color(0xFFE8F5E9)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("当前状态", style = MaterialTheme.typography.labelLarge)
            Text(
                text = if (enabled) {
                    "已启用 · ${savedConfig.type.name} · ${savedConfig.host}:${savedConfig.port}"
                } else {
                    "未启用（直连）"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    Color(0xFF1B5E20)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun TestResultCard(testState: ProxyViewModel.TestState) {
    val result = testState as? ProxyViewModel.TestState.Result ?: return
    SectionCard(title = "测试结果") {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (result.success) {
                    Color(0xFFE8F5E9)
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Text(
                text = result.message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (result.success) {
                    Color(0xFF1B5E20)
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
