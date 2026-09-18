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
import kotlinx.serialization.json.Json
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

    fun streamChat(request: ChatRequest): Flow<ChatDelta> = streamRaw {
        val validated = ChatRequestEncoder.encode(request, json)
        val model = modelCatalog.requireModel(request.model, validated.needsVision)
        val transport = requireNotNull(model.transport)
        RequestPlan(
            if (transport == ModelTransport.CHAT_COMPLETIONS) validated else ChatRequestEncoder.encode(request, json, transport),
            transport
        )
    }

    fun streamVisionChat(request: VisionRequest): Flow<ChatDelta> = streamRaw {
        val validated = ChatRequestEncoder.encode(request, json)
        val model = modelCatalog.requireModel(request.model, validated.needsVision)
        val transport = requireNotNull(model.transport)
        RequestPlan(
            if (transport == ModelTransport.CHAT_COMPLETIONS) validated else ChatRequestEncoder.encode(request, json, transport),
            transport
        )
    }

    fun streamAgentChat(request: AgentChatRequest): Flow<AgentStreamEvent> = streamResponse(
        prepare = {
            val validated = AgentRequestEncoder.validate(request, json)
            val model = modelCatalog.requireModel(request.model, validated.needsVision, needsTools = true)
            val transport = requireNotNull(model.transport)
            RequestPlan(
                AgentRequestEncoder.encode(validated.request, json, model),
                transport, Constants.AGENT_INTENT,
                initiator = if (request.messages.lastOrNull()?.role == "user") "user" else "agent"
            )
        }
    ) { source, transport, emit ->
        consumeEvents(source, transport, allowTools = true, emit)
    }

    private data class RequestPlan(
        val encoded: EncodedAgentRequest,
        val transport: ModelTransport,
        val intent: String = Constants.OPENAI_INTENT,
        val initiator: String = "user"
    )

    private fun <T> streamResponse(
        prepare: suspend () -> RequestPlan,
        consume: suspend (BufferedSource, ModelTransport, suspend (T) -> Unit) -> Unit
    ): Flow<T> = channelFlow<Result<T>> {
        try {
            auth.withAccount {
                val plan = prepare()
                httpProvider.awaitReady()
                val session = auth.getValidCopilotSession()
                val request = Request.Builder()
                    .url("${session.apiBase.trimEnd('/')}${plan.transport.endpoint}")
                    .post(plan.encoded.body.toRequestBody(JSON_MEDIA))
                    .header("Authorization", "Bearer ${session.token}")
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", Constants.USER_AGENT_VSCODE)
                    .header("Editor-Version", Constants.EDITOR_VERSION)
                    .header("Editor-Plugin-Version", Constants.EDITOR_PLUGIN_VERSION)
                    .header("Copilot-Integration-Id", Constants.COPILOT_INTEGRATION_ID)
                    .header("Openai-Intent", plan.intent)
                    .header("X-GitHub-Api-Version", Constants.COPILOT_API_VERSION)
                    .header("X-Initiator", plan.initiator)
                    .header("X-Interaction-Type", plan.intent)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("VScode-SessionId", UUID.randomUUID().toString())
                    .header("VScode-MachineId", "copilotgo-${UUID.randomUUID()}")
                    .apply { if (plan.encoded.needsVision) header("Copilot-Vision-Request", "true") }
                    .build()
                httpProvider.client.newCall(request).withResponse { response ->
                    if (!response.isSuccessful) {
                        throw apiFailure(response.code, response.readBodyLimited(64 * 1024), json)
                    }
                    val source = response.body?.source() ?: throw StreamProtocolException("服务器返回了空响应")
                    if (response.body?.contentType()?.subtype != "event-stream") {
                        throw apiFailure(response.code, response.readBodyLimited(64 * 1024), json)
                    }
                    consume(source, plan.transport) { send(Result.success(it)) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Deliver the failure after queued deltas instead of cancelling and losing partial text.
            send(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO).map { it.getOrThrow() }

    private fun streamRaw(prepare: suspend () -> RequestPlan): Flow<ChatDelta> =
        streamResponse(prepare) { source, transport, emit ->
            consumeEvents(source, transport, allowTools = false) { event ->
                when (event) {
                    is AgentStreamEvent.TextDelta -> emit(ChatDelta(event.text, isFinal = false))
                    is AgentStreamEvent.Completed -> emit(ChatDelta("", isFinal = true, event.finishReason))
                }
            }
        }

    private suspend fun consumeEvents(
        source: BufferedSource,
        transport: ModelTransport,
        allowTools: Boolean,
        emit: suspend (AgentStreamEvent) -> Unit
    ) {
        val completed = when (transport) {
            ModelTransport.CHAT_COMPLETIONS -> {
                val assembler = AgentStreamAssembler(json)
                SseParser.events(source).takeWhile { event ->
                    assembler.accept(event).forEach { emit(it) }
                    !assembler.isDone
                }.collect {}
                assembler.endOfStream()
            }
            ModelTransport.RESPONSES -> {
                val assembler = ResponsesStreamAssembler(json, allowTools)
                SseParser.events(source).takeWhile { event ->
                    assembler.accept(event).forEach { emit(it) }
                    !assembler.isDone
                }.collect {}
                assembler.endOfStream()
            }
        }
        if (!allowTools && completed.toolCalls.isNotEmpty()) {
            throw StreamProtocolException("当前普通聊天模式不支持模型请求的工具调用")
        }
        emit(completed)
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
                .header("Openai-Intent", "model-access")
                .header("X-GitHub-Api-Version", Constants.COPILOT_API_VERSION)
                .build()
            httpProvider.client.newCall(request).withResponse { response ->
                val text = response.readBodyLimited()
                if (!response.isSuccessful) throw apiFailure(response.code, text, json)
                json.decodeFromString(ModelListResponse.serializer(), text)
                    .data.filter { it.pickerVisible }.distinctBy { it.id }
            }
        }
    }

    data class ChatDelta(val text: String, val isFinal: Boolean, val finishReason: String? = null)

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
