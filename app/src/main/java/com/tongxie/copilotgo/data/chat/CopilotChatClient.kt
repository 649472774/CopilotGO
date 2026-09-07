package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.agent.AgentChatRequest
import com.tongxie.copilotgo.data.agent.AgentStreamEvent
import com.tongxie.copilotgo.data.auth.AuthRepository
import com.tongxie.copilotgo.data.auth.withResponse
import com.tongxie.copilotgo.data.net.HttpClientProvider
import com.tongxie.copilotgo.data.net.apiFailure
import com.tongxie.copilotgo.data.net.readBodyLimited
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.File
import java.util.UUID

class CopilotChatClient(
    private val httpProvider: HttpClientProvider,
    private val json: Json,
    private val auth: AuthRepository,
    modelCacheFile: File? = null
) {
    val modelCatalog: ModelCatalog by lazy { ModelCatalog(this, json, auth, modelCacheFile) }
    val accountGeneration get() = auth.accountGeneration

    internal suspend fun <T> withAccount(block: suspend () -> T): T = auth.withAccount(block)

    fun streamChat(request: ChatRequest): Flow<ChatDelta> =
        streamRaw { json.encodeToString(ChatRequest.serializer(), request) }

    fun streamVisionChat(request: VisionRequest): Flow<ChatDelta> =
        streamRaw { json.encodeToString(VisionRequest.serializer(), request) }

    fun streamAgentChat(request: AgentChatRequest): Flow<AgentStreamEvent> = streamResponse(
        body = {
            val encoded = AgentRequestEncoder.encode(request, json)
            modelCatalog.requireModel(request.model, encoded.needsVision, needsTools = true)
            encoded.body
        }
    ) { source, emit ->
        val assembler = AgentStreamAssembler(json)
        SseParser.events(source).takeWhile { event ->
            assembler.accept(event).forEach { emit(it) }
            !assembler.isDone
        }.collect {}
        emit(assembler.endOfStream())
    }

    private fun <T> streamResponse(
        body: suspend () -> String,
        consume: suspend (BufferedSource, suspend (T) -> Unit) -> Unit
    ): Flow<T> = channelFlow<Result<T>> {
        try {
            auth.withAccount {
                val encodedBody = body()
                httpProvider.awaitReady()
                val session = auth.getValidCopilotSession()
                val request = Request.Builder()
                    .url("${session.apiBase.trimEnd('/')}/chat/completions")
                    .post(encodedBody.toRequestBody(JSON_MEDIA))
                    .header("Authorization", "Bearer ${session.token}")
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
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
                httpProvider.client.newCall(request).withResponse { response ->
                    if (!response.isSuccessful) {
                        throw apiFailure(response.code, response.readBodyLimited(64 * 1024), json)
                    }
                    val source = response.body?.source() ?: throw StreamProtocolException("服务器返回了空响应")
                    if (response.body?.contentType()?.subtype != "event-stream") {
                        throw apiFailure(response.code, response.readBodyLimited(64 * 1024), json)
                    }
                    consume(source) { send(Result.success(it)) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Deliver the failure after queued deltas instead of cancelling and losing partial text.
            send(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO).map { it.getOrThrow() }

    private fun streamRaw(body: () -> String): Flow<ChatDelta> = streamResponse({ body() }) { source, emit ->
        var terminal = false
        var characters = 0
        SseParser.events(source).takeWhile { event ->
            if (event.event == "error") throw apiFailure(null, event.data, json)
            if (event.data == "[DONE]") {
                terminal = true
                emit(ChatDelta("", isFinal = true))
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
            if (delta.isNotEmpty()) emit(ChatDelta(delta, isFinal = false))
            val finish = choice?.finishReason
            if (finish != null) {
                if (finish == "content_filter") {
                    throw apiFailure(null, """{"code":"content_filter"}""", json)
                }
                if (finish == "tool_calls" || finish == "function_call") {
                    throw StreamProtocolException("当前普通聊天模式不支持模型请求的工具调用")
                }
                terminal = true
                emit(ChatDelta("", isFinal = true, finishReason = finish))
            }
            !terminal
        }.collect {}
        if (!terminal) throw StreamProtocolException("回复在完成前中断，请重试")
    }

    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        auth.withAccount {
            httpProvider.awaitReady()
            val session = auth.getValidCopilotSession()
            val request = Request.Builder()
                .url("${session.apiBase.trimEnd('/')}/models")
                .get()
                .header("Authorization", "Bearer ${session.token}")
                .header("Accept", "application/json")
                .header("User-Agent", Constants.USER_AGENT_VSCODE)
                .header("Editor-Version", Constants.EDITOR_VERSION)
                .header("Editor-Plugin-Version", Constants.EDITOR_PLUGIN_VERSION)
                .header("Copilot-Integration-Id", Constants.COPILOT_INTEGRATION_ID)
                .build()
            httpProvider.client.newCall(request).withResponse { response ->
                val text = response.readBodyLimited()
                if (!response.isSuccessful) throw apiFailure(response.code, text, json)
                json.decodeFromString(ModelListResponse.serializer(), text)
                    .data.filter { it.chatCompatible }.distinctBy { it.id }
            }
        }
    }

    data class ChatDelta(val text: String, val isFinal: Boolean, val finishReason: String? = null)

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
