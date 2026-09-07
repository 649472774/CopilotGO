package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.agent.AgentChatMessage
import com.tongxie.copilotgo.data.agent.AgentChatRequest
import com.tongxie.copilotgo.data.agent.AgentFunctionDefinition
import com.tongxie.copilotgo.data.agent.AgentToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun agentTestRequest(model: String = "fixture-chat") = AgentChatRequest(
    model,
    listOf(AgentChatMessage("user", JsonPrimitive("fixture question"))),
    listOf(AgentToolDefinition(AgentFunctionDefinition(
        "lookup", "Read synthetic test data", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject { put("type", "string") })
            })
        }
    )))
)

internal fun agentFragment(
    index: Int = 0,
    id: String? = null,
    name: String? = null,
    arguments: String? = null,
    type: String? = if (id == null) null else "function"
) = buildJsonObject {
    put("index", index)
    id?.let { put("id", it) }
    type?.let { put("type", it) }
    if (name != null || arguments != null) {
        put("function", buildJsonObject {
            name?.let { put("name", it) }
            arguments?.let { put("arguments", it) }
        })
    }
}

internal fun agentChunk(
    fragments: List<JsonObject>? = null,
    content: String? = null,
    finish: String? = null,
    choice: Int = 0
) = SseEvent("message", buildJsonObject {
    put("object", "chat.completion.chunk")
    put("choices", JsonArray(listOf(buildJsonObject {
        put("index", choice)
        put("delta", buildJsonObject {
            content?.let { put("content", it) }
            fragments?.let { put("tool_calls", JsonArray(it)) }
        })
        finish?.let { put("finish_reason", it) }
    })))
}.toString())

internal fun agentSse(vararg events: SseEvent): String = events.joinToString("") {
    "event: ${it.event}\ndata: ${it.data}\n\n"
}

internal fun agentCompleteCall(arguments: String = """{"query":"fixture"}""") =
    agentChunk(listOf(agentFragment(id = "call_fixture", name = "lookup", arguments = arguments)))
