package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.agent.AgentToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Opaque reasoning stays transport-only and is bound to the exact visible text and proposed calls. */
internal object ResponsesOutputGuard {
    const val MAX_ITEMS = 256
    const val MAX_BYTES = AgentWireLimits.MAX_EVENT_BYTES

    fun snapshot(
        output: List<JsonObject>,
        assistantText: String,
        calls: List<AgentToolCall>
    ): List<JsonObject> {
        agentCheck(output.size in 1..MAX_ITEMS, "Responses 续接输出项数量无效")
        val snapshot = (AgentJsonGuard.TreeBudget(MAX_BYTES).copy(JsonArray(output)) as JsonArray)
            .map { it as JsonObject }
        val ids = HashSet<String>()
        val seenCalls = HashSet<String>()
        val expectedCalls = calls.associateBy { it.id }
        agentCheck(expectedCalls.size == calls.size, "Responses 续接工具调用标识重复")
        val text = StringBuilder()
        for (item in snapshot) {
            val id = item.requiredString("id")
            agentOutputItemId(id)
            agentCheck(ids.add(id), "Responses 续接输出项标识重复")
            agentCheck(item.string("status") in setOf(null, "completed"), "Responses 续接输出尚未完成")
            item.string("phase")?.let { agentCheck(it.length <= 64, "Responses 消息阶段标识过长") }
            when (item.requiredString("type")) {
                "reasoning" -> {
                    val summary = item["summary"] as? JsonArray
                        ?: throw StreamProtocolException("Responses 推理输出缺少 summary")
                    agentCheck(summary.size <= MAX_ITEMS, "Responses 推理摘要项过多")
                    summary.forEach { element ->
                        val part = element as? JsonObject
                            ?: throw StreamProtocolException("Responses 推理摘要格式无效")
                        agentCheck(part.string("type") == "summary_text", "Responses 推理摘要类型不受支持")
                        part.requiredString("text")
                    }
                    val encrypted = item.string("encrypted_content")
                    if (calls.isNotEmpty()) {
                        agentCheck(!encrypted.isNullOrBlank(),
                            "Responses 模型未返回工具续接必需的加密推理状态，本次工具未执行")
                    }
                }
                "message" -> {
                    agentCheck(item.string("role") == "assistant", "Responses 续接消息角色无效")
                    val content = item["content"] as? JsonArray
                        ?: throw StreamProtocolException("Responses 续接消息缺少内容")
                    agentCheck(content.size <= MAX_ITEMS, "Responses 续接内容项过多")
                    content.forEach { element ->
                        val part = element as? JsonObject
                            ?: throw StreamProtocolException("Responses 续接消息内容格式无效")
                        agentCheck(part.string("type") == "output_text", "Responses 续接消息内容类型不受支持")
                        val value = part.requiredString("text")
                        agentCheck(value.length <= Constants.MAX_RESPONSE_CHARACTERS - text.length,
                            "Responses 续接文字超过大小限制")
                        text.append(value)
                    }
                }
                "function_call" -> {
                    val callId = item.requiredString("call_id")
                    agentToolCallId(callId)
                    agentToolName(item.requiredString("name"))
                    agentCheck(seenCalls.add(callId), "Responses 续接工具调用重复")
                    val expected = expectedCalls[callId]
                        ?: throw StreamProtocolException("Responses 续接输出含有不匹配的工具调用")
                    agentCheck(expected.type == "function" && item.string("name") == expected.function.name,
                        "Responses 续接工具名称或类型不一致")
                    // The engine canonicalizes argument JSON; retain the server's original wire string.
                    agentCheck(
                        AgentJsonGuard.objectValue(item.requiredString("arguments"), AgentWireLimits.MAX_ARGUMENT_BYTES) ==
                            AgentJsonGuard.objectValue(expected.function.arguments, AgentWireLimits.MAX_ARGUMENT_BYTES),
                        "Responses 续接工具参数与已确认调用不一致"
                    )
                }
                else -> throw StreamProtocolException("Responses 续接输出类型不受支持")
            }
        }
        agentCheck(seenCalls == expectedCalls.keys, "Responses 续接输出遗漏工具调用")
        agentCheck(text.toString() == assistantText || (text.isBlank() && assistantText.isBlank()),
            "Responses 续接文字与已接收回复不一致")
        return snapshot
    }
}
