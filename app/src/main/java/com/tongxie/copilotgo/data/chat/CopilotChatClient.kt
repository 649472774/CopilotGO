package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.executeAsync
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class CopilotChatClient(
    private val httpProvider: HttpClientProvider,
    private val json: Json,
    private val auth: AuthRepository
) {

    /** 流式聊天，逐 chunk 发出文本增量；最后一次 emit 用 isFinal=true 标记结束 */
    fun streamChat(request: ChatRequest): Flow<ChatDelta> = flow {
        val body = json.encodeToString(ChatRequest.serializer(), request)
        emitAll(streamRaw(body))
    }.flowOn(Dispatchers.IO)

    /** 视觉模型流式：messages 走 multi-content（text + image_url）格式 */
    fun streamVisionChat(request: VisionRequest): Flow<ChatDelta> = flow {
        val body = json.encodeToString(VisionRequest.serializer(), request)
        emitAll(streamRaw(body))
    }.flowOn(Dispatchers.IO)

    /**
     * 关键：整个 streamRaw 必须跑在 Dispatchers.IO。
     * - executeAsync 内部本身已挂起到 IO（OkHttp 调度线程）。
     * - 但 SseParser.lines / source.exhausted() / readUtf8Line() 是 **阻塞** socket read，
     *   在 collector 的协程上下文里跑（默认是上游 dispatcher）。
     * - 如果调用方在 viewModelScope（Main）collect，本函数所有同步阻塞 IO 都会在主线程跑 → ANR 5s。
     * - .flowOn(Dispatchers.IO) 保证所有 emit 之前的代码在 IO 池；emit 跨线程通过 channel 切回 collector。
     */
    private fun streamRaw(body: String): Flow<ChatDelta> = flow {
        val session = auth.getValidCopilotSession()
        val reqBody = body.toRequestBody(JSON_MEDIA)

        val req = Request.Builder()
            .url("${session.apiBase}/chat/completions")
            .post(reqBody)
            .header("Authorization", "Bearer ${session.token}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            // 关键：禁用 gzip，否则 OkHttp 解压时会按 gzip frame 缓冲，
            // SSE 多个 delta 被压在一个 frame 里会一次性出来，没有逐字流式效果
            .header("Accept-Encoding", "identity")
            .header("User-Agent", Constants.USER_AGENT_VSCODE)
            .header("Editor-Version", Constants.EDITOR_VERSION)
            .header("Editor-Plugin-Version", Constants.EDITOR_PLUGIN_VERSION)
            .header("Copilot-Integration-Id", Constants.COPILOT_INTEGRATION_ID)
            .header("Openai-Intent", Constants.OPENAI_INTENT)
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("VScode-SessionId", UUID.randomUUID().toString())
            .header("VScode-MachineId", "copilotgo-${UUID.randomUUID()}")
            .build()

        val resp = httpProvider.client.newCall(req).executeAsync()
        if (!resp.isSuccessful) {
            val errBody = resp.body?.string().orEmpty()
            error("chat failed (${resp.code}): $errBody")
        }
        val source = resp.body?.source() ?: error("empty body")

        try {
            SseParser.lines(source).collect { raw ->
                runCatching {
                    val chunk = json.decodeFromString(ChatStreamChunk.serializer(), raw)
                    val delta = chunk.choices.firstOrNull()?.delta?.content
                    val finish = chunk.choices.firstOrNull()?.finishReason
                    if (!delta.isNullOrEmpty()) {
                        emit(ChatDelta(text = delta, isFinal = false))
                    }
                    if (finish != null) {
                        emit(ChatDelta(text = "", isFinal = true, finishReason = finish))
                    }
                }.onFailure { e ->
                    Logger.w("Failed to parse SSE chunk: $raw -> ${e.message}")
                }
            }
            emit(ChatDelta(text = "", isFinal = true))
        } finally {
            runCatching { resp.close() }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            val session = auth.getValidCopilotSession()
            val req = Request.Builder()
                .url("${session.apiBase}/models")
                .get()
                .header("Authorization", "Bearer ${session.token}")
                .header("Accept", "application/json")
                .header("User-Agent", Constants.USER_AGENT_VSCODE)
                .header("Editor-Version", Constants.EDITOR_VERSION)
                .header("Editor-Plugin-Version", Constants.EDITOR_PLUGIN_VERSION)
                .header("Copilot-Integration-Id", Constants.COPILOT_INTEGRATION_ID)
                .build()
            val resp = httpProvider.client.newCall(req).executeAsync()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Logger.w("listModels failed (${resp.code}): $text")
                return@withContext Constants.FALLBACK_MODELS.map { ModelInfo(id = it) }
            }
            val parsed = json.decodeFromString(ModelListResponse.serializer(), text)
            parsed.data.filter { it.modelPickerEnabled }.ifEmpty {
                Constants.FALLBACK_MODELS.map { ModelInfo(id = it) }
            }
        } catch (e: Throwable) {
            Logger.w("listModels error", throwable = e)
            Constants.FALLBACK_MODELS.map { ModelInfo(id = it) }
        }
    }

    data class ChatDelta(
        val text: String,
        val isFinal: Boolean,
        val finishReason: String? = null
    )

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
