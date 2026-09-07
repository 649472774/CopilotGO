package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.storage.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

data class PreparedPrompt(
    val textRequest: ChatRequest? = null,
    val visionRequest: VisionRequest? = null,
    val truncated: Boolean = false
)

/** Bounds the wire prompt, never the stored conversation, and never cuts a turn in half. */
class PromptBuilder(private val attachments: AttachmentStore) {
    suspend fun prepare(messages: List<UiMessage>, model: ModelInfo): PreparedPrompt =
        withContext(Dispatchers.IO) {
            val systems = messages.filter { it.role == "system" }
            val turns = mutableListOf<MutableList<UiMessage>>()
            for (message in messages) {
                when (message.role) {
                    "user" -> turns.add(mutableListOf(message))
                    "assistant" -> if (message.finishReason != "orphaned") turns.lastOrNull()?.add(message)
                }
            }
            if (turns.isEmpty()) throw ModelUnavailableException("没有可发送的用户消息")
            val validTurns = turns.filterIndexed { index, turn ->
                index == turns.lastIndex || turn.any {
                    it.role == "assistant" && it.content.isNotBlank() &&
                        it.finishReason !in setOf("error", "cancelled", "interrupted")
                }
            }
            val limits = model.capabilities?.limits
            val tokenLimit = limits?.maxPromptTokens
                ?: limits?.maxContextWindowTokens?.let {
                    it - (limits.maxOutputTokens ?: minOf(4096, it / 4))
                }
            // UTF-8 bytes plus message framing conservatively bound tokens across CJK/emoji.
            val budget = minOf(
                Constants.MAX_PROMPT_CHARACTERS.toLong(),
                tokenLimit?.toLong()?.coerceAtLeast(1) ?: Constants.MAX_PROMPT_CHARACTERS.toLong()
            )
            var units = systems.sumOf { cost(it) }
            var imageBytes = systems.sumOf { imageBytes(it) }
            var imageCount = systems.sumOf { imageCount(it) }
            if (units > budget) throw ModelUnavailableException("系统提示超过模型上下文限制")
            val retained = mutableListOf<List<UiMessage>>()
            for (turn in validTurns.asReversed()) {
                val turnUnits = turn.sumOf { cost(it) }
                val turnBytes = turn.sumOf { imageBytes(it) }
                val turnImages = turn.sumOf { imageCount(it) }
                val fits = retained.size < Constants.MAX_PROMPT_TURNS &&
                    units + turnUnits <= budget &&
                    imageBytes + turnBytes <= AttachmentStore.MAX_TURN_BYTES &&
                    imageCount + turnImages <= AttachmentStore.MAX_ATTACHMENTS
                if (!fits) {
                    if (retained.isEmpty()) {
                        throw ModelUnavailableException("本轮内容超过模型上下文或附件限制，请减少文字或附件")
                    }
                    break
                }
                retained.add(turn)
                units += turnUnits
                imageBytes += turnBytes
                imageCount += turnImages
            }
            val history = systems + retained.asReversed().flatten()
            val vision = history.any { imageCount(it) > 0 }
            if (vision && !model.supportsVision) {
                throw ModelUnavailableException("保留的历史包含图片，请选择支持视觉的模型后继续")
            }
            val text = history.map { message ->
                buildString {
                    append(message.content)
                    for (ref in message.attachments.filter { it.kind == AttachmentKind.TEXT }) {
                        append("\n\n附件：").append(ref.name).append('\n')
                        append(attachments.readText(ref))
                    }
                }
            }
            val truncated = retained.size < validTurns.size || validTurns.size < turns.size
            if (!vision) {
                return@withContext PreparedPrompt(
                    textRequest = ChatRequest(model.id, history.mapIndexed { index, message ->
                        ChatMessage(message.role, text[index])
                    }),
                    truncated = truncated
                )
            }
            val resolvedImages = mutableMapOf<String, String>()
            val visionMessages = history.mapIndexed { index, message ->
                val parts = mutableListOf(VisionContentPart(type = "text", text = text[index]))
                for (url in message.imageUrls) {
                    validateImageUrl(url)
                    parts.add(VisionContentPart("image_url", imageUrl = VisionImageUrl(url)))
                }
                for (ref in message.attachments.filter { it.kind == AttachmentKind.IMAGE }) {
                    val data = resolvedImages[ref.id] ?: attachments.imageDataUri(ref).also {
                        resolvedImages[ref.id] = it
                    }
                    parts.add(VisionContentPart("image_url", imageUrl = VisionImageUrl(data)))
                }
                VisionMessage(message.role, parts)
            }
            PreparedPrompt(visionRequest = VisionRequest(model.id, visionMessages), truncated = truncated)
        }

    private fun cost(message: UiMessage): Long =
        message.content.toByteArray(Charsets.UTF_8).size.toLong() + 64 +
            message.attachments.filter { it.kind == AttachmentKind.TEXT }.sumOf { it.sizeBytes + 512 }

    private fun imageBytes(message: UiMessage): Long =
        message.attachments.filter { it.kind == AttachmentKind.IMAGE }.sumOf { it.sizeBytes } +
            message.imageUrls.sumOf { if (it.startsWith("data:")) it.length.toLong() else 1024L }

    private fun imageCount(message: UiMessage): Int =
        message.imageUrls.size + message.attachments.count { it.kind == AttachmentKind.IMAGE }

    companion object {
        fun validateImageUrl(url: String) {
            val uri = try { URI(url) } catch (_: java.net.URISyntaxException) { null }
            if (url.length > 8192 || uri == null || uri.scheme !in setOf("https", "http") ||
                uri.host.isNullOrBlank() || uri.userInfo != null
            ) throw ModelUnavailableException("图片链接无效，请重新选择图片")
        }
    }
}
