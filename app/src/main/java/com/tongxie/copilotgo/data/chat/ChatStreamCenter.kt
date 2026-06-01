package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.storage.SessionStore
import com.tongxie.copilotgo.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单例：跨 ChatViewModel 生命周期持有正在进行的 SSE 流。
 *
 * 设计要点：
 * - scope = ApplicationScope (SupervisorJob + Main.immediate)
 *   -> ChatViewModel 销毁不再 cancel 流；用户退出 chat 屏幕，流继续在后台运行。
 * - 每个 sessionId 维护独立的 sessionFlow / sendingFlow / errorFlow。
 * - 流式过程中每 ~800ms 持久化一次到 SessionStore，避免进程被 kill 时全丢。
 * - load 时修正残留 isStreaming=true 的消息（上次进程被杀的痕迹），避免显示永久"..."。
 *
 * 进程被杀仍会丢——彻底解决需要 Foreground Service（后续可加）。
 */
class ChatStreamCenter(
    private val store: SessionStore,
    private val chatClient: CopilotChatClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val sessionFlows = ConcurrentHashMap<String, MutableStateFlow<Session?>>()
    private val sendingFlows = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()
    private val errorFlows = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val loaded = AtomicBoolean(false)
    private val loadMutex = Mutex()

    private suspend fun ensureLoaded() {
        if (loaded.get()) return
        loadMutex.withLock {
            if (!loaded.get()) {
                store.load()
                loaded.set(true)
            }
        }
    }

    fun sessionFlow(id: String): StateFlow<Session?> {
        val mf = sessionFlows.getOrPut(id) {
            val flow = MutableStateFlow<Session?>(null)
            scope.launch {
                ensureLoaded()
                val s = store.sessions.value.firstOrNull { it.id == id }
                if (s != null) {
                    // 修正残留 isStreaming：流任务不在但 message 标记 streaming
                    // -> 上次进程没活到流结束，把 "..." 修成静态内容。
                    if (jobs[id] == null && s.messages.any { it.isStreaming }) {
                        for (i in s.messages.indices) {
                            if (s.messages[i].isStreaming) {
                                val orig = s.messages[i]
                                // content 空 → "[已中断]" 兜底（避免空气泡）
                                val fixed = if (orig.content.isEmpty()) {
                                    orig.copy(content = "[已中断]", isStreaming = false)
                                } else {
                                    orig.copy(isStreaming = false)
                                }
                                s.messages[i] = fixed
                            }
                        }
                        scope.launch { store.save(s) }
                    }
                }
                flow.value = s
            }
            flow
        }
        return mf.asStateFlow()
    }

    fun sendingFlow(id: String): StateFlow<Boolean> =
        sendingFlows.getOrPut(id) { MutableStateFlow(false) }.asStateFlow()

    fun errorFlow(id: String): StateFlow<String?> =
        errorFlows.getOrPut(id) { MutableStateFlow(null) }.asStateFlow()

    fun clearError(id: String) {
        errorFlows[id]?.value = null
    }

    fun setModel(id: String, model: String) {
        val s = sessionFlows[id]?.value ?: return
        s.model = model
        bump(id)
        scope.launch { store.save(s) }
    }

    /** 强制让 StateFlow 重新派发（绕过 data class equals 去重） */
    private fun bump(id: String) {
        sessionFlows[id]?.update { it?.copy(revision = it.revision + 1) }
    }

    private fun replaceAssistant(s: Session, mid: String, transform: (UiMessage) -> UiMessage) {
        val idx = s.messages.indexOfFirst { it.id == mid }
        if (idx >= 0) s.messages[idx] = transform(s.messages[idx])
    }

    fun send(
        id: String,
        text: String,
        attachments: List<String> = emptyList(),
        imageUrls: List<String> = emptyList()
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imageUrls.isEmpty()) return
        val sendingFlow = sendingFlows.getOrPut(id) { MutableStateFlow(false) }
        if (sendingFlow.value) return
        val s = sessionFlows[id]?.value ?: return
        val errorFlow = errorFlows.getOrPut(id) { MutableStateFlow(null) }

        val finalPrompt = if (attachments.isEmpty()) {
            trimmed.ifEmpty { "请看图。" }
        } else {
            buildString {
                attachments.forEach { content ->
                    appendLine("```"); appendLine(content); appendLine("```")
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
        bump(id)
        scope.launch { store.save(s) }

        sendingFlow.value = true
        errorFlow.value = null

        val isVision = imageUrls.isNotEmpty() ||
            s.messages.any { it.role == "user" && it.imageUrls.isNotEmpty() }

        jobs[id] = scope.launch {
            try {
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

                    // SSE collect 在 Main，上游已 flowOn(IO)。不要去掉 flowOn —— 否则 socket read 拉回 Main 触发 ANR (v0.1.7)。
                    val buffer = StringBuilder()
                    var lastSaveTime = System.currentTimeMillis()
                    flow.collect { delta ->
                        if (delta.text.isNotEmpty()) {
                            buffer.append(delta.text)
                            replaceAssistant(s, assistantId) {
                                it.copy(content = buffer.toString(), isStreaming = true)
                            }
                            bump(id)
                            // 节流持久化：每 800ms 一次（用户退出/进程崩溃时保留部分内容）
                            val now = System.currentTimeMillis()
                            if (now - lastSaveTime > 800) {
                                lastSaveTime = now
                                launch { runCatching { store.save(s) } }
                            }
                        }
                    }
                    replaceAssistant(s, assistantId) {
                        it.copy(content = buffer.toString(), isStreaming = false)
                    }
                    bump(id)
                }

                var result = runOnce(s.model)
                val err1 = result.exceptionOrNull()
                if (err1 != null && err1.message?.contains("model_not_supported") == true) {
                    val fallback = Constants.DEFAULT_MODEL
                    Logger.w("model ${s.model} not supported, fallback to $fallback")
                    s.model = fallback
                    bump(id)
                    store.save(s)
                    replaceAssistant(s, assistantId) {
                        it.copy(content = "[已自动切换模型 → $fallback]\n", isStreaming = true)
                    }
                    bump(id)
                    result = runOnce(fallback)
                }

                result.onFailure { e ->
                    Logger.e("chat error", throwable = e)
                    val friendly = friendlyError(e)
                    errorFlow.value = friendly
                    replaceAssistant(s, assistantId) {
                        val newContent =
                            (it.content.takeIf { c -> c.isNotBlank() } ?: "") + "[出错] $friendly"
                        it.copy(content = newContent, isStreaming = false)
                    }
                }
                replaceAssistant(s, assistantId) { it.copy(isStreaming = false) }
                bump(id)
                store.save(s)
            } catch (e: CancellationException) {
                // 用户主动 stop：保留已收到的内容，清 streaming 标志，持久化
                runCatching {
                    replaceAssistant(s, assistantId) { it.copy(isStreaming = false) }
                    bump(id)
                    store.save(s)
                }
                throw e
            } catch (e: Throwable) {
                Logger.e("send fatal", throwable = e)
                errorFlow.value = "意外错误：${e::class.simpleName}：${e.message ?: ""}"
                runCatching {
                    replaceAssistant(s, assistantId) {
                        it.copy(
                            content = it.content + "\n[内部错误] ${e::class.simpleName}",
                            isStreaming = false
                        )
                    }
                    bump(id)
                    store.save(s)
                }
            } finally {
                sendingFlow.value = false
                jobs.remove(id)
            }
        }
    }

    fun stop(id: String) {
        jobs[id]?.cancel()
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
}
