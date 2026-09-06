package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.withResponse
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.net.apiFailure
import com.tongxie.copilotgo.data.net.readBodyLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.io.File

class CopilotChatClient(
    private val httpProvider: HttpClientProvider,
    private val json: Json,
    private val auth: AuthRepository,
    modelCacheFile: File? = null
) {
    val modelCatalog: ModelCatalog by lazy { ModelCatalog(this, json, auth, modelCacheFile) }
    val accountGeneration get() = auth.accountGeneration

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
    private fun streamRaw(body: String): Flow<ChatDelta> = channelFlow {
        auth.withAccount {
        httpProvider.awaitReady()
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

        httpProvider.client.newCall(req).withResponse { resp ->
            if (!resp.isSuccessful) {
                throw apiFailure(resp.code, resp.readBodyLimited(64 * 1024), json)
            }
            val source = resp.body?.source() ?: throw StreamProtocolException("服务器返回了空响应")
            if (resp.body?.contentType()?.subtype != "event-stream") {
                throw apiFailure(resp.code, resp.readBodyLimited(64 * 1024), json)
            }
            var terminal = false
            var characters = 0
            SseParser.events(source).takeWhile { event ->
                if (event.event == "error") throw apiFailure(null, event.data, json)
                if (event.data == "[DONE]") {
                    terminal = true
                    send(ChatDelta("", isFinal = true))
                    return@takeWhile false
                }
                if (event.data.isBlank() || event.event == "ping" || event.event == "heartbeat") {
                    return@takeWhile true
                }
                val chunk = try {
                    val root = json.parseToJsonElement(event.data) as? JsonObject
                        ?: throw StreamProtocolException("流式响应格式不受支持")
                    if (root["error"] != null && root["error"] != JsonNull) {
                        throw apiFailure(null, event.data, json)
                    }
                    if ("choices" !in root && "usage" !in root) {
                        throw StreamProtocolException("流式响应缺少消息内容")
                    }
                    json.decodeFromJsonElement(ChatStreamChunk.serializer(), root)
                } catch (_: SerializationException) {
                    throw StreamProtocolException("流式响应格式损坏，请重试")
                }
                val choice = chunk.choices.firstOrNull { it.index == 0 }
                val delta = choice?.delta?.content.orEmpty()
                characters += delta.length
                if (characters > Constants.MAX_RESPONSE_CHARACTERS) {
                    throw StreamProtocolException("回复超过本地安全长度限制，已停止接收")
                }
                if (delta.isNotEmpty()) send(ChatDelta(delta, isFinal = false))
                val finish = choice?.finishReason
                if (finish != null) {
                    if (finish == "content_filter") {
                        throw apiFailure(null, """{"code":"content_filter"}""", json)
                    }
                    if (finish == "tool_calls" || finish == "function_call") {
                        throw StreamProtocolException("当前普通聊天模式不支持模型请求的工具调用")
                    }
                    terminal = true
                    send(ChatDelta("", isFinal = true, finishReason = finish))
                }
                !terminal
            }.collect {}
            if (!terminal) throw StreamProtocolException("回复在完成前中断，请重试")
        }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        auth.withAccount {
            httpProvider.awaitReady()
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
            httpProvider.client.newCall(req).withResponse { resp ->
                val text = resp.readBodyLimited()
                if (!resp.isSuccessful) throw apiFailure(resp.code, text, json)
                val parsed = json.decodeFromString(ModelListResponse.serializer(), text)
                parsed.data.filter { it.chatCompatible }.distinctBy { it.id }
            }
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
