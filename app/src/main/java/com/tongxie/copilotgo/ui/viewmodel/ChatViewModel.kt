package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.chat.ChatMessage
import com.tongxie.copilotgo.data.chat.ChatRequest
import com.tongxie.copilotgo.data.chat.CopilotChatClient
import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.data.chat.VisionContentPart
import com.tongxie.copilotgo.data.chat.VisionImageUrl
import com.tongxie.copilotgo.data.chat.VisionMessage
import com.tongxie.copilotgo.data.chat.VisionRequest
import com.tongxie.copilotgo.data.storage.SessionStore
import com.tongxie.copilotgo.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val sessionId: String,
    private val store: SessionStore,
    private val chatClient: CopilotChatClient
) : ViewModel() {

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            store.load()
            _session.value = store.sessions.value.firstOrNull { it.id == sessionId }
        }
    }

    /** 强制让 StateFlow 发新值（绕过 data class equals 去重）
     *  关键：不能修改 _session.value 指向的旧对象的 revision，否则 old.equals(new) 仍然 true */
    private fun bumpSession() {
        _session.update { current -> current?.copy(revision = current.revision + 1) }
    }

    fun setModel(model: String) {
        val s = _session.value ?: return
        s.model = model
        bumpSession()
        viewModelScope.launch { store.save(s) }
    }

    /**
     * 发送消息。
     * @param text 文本（trim 后不能空，除非有 imageUrls）
     * @param attachments 文本附件内容（追加到 prompt 前面）
     * @param imageUrls 图片 data URI 列表（如 "data:image/png;base64,..."），用于视觉模型
     */
    fun send(
        text: String,
        attachments: List<String> = emptyList(),
        imageUrls: List<String> = emptyList()
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imageUrls.isEmpty()) return
        if (_sending.value) return
        val s = _session.value ?: return

        val finalPrompt = if (attachments.isEmpty()) {
            trimmed.ifEmpty { "请看图。" }
        } else {
            buildString {
                attachments.forEach { content ->
                    appendLine("```")
                    appendLine(content)
                    appendLine("```")
                }
                append(trimmed.ifEmpty { "请看图。" })
            }
        }

        val userMsg = UiMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = finalPrompt,
            imageUrls = imageUrls
        )
        val assistantId = UUID.randomUUID().toString()
        val assistantMsg = UiMessage(
            id = assistantId,
            role = "assistant",
            content = "",
            isStreaming = true
        )
        s.messages.add(userMsg)
        s.messages.add(assistantMsg)
        if (s.title == "新会话" && s.messages.size == 2) {
            s.title = trimmed.take(30).ifEmpty { "图片对话" }
        }
        bumpSession()
        viewModelScope.launch { store.save(s) }

        _sending.value = true
        _error.value = null

        // 历史构造（不含本轮 assistant 占位符）。如果有图片就走 vision payload。
        val isVision = imageUrls.isNotEmpty() || s.messages.any { it.role == "user" && it.imageUrls.isNotEmpty() }

        streamJob = viewModelScope.launch {

            suspend fun runOnce(model: String): Result<Unit> = runCatching {
                val flow: Flow<CopilotChatClient.ChatDelta> = if (isVision) {
                    val visionMessages = s.messages.dropLast(1).map { ui ->
                        val parts = mutableListOf<VisionContentPart>()
                        if (ui.content.isNotEmpty()) {
                            parts.add(VisionContentPart(type = "text", text = ui.content))
                        }
                        ui.imageUrls.forEach { url ->
                            parts.add(
                                VisionContentPart(
                                    type = "image_url",
                                    imageUrl = VisionImageUrl(url = url)
                                )
                            )
                        }
                        if (parts.isEmpty()) {
                            parts.add(VisionContentPart(type = "text", text = ""))
                        }
                        VisionMessage(role = ui.role, content = parts)
                    }
                    chatClient.streamVisionChat(
                        VisionRequest(model = model, messages = visionMessages, stream = true)
                    )
                } else {
                    val historyForModel = s.messages.dropLast(1)
                        .map { ChatMessage(role = it.role, content = it.content) }
                    chatClient.streamChat(
                        ChatRequest(model = model, messages = historyForModel, stream = true)
                    )
                }

                val buffer = StringBuilder()
                // Typewriter：分离"接收 buffer"与"已显示长度"，单独协程每 20ms 推进 2 个字符
                var displayedLen = 0
                var streamDone = false
                val typewriter = launch {
                    while (!streamDone || displayedLen < buffer.length) {
                        if (displayedLen < buffer.length) {
                            // 速率：~100 chars/sec；剩余太多时加速，避免结束后还在慢慢敲
                            val remaining = buffer.length - displayedLen
                            val step = when {
                                remaining > 200 -> 8
                                remaining > 60 -> 4
                                else -> 2
                            }
                            displayedLen = (displayedLen + step).coerceAtMost(buffer.length)
                            replaceAssistant(s, assistantId) {
                                it.copy(content = buffer.substring(0, displayedLen), isStreaming = true)
                            }
                            bumpSession()
                        }
                        delay(20)
                    }
                }

                flow.collect { delta ->
                    if (delta.text.isNotEmpty()) {
                        buffer.append(delta.text)
                    }
                    if (delta.isFinal) {
                        streamDone = true
                    }
                }
                streamDone = true
                typewriter.join()
                // 收尾：显示完整内容，停掉 streaming 标志
                replaceAssistant(s, assistantId) {
                    it.copy(content = buffer.toString(), isStreaming = false)
                }
                bumpSession()
            }

            var result = runOnce(s.model)
            val err1 = result.exceptionOrNull()
            if (err1 != null && err1.message?.contains("model_not_supported") == true) {
                val fallback = com.tongxie.copilotgo.data.Constants.DEFAULT_MODEL
                Logger.w("model ${s.model} not supported, fallback to $fallback")
                s.model = fallback
                bumpSession()
                store.save(s)
                replaceAssistant(s, assistantId) {
                    it.copy(content = "[已自动切换模型 → $fallback]\n", isStreaming = true)
                }
                bumpSession()
                result = runOnce(fallback)
            }

            result.onFailure { e ->
                Logger.e("chat error", throwable = e)
                val friendly = friendlyError(e)
                _error.value = friendly
                replaceAssistant(s, assistantId) {
                    val newContent =
                        (it.content.takeIf { c -> c.isNotBlank() } ?: "") + "[出错] $friendly"
                    it.copy(content = newContent, isStreaming = false)
                }
            }
            _sending.value = false
            // 收尾：确保不再 streaming
            replaceAssistant(s, assistantId) { it.copy(isStreaming = false) }
            bumpSession()
            store.save(s)
        }
    }

    /** 替换 list 中指定 id 的 message 为新对象（保证 list 元素引用变化） */
    private fun replaceAssistant(s: Session, id: String, transform: (UiMessage) -> UiMessage) {
        val idx = s.messages.indexOfFirst { it.id == id }
        if (idx >= 0) {
            s.messages[idx] = transform(s.messages[idx])
        }
    }

    private fun friendlyError(e: Throwable): String {
        val raw = e.message ?: return "请求失败"
        val msgRegex = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")
        val codeRegex = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"")
        val m = msgRegex.find(raw)?.groupValues?.get(1)
        val c = codeRegex.find(raw)?.groupValues?.get(1)
        return when {
            c == "model_not_supported" -> "当前模型不被订阅支持：${m ?: ""}"
            raw.contains("Misdirected") -> "Endpoint 不匹配，请重新登录"
            raw.contains("401") || raw.contains("unauthorized", true) -> "登录已失效，请重新登录"
            raw.contains("429") -> "请求过于频繁，请稍后再试"
            m != null -> m
            else -> raw.take(200)
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        _sending.value = false
        val s = _session.value ?: return
        val lastIdx = s.messages.indexOfLast { it.role == "assistant" && it.isStreaming }
        if (lastIdx >= 0) {
            s.messages[lastIdx] = s.messages[lastIdx].copy(isStreaming = false)
        }
        bumpSession()
        viewModelScope.launch { store.save(s) }
    }

    fun clearError() { _error.value = null }
}
