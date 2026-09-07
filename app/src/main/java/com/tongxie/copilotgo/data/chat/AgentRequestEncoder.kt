package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.agent.AgentChatRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

internal data class EncodedAgentRequest(val body: String, val needsVision: Boolean)

internal object AgentRequestEncoder {
    fun encode(request: AgentChatRequest, json: Json): EncodedAgentRequest {
        agentCheck(request.stream, "Agent 运行时仅支持流式请求（stream=true）")
        agentCheck(request.n == 1, "Agent 运行时仅支持单个回复（n=1）")
        agentCheck(request.model.isNotBlank() && request.model.length <= 256, "Agent 必须指定有效模型")
        agentCheck(request.temperature.isFinite() && request.temperature in 0.0..2.0, "Agent 温度参数无效")
        agentCheck(request.toolChoice in setOf("auto", "none", "required"), "Agent 工具选择参数不受支持")
        agentCheck(request.messages.size in 1..AgentWireLimits.MAX_MESSAGES, "Agent 消息数量超过限制或为空")
        agentCheck(request.tools.size <= AgentWireLimits.MAX_TOOL_DEFINITIONS, "Agent 工具数量超过限制")
        val treeBudget = AgentJsonGuard.TreeBudget(AgentWireLimits.MAX_REQUEST_BYTES)
        val toolNames = HashSet<String>()
        val tools = request.tools.map { tool ->
            agentCheck(tool.type == "function", "Agent 仅支持 function 工具")
            agentIdentity(tool.function.name, AgentWireLimits.MAX_NAME_CHARACTERS)
            agentCheck(toolNames.add(tool.function.name), "Agent 工具名称重复")
            agentUtf8Size(tool.function.description, 16 * 1024)
            val schema = AgentJsonGuard.TreeBudget(AgentWireLimits.MAX_SCHEMA_BYTES).copy(
                tool.function.parameters
            ) as JsonObject
            tool.copy(function = tool.function.copy(parameters = schema))
        }
        var argumentBytes = 0
        var contentBytes = 0
        var needsVision = false
        val callIds = HashSet<String>()
        val messages = request.messages.map { message ->
            agentCheck(message.role in setOf("system", "developer", "user", "assistant", "tool"), "Agent 消息角色无效")
            val content = message.content?.let { treeBudget.copy(it) }
            fun text(value: String) {
                contentBytes += agentUtf8Size(value, Constants.MAX_RESPONSE_CHARACTERS)
                agentCheck(contentBytes <= Constants.MAX_RESPONSE_CHARACTERS, "Agent 消息文字超过大小限制")
            }
            when (content) {
                null, JsonNull -> Unit
                is JsonPrimitive -> {
                    agentCheck(content.isString, "Agent 消息内容必须是文字或视觉内容")
                    text(content.content)
                }
                is JsonArray -> for (element in content) {
                    val part = element as? JsonObject
                        ?: throw StreamProtocolException("Agent 视觉内容格式无效")
                    when (part.string("type")) {
                        "text" -> text(part.requiredString("text"))
                        "image_url" -> {
                            needsVision = true
                            val image = part["image_url"] as? JsonObject
                                ?: throw StreamProtocolException("Agent 图片格式无效")
                            imageUrl(image.requiredString("url"))
                        }
                        else -> throw StreamProtocolException("Agent 不支持此聊天内容类型")
                    }
                }
                else -> throw StreamProtocolException("Agent 消息内容格式无效")
            }
            val calls = message.toolCalls?.let { calls ->
                agentCheck(message.role == "assistant", "只有 assistant 消息可包含工具调用")
                agentCheck(calls.size in 1..AgentWireLimits.MAX_TOOLS, "Agent 工具调用数量无效")
                calls.map { call ->
                    agentIdentity(call.id, AgentWireLimits.MAX_ID_CHARACTERS)
                    agentIdentity(call.function.name, AgentWireLimits.MAX_NAME_CHARACTERS)
                    agentCheck(call.type == "function", "Agent 仅支持 function 工具调用")
                    agentCheck(callIds.add(call.id), "Agent 历史工具调用标识重复")
                    argumentBytes += agentUtf8Size(call.function.arguments, AgentWireLimits.MAX_ARGUMENT_BYTES)
                    agentCheck(argumentBytes <= AgentWireLimits.MAX_TOTAL_ARGUMENT_BYTES, "Agent 工具参数总量超过限制")
                    AgentJsonGuard.objectValue(call.function.arguments, AgentWireLimits.MAX_ARGUMENT_BYTES)
                    call
                }
            }
            if (message.role == "tool") {
                agentIdentity(message.toolCallId.orEmpty(), AgentWireLimits.MAX_ID_CHARACTERS)
                agentCheck(content is JsonPrimitive && content.isString, "工具结果必须是完整文字内容")
            } else {
                agentCheck(message.toolCallId == null, "非工具结果消息不能包含 tool_call_id")
            }
            message.copy(content = content, toolCalls = calls)
        }
        val snapshot = request.copy(messages = messages, tools = tools)
        try {
            val wireJson = Json(json) { encodeDefaults = true; prettyPrint = false }
            val toolsJson = wireJson.encodeToJsonElement(AgentChatRequest.serializer(), snapshot.copy(messages = emptyList()))
            AgentJsonGuard.TreeBudget(AgentWireLimits.MAX_TOTAL_TOOLS_BYTES).copy(toolsJson)
            val root = wireJson.encodeToJsonElement(AgentChatRequest.serializer(), snapshot)
            AgentJsonGuard.TreeBudget(AgentWireLimits.MAX_REQUEST_BYTES).copy(root)
            val body = wireJson.encodeToString(JsonElement.serializer(), root)
            AgentJsonGuard.objectValue(body, AgentWireLimits.MAX_REQUEST_BYTES)
            return EncodedAgentRequest(body, needsVision)
        } catch (_: SerializationException) {
            throw StreamProtocolException("Agent 请求无法编码")
        }
    }

    private fun imageUrl(url: String) {
        if (url.startsWith("data:image/") && ";base64," in url) return
        val uri = try { URI(url) } catch (_: java.net.URISyntaxException) { null }
        agentCheck(
            url.length <= 8192 && uri != null && uri.scheme == "https" &&
                !uri.host.isNullOrBlank() && uri.userInfo == null,
            "Agent 图片链接必须是 HTTPS 或内嵌图片"
        )
    }
}

internal fun JsonObject.string(key: String): String? {
    val value = this[key] ?: return null
    if (value == JsonNull) return null
    agentCheck(value is JsonPrimitive && value.isString, "Agent 字段 $key 必须是字符串")
    return (value as JsonPrimitive).content
}

internal fun JsonObject.requiredString(key: String): String =
    string(key) ?: throw StreamProtocolException("Agent 缺少字段 $key")
