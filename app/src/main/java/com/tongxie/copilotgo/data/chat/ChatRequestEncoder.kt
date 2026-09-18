package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.agent.AgentChatMessage
import com.tongxie.copilotgo.data.agent.AgentChatRequest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object ChatRequestEncoder {
    fun encode(
        request: ChatRequest,
        json: Json,
        transport: ModelTransport = ModelTransport.CHAT_COMPLETIONS
    ): EncodedAgentRequest = encode(
        AgentChatRequest(
            request.model, request.messages.map { AgentChatMessage(it.role, JsonPrimitive(it.content)) },
            emptyList(), request.stream, request.temperature, request.n
        ), request.topP, json, transport
    )

    fun encode(
        request: VisionRequest,
        json: Json,
        transport: ModelTransport = ModelTransport.CHAT_COMPLETIONS
    ): EncodedAgentRequest = encode(
        AgentChatRequest(
            request.model, request.messages.map {
                AgentChatMessage(it.role, Json(json) { encodeDefaults = true }.encodeToJsonElement(
                    ListSerializer(VisionContentPart.serializer()), it.content.toList()
                ))
            }, emptyList(), request.stream, request.temperature, request.n
        ), request.topP, json, transport
    )

    private fun encode(
        request: AgentChatRequest,
        topP: Double,
        json: Json,
        transport: ModelTransport
    ): EncodedAgentRequest {
        agentCheck(topP.isFinite() && topP in 0.0..1.0, "聊天 top_p 参数无效")
        agentCheck(request.messages.all { it.role in setOf("system", "developer", "user", "assistant") },
            "普通聊天不支持工具结果消息")
        val encoded = AgentRequestEncoder.encode(request, json, transport)
        if (transport == ModelTransport.RESPONSES) return encoded
        val root = AgentJsonGuard.objectValue(encoded.body, AgentWireLimits.MAX_REQUEST_BYTES)
        val ordinary = JsonObject(root.filterKeys {
            it !in setOf("tools", "tool_choice", "parallel_tool_calls")
        } + ("top_p" to JsonPrimitive(topP)))
        AgentJsonGuard.TreeBudget(AgentWireLimits.MAX_REQUEST_BYTES).copy(ordinary)
        return encoded.copy(body = Json(json) { prettyPrint = false }
            .encodeToString(JsonObject.serializer(), ordinary))
    }
}
