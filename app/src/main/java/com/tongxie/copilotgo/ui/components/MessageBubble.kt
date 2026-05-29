package com.tongxie.copilotgo.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.ui.theme.AssistantBubble
import com.tongxie.copilotgo.ui.theme.AssistantBubbleDark
import com.tongxie.copilotgo.ui.theme.UserBubble
import com.tongxie.copilotgo.ui.theme.UserBubbleDark
import dev.jeziellago.compose.markdowntext.MarkdownText

@Composable
fun MessageBubble(message: UiMessage) {
    val isUser = message.role == "user"
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bubbleColor = if (isUser) {
        if (isDark) UserBubbleDark else UserBubble
    } else {
        if (isDark) AssistantBubbleDark else AssistantBubble
    }

    val alignment = if (isUser) Alignment.End else Alignment.Start

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
            if (!isUser && message.content.isNotEmpty()) {
                val clipboard = LocalClipboardManager.current
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(message.content)) },
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
                .padding(12.dp)
        ) {
            if (message.content.isEmpty() && message.isStreaming) {
                Text(
                    "…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                MarkdownText(
                    markdown = message.content.ifEmpty { " " },
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
