package com.tongxie.copilotgo.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class AgentFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class AgentToolDefinition(
    val function: AgentFunctionDefinition,
    val type: String = "function"
)

@Serializable
data class AgentFunctionCall(val name: String, val arguments: String)

/** Arguments are complete JSON, never a streamed fragment. */
@Serializable
data class AgentToolCall(
    val id: String,
    val function: AgentFunctionCall,
    val type: String = "function"
)

@Serializable
data class AgentChatMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<AgentToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class AgentChatRequest(
    val model: String,
    val messages: List<AgentChatMessage>,
    val tools: List<AgentToolDefinition>,
    val stream: Boolean = true,
    val temperature: Double = 0.1,
    val n: Int = 1,
    @SerialName("tool_choice") val toolChoice: String = "auto",
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean = false
)

sealed interface AgentStreamEvent {
    data class TextDelta(val text: String, val choiceIndex: Int = 0) : AgentStreamEvent
    data class Completed(
        val finishReason: String,
        val toolCalls: List<AgentToolCall> = emptyList(),
        val choiceIndex: Int = 0
    ) : AgentStreamEvent
}

fun interface AgentModelTransport {
    fun stream(request: AgentChatRequest): Flow<AgentStreamEvent>
}
