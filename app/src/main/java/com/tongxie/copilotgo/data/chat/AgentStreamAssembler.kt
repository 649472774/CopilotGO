package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.agent.AgentFunctionCall
import com.tongxie.copilotgo.data.agent.AgentStreamEvent
import com.tongxie.copilotgo.data.agent.AgentToolCall
import com.tongxie.copilotgo.data.net.apiFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Single-choice chat-completions assembly; validates framing and JSON arguments, not tool schemas. */
internal class AgentStreamAssembler(private val json: Json) {
    private val calls = sortedMapOf<Int, PartialCall>()
    private var eventCount = 0
    private var streamBytes = 0L
    private var argumentBytes = 0
    private var contentCharacters = 0
    private var hasText = false
    private var terminal: AgentStreamEvent.Completed? = null
    private var done = false
    private var delivered = false
    private var failed = false
    val isDone: Boolean get() = done

    fun accept(event: SseEvent): List<AgentStreamEvent.TextDelta> {
        try {
            agentCheck(!failed && !done && !delivered, "Agent 完成后收到重复事件")
            agentCheck(++eventCount <= AgentWireLimits.MAX_EVENTS, "Agent 流式事件数量超过限制")
            streamBytes += agentUtf8Size(event.data, AgentWireLimits.MAX_EVENT_BYTES)
            streamBytes += agentUtf8Size(event.event, 256)
            event.id?.let { streamBytes += agentUtf8Size(it, 1024) }
            agentCheck(streamBytes <= AgentWireLimits.MAX_STREAM_BYTES, "Agent 流式数据总量超过限制")
            if (event.event == "error") {
                val error = try {
                    AgentJsonGuard.objectValue(event.data, AgentWireLimits.MAX_EVENT_BYTES).toString()
                } catch (_: StreamProtocolException) {
                    ""
                }
                throw apiFailure(null, error, json)
            }
            agentCheck(event.event in setOf("message", "ping", "heartbeat"), "Agent 流式事件类型不受支持")
            if (event.data == "[DONE]") {
                agentCheck(event.event == "message" && terminal != null, "Agent 回复缺少完成原因")
                done = true
                return emptyList()
            }
            if (event.data.isBlank()) return emptyList()
            if (event.event == "ping" || event.event == "heartbeat") {
                if (event.data.trimStart().startsWith('{')) {
                    errorPayload(AgentJsonGuard.objectValue(event.data, AgentWireLimits.MAX_EVENT_BYTES), event.data)
                }
                return emptyList()
            }
            val root = AgentJsonGuard.objectValue(event.data, AgentWireLimits.MAX_EVENT_BYTES)
            errorPayload(root, event.data)
            agentCheck(root.string("object") in setOf(null, "chat.completion.chunk"), "Agent 仅支持 chat-completions 流式响应")
            legacy(root)
            val choices = root["choices"]
            if (choices == null && root["usage"] is JsonObject) return emptyList()
            agentCheck(choices is JsonArray, "Agent 流式响应缺少 choices")
            choices as JsonArray
            agentCheck(choices.size <= 1, "Agent 运行时不支持多个回复选择")
            if (choices.isEmpty()) return emptyList()
            val choice = choices.single() as? JsonObject
                ?: throw StreamProtocolException("Agent 回复选择格式无效")
            val index = choice.index("index", default = 0)
            agentCheck(index == 0, "Agent 运行时仅接受 choice index 0")
            agentCheck(terminal == null, "Agent 已完成的回复包含重复调用或增量")
            legacy(choice)
            val delta = choice["delta"]
            agentCheck(delta == null || delta == JsonNull || delta is JsonObject, "Agent delta 格式无效")
            var text: String? = null
            if (delta is JsonObject) {
                legacy(delta)
                agentCheck(delta.string("role") in setOf(null, "assistant"), "Agent 回复角色无效")
                agentCheck(delta["refusal"] == null || delta["refusal"] == JsonNull, "Agent 回复被服务端拒绝")
                text = delta.string("content")
                text?.let {
                    agentCheck(it.length <= Constants.MAX_RESPONSE_CHARACTERS - contentCharacters, "Agent 回复超过本地安全长度限制")
                    contentCharacters += it.length
                    hasText = hasText || it.any { char -> !char.isWhitespace() }
                }
                val fragments = delta["tool_calls"]
                if (fragments != null && fragments != JsonNull) {
                    agentCheck(fragments is JsonArray && fragments.size <= AgentWireLimits.MAX_TOOLS, "Agent 工具调用增量格式无效")
                    val seen = HashSet<Int>()
                    for (element in fragments as JsonArray) {
                        val fragment = element as? JsonObject
                            ?: throw StreamProtocolException("Agent 工具调用增量格式无效")
                        val callIndex = fragment.index("index")
                        agentCheck(callIndex in 0 until AgentWireLimits.MAX_TOOLS, "Agent 工具调用索引超过限制")
                        agentCheck(seen.add(callIndex), "Agent 同一事件包含重复工具调用索引")
                        calls.getOrPut(callIndex) { PartialCall() }.append(fragment)
                    }
                }
            }
            choice.string("finish_reason")?.let { finish ->
                terminal = when (finish) {
                    "tool_calls" -> {
                        agentCheck(calls.isNotEmpty(), "Agent 工具完成原因与调用不匹配")
                        agentCheck(calls.keys.toList() == (0 until calls.size).toList(), "Agent 工具调用索引不完整")
                        val complete = calls.values.map { it.complete() }
                        agentCheck(complete.map { it.id }.distinct().size == complete.size, "Agent 工具调用标识重复")
                        AgentStreamEvent.Completed(finish, complete)
                    }
                    "stop", "length" -> {
                        agentCheck(calls.isEmpty(), "Agent 工具调用缺少匹配的 tool_calls 完成原因")
                        agentCheck(hasText, "Agent 未返回有效回复或工具调用")
                        AgentStreamEvent.Completed(finish)
                    }
                    "content_filter" -> throw apiFailure(null, """{"code":"content_filter"}""", json)
                    "function_call" -> throw StreamProtocolException("Agent 不支持旧版 function_call")
                    else -> throw StreamProtocolException("Agent 完成原因不受支持")
                }
            }
            return text?.takeIf { it.isNotEmpty() }?.let { listOf(AgentStreamEvent.TextDelta(it)) }.orEmpty()
        } catch (error: Exception) {
            failed = true
            throw error
        }
    }

    /** Wait for EOF/[DONE], so a trailing error or a duplicate terminal cannot authorize tools. */
    fun endOfStream(): AgentStreamEvent.Completed {
        agentCheck(!failed && !delivered, "Agent 回复已失败或重复完成")
        delivered = true
        return terminal ?: throw StreamProtocolException("Agent 回复在完成前中断，请重试")
    }

    private fun errorPayload(root: JsonObject, data: String) {
        if ((root["error"] != null && root["error"] != JsonNull) || root.string("type") == "error") {
            throw apiFailure(null, data, json)
        }
    }

    private fun legacy(value: JsonObject) {
        agentCheck(value["function_call"] == null || value["function_call"] == JsonNull, "Agent 不支持旧版 function_call")
    }

    private inner class PartialCall {
        private val id = StringBuilder()
        private val name = StringBuilder()
        private val arguments = StringBuilder()
        private var type: String? = null
        private var identitySealed = false
        private var bytes = 0

        fun append(fragment: JsonObject) {
            legacy(fragment)
            val declaredType = fragment.string("type")
            val repeatedHeader = type != null && declaredType != null
            declaredType?.let {
                agentCheck(it == "function" && (type == null || type == it), "Agent 工具调用类型不一致")
                type = it
            }
            identity(id, fragment.string("id"), AgentWireLimits.MAX_ID_CHARACTERS, repeatedHeader)
            val function = fragment["function"]
            agentCheck(function == null || function == JsonNull || function is JsonObject, "Agent 工具 function 格式无效")
            if (function is JsonObject) {
                identity(name, function.string("name"), AgentWireLimits.MAX_NAME_CHARACTERS, repeatedHeader)
                function.string("arguments")?.let { part ->
                    val size = agentUtf8Size(part, AgentWireLimits.MAX_ARGUMENT_BYTES)
                    agentCheck(size <= AgentWireLimits.MAX_ARGUMENT_BYTES - bytes, "Agent 单个工具参数超过限制")
                    agentCheck(size <= AgentWireLimits.MAX_TOTAL_ARGUMENT_BYTES - argumentBytes, "Agent 工具参数总量超过限制")
                    bytes += size
                    argumentBytes += size
                    arguments.append(part)
                    if (part.isNotEmpty()) {
                        agentIdentity(id.toString(), AgentWireLimits.MAX_ID_CHARACTERS)
                        agentIdentity(name.toString(), AgentWireLimits.MAX_NAME_CHARACTERS)
                        agentCheck(type == "function", "Agent 工具调用缺少类型")
                        identitySealed = true
                    }
                }
            }
        }

        private fun identity(target: StringBuilder, fragment: String?, limit: Int, repeatedHeader: Boolean) {
            if (fragment.isNullOrEmpty()) return
            // Replayed typed headers are identities, not suffixes. Arguments seal both identities.
            if (identitySealed || (repeatedHeader && target.isNotEmpty())) {
                agentCheck(fragment == target.toString(), "Agent 工具调用标识或名称发生冲突")
            } else {
                agentCheck(fragment.length <= limit - target.length, "Agent 工具调用标识或名称过长")
                target.append(fragment)
            }
        }

        fun complete(): AgentToolCall {
            agentIdentity(id.toString(), AgentWireLimits.MAX_ID_CHARACTERS)
            agentIdentity(name.toString(), AgentWireLimits.MAX_NAME_CHARACTERS)
            agentCheck(type == "function", "Agent 工具调用缺少 function 类型")
            val completeArguments = arguments.toString()
            AgentJsonGuard.objectValue(completeArguments, AgentWireLimits.MAX_ARGUMENT_BYTES)
            return AgentToolCall(id.toString(), AgentFunctionCall(name.toString(), completeArguments))
        }
    }

    private fun JsonObject.index(key: String, default: Int? = null): Int {
        val value = this[key] ?: return default ?: throw StreamProtocolException("Agent 缺少工具调用索引")
        agentCheck(value is JsonPrimitive && !value.isString, "Agent 索引必须是整数")
        return (value as JsonPrimitive).intOrNull ?: throw StreamProtocolException("Agent 索引必须是整数")
    }
}
