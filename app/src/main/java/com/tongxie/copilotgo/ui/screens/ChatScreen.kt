package com.tongxie.copilotgo.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.ui.components.MessageBubble
import com.tongxie.copilotgo.ui.components.ModelPickerInline
import com.tongxie.copilotgo.ui.viewmodel.ChatViewModel
import com.tongxie.copilotgo.ui.viewmodel.SessionListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modelsVm: SessionListViewModel,
    onBack: () -> Unit
) {
    val session by viewModel.session.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val error by viewModel.error.collectAsState()
    val models by modelsVm.models.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<Pair<String, String>>() } // name -> text content
    // 图片：name -> data URI(base64)
    val imageItems = remember { mutableStateListOf<Pair<String, String>>() }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val name = getFileName(context, uri)
                val content = readUriText(context, uri)
                attachments.add(name to content)
            }
        }
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            runCatching {
                val name = getFileName(context, uri)
                val dataUri = readUriAsDataUri(context, uri)
                if (dataUri != null) imageItems.add(name to dataUri)
            }
        }
    }

    LaunchedEffect(Unit) {
        modelsVm.refreshModels()
    }

    // 拉到真实模型列表后，自动迁移旧会话不可用 model
    LaunchedEffect(models, session?.id) {
        val s = session ?: return@LaunchedEffect
        if (models.isEmpty()) return@LaunchedEffect
        val ids = models.map { it.id }
        if (s.model !in ids) {
            viewModel.setModel(ids.first())
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // 监听 size + 最后一条 assistant content 长度变化（流式追加时也滚动）
    val lastAssistantLen = session?.messages?.lastOrNull { it.role == "assistant" }?.content?.length ?: 0
    val rev = session?.revision ?: 0
    LaunchedEffect(rev, session?.messages?.size, lastAssistantLen, sending) {
        val s = session
        if (s != null && s.messages.isNotEmpty()) {
            listState.animateScrollToItem(s.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val s = session
                    Column {
                        Text(
                            s?.title ?: "会话",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        if (s != null) {
                            ModelPickerInline(
                                currentModel = s.model,
                                models = models,
                                onSelect = { viewModel.setModel(it) }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            val s = session
            if (s == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(s.messages, key = { it.id }) { msg ->
                        MessageBubble(message = msg)
                    }
                }
            }

            if (attachments.isNotEmpty() || imageItems.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        attachments.forEachIndexed { idx, (name, _) ->
                            FilterChip(
                                selected = true,
                                onClick = { attachments.removeAt(idx) },
                                label = { Text(name) },
                                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "移除") }
                            )
                        }
                        imageItems.forEachIndexed { idx, (name, _) ->
                            FilterChip(
                                selected = true,
                                onClick = { imageItems.removeAt(idx) },
                                label = { Text("🖼 $name") },
                                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "移除") }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(onClick = { pickFile.launch(arrayOf("text/*", "application/json", "*/*")) }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "附件")
                }
                IconButton(onClick = { pickImages.launch("image/*") }) {
                    Icon(Icons.Default.Image, contentDescription = "图片")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("输入消息…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp)
                )
                Spacer(Modifier.size(8.dp))
                if (sending) {
                    IconButton(onClick = { viewModel.stopStreaming() }) {
                        Icon(Icons.Default.Stop, contentDescription = "停止")
                    }
                } else {
                    val canSend = input.isNotBlank() || imageItems.isNotEmpty()
                    IconButton(
                        onClick = {
                            val text = input.trim()
                            val atts = attachments.map { it.second }
                            val imgs = imageItems.map { it.second }
                            viewModel.send(text, atts, imgs)
                            input = ""
                            attachments.clear()
                            imageItems.clear()
                        },
                        enabled = canSend
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

private fun getFileName(ctx: Context, uri: Uri): String {
    val cursor = ctx.contentResolver.query(uri, null, null, null, null) ?: return uri.lastPathSegment ?: "file"
    return cursor.use {
        if (it.moveToFirst()) {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) it.getString(idx) else uri.lastPathSegment ?: "file"
        } else uri.lastPathSegment ?: "file"
    }
}

private fun readUriText(ctx: Context, uri: Uri): String {
    ctx.contentResolver.openInputStream(uri).use { input ->
        val bytes = input?.readBytes() ?: return ""
        val capped = if (bytes.size > 1_048_576) bytes.copyOf(1_048_576) else bytes
        return String(capped, Charsets.UTF_8)
    }
}

/** 把图片 Uri 读成 base64 data URI（限制 ~5MB 原始数据，超过裁掉，避免请求过大） */
private fun readUriAsDataUri(ctx: Context, uri: Uri): String? {
    val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
    ctx.contentResolver.openInputStream(uri).use { input ->
        val bytes = input?.readBytes() ?: return null
        // 简单限制 ~5MB
        if (bytes.size > 5 * 1024 * 1024) {
            return null
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:$mime;base64,$b64"
    }
}
