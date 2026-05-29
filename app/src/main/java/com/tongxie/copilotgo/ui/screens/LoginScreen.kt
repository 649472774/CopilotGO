package com.tongxie.copilotgo.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tongxie.copilotgo.data.auth.AuthState
import com.tongxie.copilotgo.ui.viewmodel.AuthViewModel

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoggedIn: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val deviceCode by viewModel.deviceCode.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state) {
        if (state is AuthState.LoggedIn) onLoggedIn()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "CopilotGo",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "用你的 GitHub Copilot 订阅，移动端聊代码",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(48.dp))

            when (val s = state) {
                AuthState.NotLoggedIn -> {
                    Button(
                        onClick = { viewModel.startLogin() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("开始登录 GitHub")
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "⚠ 本应用通过非官方方式访问 Copilot API，可能违反 GitHub 服务条款。\n仅供个人学习/自用，使用风险自负。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is AuthState.AwaitingUserAuthorization -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(24.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("验证码", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                s.userCode,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "在浏览器中打开 ${s.verificationUri}\n粘贴上面这串验证码完成授权",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            copyAndOpen(context, s.userCode, s.verificationUri)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("复制验证码并打开浏览器")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.cancel() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("取消") }
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("等待你在浏览器中授权…", style = MaterialTheme.typography.bodyMedium)
                }
                is AuthState.LoggedIn -> {
                    Text("登录成功", style = MaterialTheme.typography.titleMedium)
                }
                is AuthState.Failed -> {
                    Text(
                        "登录失败：${s.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.startLogin() }) { Text("重试") }
                }
            }
        }
    }
}

private fun copyAndOpen(ctx: Context, code: String, url: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("user_code", code))
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(intent)
}
