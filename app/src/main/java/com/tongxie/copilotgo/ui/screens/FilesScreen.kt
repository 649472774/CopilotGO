package com.tongxie.copilotgo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.data.storage.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    paths: AppPaths,
    onBack: () -> Unit
) {
    var currentDir by remember { mutableStateOf(paths.root) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var preview by remember { mutableStateOf<File?>(null) }
    var previewContent by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 目录刷新（Bug 12 修复）：listFiles + sort 也搬到 IO，避免大目录卡 UI
    suspend fun refresh(dir: File): List<File> = withContext(Dispatchers.IO) {
        (dir.listFiles()?.toList() ?: emptyList())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    LaunchedEffect(currentDir) {
        entries = refresh(currentDir)
    }

    // 预览改成异步读取前 16 KB，避免主线程 readText 整个大文件
    LaunchedEffect(preview) {
        val pf = preview
        previewContent = if (pf == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    pf.inputStream().use { fis ->
                        val buf = ByteArray(16 * 1024)
                        val n = fis.read(buf)
                        if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
                    }
                }.getOrDefault("(二进制文件或读取失败)")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文件 · ${currentDir.name}") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentDir.absolutePath == paths.root.absolutePath) {
                            onBack()
                        } else {
                            currentDir = currentDir.parentFile ?: paths.root
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {

            Text(
                "私有目录：${paths.root.absolutePath}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(12.dp)
            )

            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("(空文件夹)", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.absolutePath }) { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (f.isDirectory) currentDir = f
                                    else preview = f
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                if (f.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                contentDescription = null,
                                tint = if (f.isDirectory)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (f.isDirectory) "目录" else "${f.length()} bytes",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            IconButton(onClick = {
                                // Bug 12 修复：deleteRecursively 同步遍历目录在 Main 上会卡 UI
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        runCatching { f.deleteRecursively() }
                                    }
                                    entries = refresh(currentDir)
                                }
                            }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    val pf = preview
    if (pf != null) {
        AlertDialog(
            onDismissRequest = { preview = null },
            confirmButton = {
                TextButton(onClick = { preview = null }) { Text("关闭") }
            },
            title = { Text(pf.name) },
            text = {
                Text(
                    previewContent ?: "正在读取…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }
}
