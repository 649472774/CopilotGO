package com.tongxie.copilotgo.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.ui.theme.AssistantBubble
import com.tongxie.copilotgo.ui.theme.AssistantBubbleDark
import com.tongxie.copilotgo.ui.theme.UserBubble
import com.tongxie.copilotgo.ui.theme.UserBubbleDark

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: UiMessage,
    onRegenerate: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val isUser = message.role == "user"
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bubbleColor = if (isUser) {
        if (isDark) UserBubbleDark else UserBubble
    } else {
        if (isDark) AssistantBubbleDark else AssistantBubble
    }

    val alignment = if (isUser) Alignment.End else Alignment.Start
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // 长按 → 复制到剪贴板 + Toast 提示
    val copyMessage = {
        if (message.content.isNotEmpty()) {
            clipboard.setText(AnnotatedString(message.content))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (isUser) "我" else "Copilot",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            // assistant 保留快捷复制按钮（一键，无需长按）
            if (!isUser && message.content.isNotEmpty()) {
                IconButton(
                    onClick = { copyMessage() },
                    modifier = Modifier.padding(0.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        modifier = Modifier.padding(0.dp)
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                // 长按弹出操作菜单（复制 / 重新生成 / 编辑重发 / 删除），短按无操作
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                    onLongClick = { menuOpen = true }
                )
                .padding(12.dp)
        ) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (message.content.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        onClick = { menuOpen = false; copyMessage() }
                    )
                }
                if (!isUser && onRegenerate != null) {
                    DropdownMenuItem(
                        text = { Text("重新生成") },
                        onClick = { menuOpen = false; onRegenerate() }
                    )
                }
                if (isUser && onEdit != null) {
                    DropdownMenuItem(
                        text = { Text("编辑重发") },
                        onClick = { menuOpen = false; onEdit() }
                    )
                }
                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
            if (message.content.isEmpty() && message.isStreaming) {
                TypingDots()
            } else {
                // 统一走 SimpleMarkdownText：流式中也实时渲染 markdown（对齐 ChatGPT 行为）。
                // SimpleMarkdownText 是纯 Kotlin 行扫描 + remember(markdown) 缓存，
                // 几 KB 文本毫秒级 parse，不会卡。
                // 未闭合的 **bold 会按原文显示，闭合瞬间变粗体——这正是 ChatGPT / Claude 的体验。
                SimpleMarkdownText(
                    markdown = message.content,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.Color.luminance(): Float {
    val r = red
    val g = green
    val b = blue
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}
