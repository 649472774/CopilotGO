package com.tongxie.copilotgo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.data.storage.AppPaths
import com.tongxie.copilotgo.ui.viewmodel.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authVm: AuthViewModel,
    paths: AppPaths,
    onLoggedOut: () -> Unit,
    onBack: () -> Unit
) {
    val state by authVm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("账号", style = MaterialTheme.typography.titleMedium)
            val s = state
            val sku = if (s is AuthState.LoggedIn) (s.sku ?: "未知") else "未登录"
            Text("Copilot SKU：$sku", style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    authVm.logout()
                    onLoggedOut()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) { Text("退出登录") }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("存储", style = MaterialTheme.typography.titleMedium)
            Text(
                paths.describe(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Text(
                "CopilotGo · V0.1\n非官方移动客户端，复用 GitHub Copilot 订阅。\n使用即视为接受可能违反 GitHub ToS 的风险。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
