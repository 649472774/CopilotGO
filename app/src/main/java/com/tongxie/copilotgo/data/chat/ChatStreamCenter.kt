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
 *
 * ## 线程安全约定（v0.1.11 起严格执行）
 * - 所有 [Session.messages] 的 **写** 必须发生在 Main 线程（scope = Main.immediate 保证）。
 * - 持久化 [store.save] 必须先用 [snapshotForSave] 在 Main 上拷一份再扔给 IO，
 *   否则 IO 线程序列化时和 Main 写并发 → `ConcurrentModificationException` 或 JSON 截断。
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
    /** purge 之后还活着的 session id 集合检查；用于 send/save 拒绝复活已删除会话 */
    private val purged = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

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

    /**
     * Main-thread 安全的 session snapshot：深拷贝 messages 列表（UiMessage 本身 immutable）。
     * IO 序列化用此快照，避免与 Main 上的 add/replace 并发。
     */
    private fun snapshotForSave(s: Session): Session =
        s.copy(messages = ArrayList(s.messages))

    /** 持久化（snapshot + IO 串行）。已 purge 的 session 拒绝写回。 */
    private fun saveSnap(id: String, s: Session) {
        if (purged.contains(id)) return
        val snap = snapshotForSave(s)
        scope.launch { runCatching { store.save(snap) } }
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
                        saveSnap(id, s)
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
        saveSnap(id, s)
    }

    /**
     * 会话被删除时调用：取消活跃流任务、清空 in-memory 缓存、阻止后续 saveSnap 复活磁盘文件。
     * 必须由 [com.tongxie.copilotgo.ui.viewmodel.SessionListViewModel.delete] 在 `store.delete` 之前调用。
     */
    fun purge(id: String) {
        purged.add(id)
        jobs.remove(id)?.cancel()
        sessionFlows.remove(id)
        sendingFlows.remove(id)
        errorFlows.remove(id)
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
        val errorFlow = errorFlows.getOrPut(id) { MutableStateFlow(null) }

        // 异步主入口：先保证 session 已加载（Bug 8），再在 Main 上拼装+启动流
        scope.launch {
            val s = sessionFlows[id]?.value ?: run {
                ensureLoaded()
                val loaded = store.sessions.value.firstOrNull { it.id == id }
                if (loaded == null) {
                    errorFlow.value = "会话不存在或已被删除"
                    return@launch
                }
                // 将刚加载的 session 灌进 flow（兼容 sessionFlow() 尚未触发 getOrPut 的边界）
                sessionFlows.getOrPut(id) { MutableStateFlow<Session?>(null) }.value = loaded
                loaded
            }

            startStreaming(id, s, trimmed, attachments, imageUrls, sendingFlow, errorFlow)
        }
    }

    /** 在 Main.immediate 上调用：构造消息 + 启动 SSE job。 */
    private fun startStreaming(
        id: String,
        s: Session,
        trimmed: String,
        attachments: List<String>,
        imageUrls: List<String>,
        sendingFlow: MutableStateFlow<Boolean>,
        errorFlow: MutableStateFlow<String?>
    ) {
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
        saveSnap(id, s)

        sendingFlow.value = true
        errorFlow.value = null

        // Bug 13 修复：vision 与否只看 **本次** 请求是否带图，不再扫历史。
        // 历史里有图但本次没图 → 走纯文本 ChatRequest，避免把含图历史发给纯文本模型时被拒。
        val isVisionThisTurn = imageUrls.isNotEmpty()

        jobs[id] = scope.launch {
            try {
                suspend fun runOnce(model: String, prefix: String): Result<Unit> = runCatching {
                    val flow: Flow<CopilotChatClient.ChatDelta> = if (isVisionThisTurn) {
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
                            val rendered = prefix + buffer.toString()
                            replaceAssistant(s, assistantId) {
                                it.copy(content = rendered, isStreaming = true)
                            }
                            bump(id)
                            // 节流持久化：每 800ms 一次（用户退出/进程崩溃时保留部分内容）
                            val now = System.currentTimeMillis()
                            if (now - lastSaveTime > 800) {
                                lastSaveTime = now
                                saveSnap(id, s)
                            }
                        }
                    }
                    replaceAssistant(s, assistantId) {
                        it.copy(content = prefix + buffer.toString(), isStreaming = false)
                    }
                    bump(id)
                }

                var result = runOnce(s.model, prefix = "")
                val err1 = result.exceptionOrNull()
                if (err1 != null && err1.message?.contains("model_not_supported") == true) {
                    val fallback = Constants.DEFAULT_MODEL
                    Logger.w("model ${s.model} not supported, fallback to $fallback")
                    s.model = fallback
                    val notice = "[已自动切换模型 → $fallback]\n"
                    replaceAssistant(s, assistantId) {
                        it.copy(content = notice, isStreaming = true)
                    }
                    bump(id)
                    saveSnap(id, s)
                    // Bug 6 修复：通过 prefix 把"已自动切换"提示与 buffer 拼接，避免 retry 第一帧把提示冲掉
                    result = runOnce(fallback, prefix = notice)
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
                saveSnap(id, s)
            } catch (e: CancellationException) {
                // 用户主动 stop 或 purge：保留已收到的内容，清 streaming 标志，持久化（除非已 purge）
                runCatching {
                    replaceAssistant(s, assistantId) { it.copy(isStreaming = false) }
                    bump(id)
                    saveSnap(id, s)
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
                    saveSnap(id, s)
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
