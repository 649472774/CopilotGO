package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.agent.AgentChatMessage
import com.tongxie.copilotgo.data.agent.AgentChatRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stateless Responses contract used by vscode-copilot-chat's responsesApi.ts (v0.43.0). */
internal object ResponsesRequestEncoder {
    fun encode(request: AgentChatRequest, json: Json): String {
        val input = buildJsonArray {
            for ((index, message) in request.messages.withIndex()) {
                if (message.responsesOutput.isNotEmpty()) {
                    message.responsesOutput.forEach { add(it) }
                    continue
                }
                if (message.role == "tool") {
                    add(buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", requireNotNull(message.toolCallId))
                        put("output", message.content as JsonPrimitive)
                    })
                    continue
                }
                val parts = content(message)
                if (parts.isNotEmpty()) {
                    add(buildJsonObject {
                        put("type", "message")
                        put("role", message.role)
                        put("content", parts)
                        if (message.role == "assistant") {
                            put("id", "msg_history_$index")
                            put("status", "completed")
                        }
                    })
                }
                for (call in message.toolCalls.orEmpty()) {
                    add(buildJsonObject {
                        put("type", "function_call")
                        put("call_id", call.id)
                        put("name", call.function.name)
                        put("arguments", call.function.arguments)
                    })
                }
            }
        }
        val root = buildJsonObject {
            put("model", request.model)
            put("input", input)
            put("stream", true)
            put("store", false)
            put("truncation", "disabled")
            put("include", JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content"))))
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.function.name)
                            put("description", tool.function.description)
                            put("parameters", tool.function.parameters)
                            put("strict", false)
                        })
                    }
                })
                put("tool_choice", request.toolChoice)
                put("parallel_tool_calls", request.parallelToolCalls)
            }
        }
        AgentJsonGuard.TreeBudget(AgentWireLimits.MAX_REQUEST_BYTES).copy(root)
        return Json(json) { prettyPrint = false }.encodeToString(JsonObject.serializer(), root).also {
            AgentJsonGuard.objectValue(it, AgentWireLimits.MAX_REQUEST_BYTES)
        }
    }

    private fun content(message: AgentChatMessage): JsonArray {
        val source = when (val content = message.content) {
            null, JsonNull -> emptyList()
            is JsonPrimitive -> listOf(buildJsonObject {
                put("type", "text")
                put("text", content)
            })
            is JsonArray -> content
            else -> throw StreamProtocolException("Responses 消息内容格式无效")
        }
        return JsonArray(source.map { element ->
            val part = element as JsonObject
            when (part.requiredString("type")) {
                "text" -> buildJsonObject {
                    put("type", if (message.role == "assistant") "output_text" else "input_text")
                    put("text", part.requiredString("text"))
                    if (message.role == "assistant") put("annotations", JsonArray(emptyList()))
                }
                "image_url" -> {
                    agentCheck(message.role == "user", "Responses 只接受用户消息中的图片")
                    val image = part["image_url"] as JsonObject
                    buildJsonObject {
                        put("type", "input_image")
                        put("image_url", image.requiredString("url"))
                        put("detail", image.string("detail") ?: "auto")
                    }
                }
                else -> throw StreamProtocolException("Responses 不支持此消息内容类型")
            }
        })
    }
}
