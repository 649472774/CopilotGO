package com.tongxie.copilotgo.ui.components

import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tongxie.copilotgo.R
import com.tongxie.copilotgo.util.Logger
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UiAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val file: File?,
    val isImage: Boolean
)

@Composable
fun AttachmentStrip(
    attachments: List<UiAttachment>,
    onPreview: (UiAttachment) -> Unit,
    modifier: Modifier = Modifier,
    onRemove: ((String) -> Unit)? = null,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(attachments, key = { it.id }) { attachment ->
            OutlinedCard(
                onClick = { onPreview(attachment) },
                enabled = enabled,
                modifier = Modifier.width(if (compact) 208.dp else 176.dp)
            ) {
                Column {
                    if (attachment.isImage && !compact) {
                        AttachmentImage(
                            attachment = attachment,
                            modifier = Modifier.fillMaxWidth().height(96.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                attachment.name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (!compact) {
                                Text(
                                    formatAttachmentSize(attachment.sizeBytes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (onRemove != null) {
                            IconButton(
                                onClick = { onRemove(attachment.id) },
                                enabled = enabled
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.attachment_remove, attachment.name)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentImage(
    attachment: UiAttachment,
    modifier: Modifier = Modifier,
    maxDimension: Int = 512
) {
    val preview by produceState<PreviewImage>(PreviewImage.Loading, attachment.id, attachment.file, maxDimension) {
        value = withContext(Dispatchers.IO) {
            val file = attachment.file
            if (file == null) {
                PreviewImage.Unavailable
            } else {
                try {
                    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                        val width = info.size.width
                        val height = info.size.height
                        require(width in 1..32_768 && height in 1..32_768)
                        require(width.toLong() * height <= 64_000_000L)
                        val scale = minOf(1.0, maxDimension.toDouble() / maxOf(width, height))
                        decoder.setTargetSize(
                            (width * scale).toInt().coerceAtLeast(1),
                            (height * scale).toInt().coerceAtLeast(1)
                        )
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    PreviewImage.Ready(bitmap.asImageBitmap())
                } catch (_: IOException) {
                    Logger.w("Image preview could not be read")
                    PreviewImage.Unavailable
                } catch (_: IllegalArgumentException) {
                    Logger.w("Image preview rejected invalid dimensions or encoding")
                    PreviewImage.Unavailable
                } catch (_: SecurityException) {
                    Logger.w("Image preview access denied")
                    PreviewImage.Unavailable
                }
            }
        }
    }
    when (val current = preview) {
        is PreviewImage.Ready -> Image(
            bitmap = current.image,
            contentDescription = stringResource(R.string.attachment_image, attachment.name),
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
        PreviewImage.Loading -> Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        PreviewImage.Unavailable -> Text(
            stringResource(R.string.attachment_preview_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(12.dp)
        )
    }
}

private sealed interface PreviewImage {
    data object Loading : PreviewImage
    data object Unavailable : PreviewImage
    data class Ready(val image: ImageBitmap) : PreviewImage
}

@Composable
fun AttachmentPreviewDialog(attachment: UiAttachment, onDismiss: () -> Unit) {
    val unavailable = stringResource(R.string.attachment_preview_unavailable)
    val loading = stringResource(R.string.state_loading)
    val text by produceState(loading, attachment.id, attachment.file, unavailable) {
        if (!attachment.isImage) {
            value = withContext(Dispatchers.IO) {
                try {
                    attachment.file?.reader(Charsets.UTF_8)?.use { reader ->
                        val buffer = CharArray(8_192)
                        val count = reader.read(buffer)
                        if (count < 0) "" else String(buffer, 0, count)
                    } ?: unavailable
                } catch (_: IOException) {
                    Logger.w("Text preview could not be read")
                    unavailable
                } catch (_: SecurityException) {
                    Logger.w("Text preview access denied")
                    unavailable
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(attachment.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SelectionContainer {
                    Text(attachment.name, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    formatAttachmentSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelMedium
                )
                if (attachment.isImage) {
                    AttachmentImage(
                        attachment,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 400.dp),
                        maxDimension = 1_280
                    )
                } else {
                    Text(
                        stringResource(R.string.attachment_preview_limit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
