package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.agent.AgentChatRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

internal data class EncodedAgentRequest(val body: String, val needsVision: Boolean) {
    val byteCount: Int get() = agentUtf8Size(body, AgentWireLimits.MAX_REQUEST_BYTES)
}

internal data class ValidatedAgentRequest(val request: AgentChatRequest, val needsVision: Boolean)

internal object AgentRequestEncoder {
    fun encode(
        request: AgentChatRequest,
        json: Json,
        model: ModelInfo,
        maxWireBytes: Int = AgentWireLimits.MAX_REQUEST_BYTES
    ): EncodedAgentRequest {
        agentCheck(request.model == model.id, "预检模型与请求模型不一致，不能自动切换模型")
        agentCheck(maxWireBytes in 1..AgentWireLimits.MAX_REQUEST_BYTES, "Agent 请求大小限制无效")
        model.unavailableReason(needsTools = true)?.let { throw ModelUnavailableException(it) }
        if (request.parallelToolCalls && model.capabilities?.supports?.parallelToolCalls != true) {
            throw ModelUnavailableException("所选模型未声明支持并行工具调用")
        }
        val encoded = encode(request, json, requireNotNull(model.transport))
        model.unavailableReason(encoded.needsVision, needsTools = true)?.let { throw ModelUnavailableException(it) }
        val limits = model.capabilities?.limits
        val modelBudget = listOfNotNull(
            limits?.maxPromptTokens,
            limits?.maxContextWindowTokens?.let {
                it - (limits.maxOutputTokens ?: minOf(4096, it / 4))
            }
        ).minOrNull()
        agentCheck(encoded.byteCount <= minOf(maxWireBytes, modelBudget ?: maxWireBytes),
            "所选模型的完整协议请求超过上下文或传输大小限制")
        return encoded
    }

    fun encode(
        request: AgentChatRequest,
        json: Json,
        transport: ModelTransport = ModelTransport.CHAT_COMPLETIONS
    ): EncodedAgentRequest {
        val validated = validate(request, json)
        val snapshot = validated.request
        if (transport == ModelTransport.RESPONSES) {
            return EncodedAgentRequest(ResponsesRequestEncoder.encode(snapshot, json), validated.needsVision)
        }
        agentCheck(snapshot.messages.all { it.responsesOutput.isEmpty() },
            "Responses 续接状态不能发送到其他协议，请保持本次运行的模型不变")
        val wireJson = Json(json) { encodeDefaults = true; prettyPrint = false }
        val body = wireJson.encodeToString(AgentChatRequest.serializer(), snapshot)
        AgentJsonGuard.objectValue(body, AgentWireLimits.MAX_REQUEST_BYTES)
        return EncodedAgentRequest(body, validated.needsVision)
    }

    fun validate(request: AgentChatRequest, json: Json): ValidatedAgentRequest {
        agentCheck(request.stream, "Agent 运行时仅支持流式请求（stream=true）")
        agentCheck(request.n == 1, "Agent 运行时仅支持单个回复（n=1）")
        agentCheck(request.model.isNotBlank() && request.model.length <= 256, "Agent 必须指定有效模型")
        agentCheck(request.temperature.isFinite() && request.temperature in 0.0..2.0, "Agent 温度参数无效")
        agentCheck(request.toolChoice in setOf("auto", "none", "required"), "Agent 工具选择参数不受支持")
        agentCheck(request.toolChoice != "required" || request.tools.isNotEmpty(), "强制工具调用需要至少一个可用工具")
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
        val outputIds = HashSet<String>()
        val pendingResults = HashSet<String>()
        val messages = request.messages.map { message ->
            agentCheck(message.role in setOf("system", "developer", "user", "assistant", "tool"), "Agent 消息角色无效")
            agentCheck(message.role == "tool" || pendingResults.isEmpty(), "Agent 工具调用缺少完整结果")
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
                            agentCheck(message.role == "user", "图片只能出现在用户消息中")
                            needsVision = true
                            val image = part["image_url"] as? JsonObject
                                ?: throw StreamProtocolException("Agent 图片格式无效")
                            imageUrl(image.requiredString("url"))
                            agentCheck(image.string("detail") in setOf(null, "auto", "low", "high"), "图片精度参数无效")
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
                    pendingResults.add(call.id)
                    argumentBytes += agentUtf8Size(call.function.arguments, AgentWireLimits.MAX_ARGUMENT_BYTES)
                    agentCheck(argumentBytes <= AgentWireLimits.MAX_TOTAL_ARGUMENT_BYTES, "Agent 工具参数总量超过限制")
                    AgentJsonGuard.objectValue(call.function.arguments, AgentWireLimits.MAX_ARGUMENT_BYTES)
                    call
                }
            }
            if (message.role == "tool") {
                agentIdentity(message.toolCallId.orEmpty(), AgentWireLimits.MAX_ID_CHARACTERS)
                agentCheck(content is JsonPrimitive && content.isString, "工具结果必须是完整文字内容")
                agentCheck(pendingResults.remove(message.toolCallId), "Agent 工具结果缺少匹配调用或已重复")
            } else {
                agentCheck(message.toolCallId == null, "非工具结果消息不能包含 tool_call_id")
            }
            val output = if (message.responsesOutput.isEmpty()) emptyList() else {
                agentCheck(message.role == "assistant", "只有 assistant 消息可包含 Responses 续接状态")
                val assistantText = when (content) {
                    null, JsonNull -> ""
                    is JsonPrimitive -> content.content
                    is JsonArray -> content.joinToString("") { (it as JsonObject).requiredString("text") }
                    else -> throw StreamProtocolException("Responses 续接消息内容格式无效")
                }
                ResponsesOutputGuard.snapshot(message.responsesOutput, assistantText, calls.orEmpty()).also {
                    treeBudget.copy(JsonArray(it))
                    it.forEach { item ->
                        agentCheck(outputIds.add(item.requiredString("id")), "Responses 历史输出项标识重复")
                    }
                }
            }
            message.copy(content = content, toolCalls = calls, responsesOutput = output)
        }
        agentCheck(pendingResults.isEmpty(), "Agent 工具调用缺少完整结果")
        val snapshot = request.copy(messages = messages, tools = tools)
        try {
            val wireJson = Json(json) { encodeDefaults = true; prettyPrint = false }
            val toolsJson = wireJson.encodeToJsonElement(AgentChatRequest.serializer(), snapshot.copy(messages = emptyList()))
            AgentJsonGuard.TreeBudget(AgentWireLimits.MAX_TOTAL_TOOLS_BYTES).copy(toolsJson)
            val root = wireJson.encodeToJsonElement(AgentChatRequest.serializer(), snapshot)
            AgentJsonGuard.TreeBudget(AgentWireLimits.MAX_REQUEST_BYTES).copy(root)
            return ValidatedAgentRequest(snapshot, needsVision)
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
