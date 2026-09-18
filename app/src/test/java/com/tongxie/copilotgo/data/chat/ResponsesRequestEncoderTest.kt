package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.agent.AgentChatMessage
import com.tongxie.copilotgo.data.agent.AgentFunctionCall
import com.tongxie.copilotgo.data.agent.AgentToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ResponsesRequestEncoderTest {
    @Test
    fun selectedProtocolPreflightMeasuresExactEncodedBytesIncludingHistoryImagesAndTools() {
        val request = agentTestRequest().copy(messages = listOf(
            AgentChatMessage("user", JsonPrimitive("历史问题")),
            AgentChatMessage("assistant", JsonPrimitive("历史回复")),
            AgentChatMessage("user", JsonArray(listOf(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/png;base64,Zml4dHVyZQ==") })
            })))
        ))
        for (transport in ModelTransport.entries) {
            val model = ModelInfo(
                request.model, supportedEndpoints = listOf(transport.endpoint),
                capabilities = ModelCapabilities(supports = ModelSupports(vision = true, toolCalls = true))
            )
            val exact = AgentRequestEncoder.encode(request, Json, model)
            assertEquals(exact.body.toByteArray(Charsets.UTF_8).size, exact.byteCount)
            assertTrue(exact.needsVision)
            assertEquals(exact.body, AgentRequestEncoder.encode(request, Json, model, exact.byteCount).body)
            assertThrows(StreamProtocolException::class.java) {
                AgentRequestEncoder.encode(request, Json, model, exact.byteCount - 1)
            }
            val constrained = model.copy(capabilities = model.capabilities?.copy(
                limits = ModelLimits(maxPromptTokens = exact.byteCount - 1)
            ))
            assertThrows(StreamProtocolException::class.java) {
                AgentRequestEncoder.encode(request, Json, constrained)
            }
        }
    }

    @Test
    fun responsesExpansionPast96KiBIsRejectedEvenWhenChatCompletionsWouldFit() {
        val budget = 96 * 1024
        val base = agentTestRequest().copy(messages = listOf(
            AgentChatMessage("user", JsonPrimitive("")),
            AgentChatMessage("assistant", JsonPrimitive("earlier reply")),
            AgentChatMessage("user", JsonPrimitive("follow up"))
        ))
        val padding = budget - AgentRequestEncoder.encode(base, Json).byteCount - 1
        val request = base.copy(messages = listOf(
            base.messages[0].copy(content = JsonPrimitive("x".repeat(padding)))
        ) + base.messages.drop(1))
        val chat = ModelInfo(
            request.model, supportedEndpoints = listOf("/chat/completions"),
            capabilities = ModelCapabilities(supports = ModelSupports(toolCalls = true))
        )
        assertTrue(AgentRequestEncoder.encode(request, Json, chat, budget).byteCount < budget)
        assertTrue(AgentRequestEncoder.encode(request, Json, ModelTransport.RESPONSES).byteCount > budget)
        assertThrows(StreamProtocolException::class.java) {
            AgentRequestEncoder.encode(request, Json, chat.copy(supportedEndpoints = listOf("/responses")), budget)
        }
    }

    @Test
    fun requiredSearchChoiceIsPreservedOnEveryAdmittedTransport() {
        for (transport in ModelTransport.entries) {
            for (choice in listOf("required", "auto", "none")) {
                val request = agentTestRequest().copy(toolChoice = choice)
                val root = Json.parseToJsonElement(AgentRequestEncoder.encode(request, Json, transport).body).jsonObject
                assertEquals(choice, root.getValue("tool_choice").jsonPrimitive.content)
                assertEquals(1, root.getValue("tools").jsonArray.size)
            }
            assertThrows(StreamProtocolException::class.java) {
                AgentRequestEncoder.encode(agentTestRequest().copy(toolChoice = "required", tools = emptyList()), Json, transport)
            }
        }
    }

    @Test
    fun responsesUsesInputItemsFlatToolsAndExactFunctionResultIdentifiers() {
        val call = AgentToolCall("call_not_the_item_id", AgentFunctionCall("lookup", """{"query":"杭州"}"""))
        val request = agentTestRequest(RESPONSES_MODEL).copy(messages = listOf(
            AgentChatMessage("system", JsonPrimitive("fixture policy")),
            AgentChatMessage("developer", JsonPrimitive("fixture context")),
            AgentChatMessage("user", JsonPrimitive("fixture question")),
            AgentChatMessage("assistant", JsonPrimitive("checking"), toolCalls = listOf(call)),
            AgentChatMessage("tool", JsonPrimitive("""{"answer":"ok"}"""), toolCallId = call.id)
        ))
        val encoded = AgentRequestEncoder.encode(request, Json, ModelTransport.RESPONSES)
        val root = Json.parseToJsonElement(encoded.body).jsonObject
        assertEquals(RESPONSES_MODEL, root.getValue("model").jsonPrimitive.content)
        assertTrue(root.getValue("stream").jsonPrimitive.boolean)
        assertFalse(root.getValue("store").jsonPrimitive.boolean)
        assertEquals("disabled", root.getValue("truncation").jsonPrimitive.content)
        for (omitted in listOf("messages", "n", "temperature", "top_p", "previous_response_id")) assertFalse(root.containsKey(omitted))
        val tool = root.getValue("tools").jsonArray.single().jsonObject
        assertEquals("lookup", tool.getValue("name").jsonPrimitive.content)
        assertFalse(tool.getValue("strict").jsonPrimitive.boolean)
        assertFalse(tool.containsKey("function"))
        val input = root.getValue("input").jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "developer", "user", "assistant"),
            input.take(4).map { it.getValue("role").jsonPrimitive.content })
        assertEquals("output_text", input[3].getValue("content").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("completed", input[3].getValue("status").jsonPrimitive.content)
        assertEquals("function_call", input[4].getValue("type").jsonPrimitive.content)
        assertEquals(call.id, input[4].getValue("call_id").jsonPrimitive.content)
        assertEquals(call.function.arguments, input[4].getValue("arguments").jsonPrimitive.content)
        assertEquals("function_call_output", input[5].getValue("type").jsonPrimitive.content)
        assertEquals(call.id, input[5].getValue("call_id").jsonPrimitive.content)
        assertEquals("""{"answer":"ok"}""", input[5].getValue("output").jsonPrimitive.content)
    }

    @Test
    fun ordinaryAndMultimodalHistoryUseRoleAppropriateContentWithoutAgentTools() {
        val request = VisionRequest(RESPONSES_MODEL, listOf(
            VisionMessage("user", listOf(
                VisionContentPart("text", "first question"),
                VisionContentPart("image_url", imageUrl = VisionImageUrl("data:image/png;base64,Zml4dHVyZQ==", "low"))
            )),
            VisionMessage("assistant", listOf(VisionContentPart("text", "earlier answer"))),
            VisionMessage("user", listOf(VisionContentPart("text", "follow up")))
        ))
        val encoded = ChatRequestEncoder.encode(request, Json, ModelTransport.RESPONSES)
        assertTrue(encoded.needsVision)
        val root = Json.parseToJsonElement(encoded.body).jsonObject
        assertFalse(root.containsKey("tools"))
        assertFalse(root.containsKey("tool_choice"))
        val messages = root.getValue("input").jsonArray
        val parts = messages.first().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals("input_text", parts[0].getValue("type").jsonPrimitive.content)
        assertEquals("input_image", parts[1].getValue("type").jsonPrimitive.content)
        assertEquals("low", parts[1].getValue("detail").jsonPrimitive.content)
        assertEquals("data:image/png;base64,Zml4dHVyZQ==", parts[1].getValue("image_url").jsonPrimitive.content)
        assertEquals("output_text", messages[1].jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        val plain = ChatRequestEncoder.encode(
            ChatRequest("legacy", listOf(ChatMessage("user", "hello")), topP = 0.75),
            Json, ModelTransport.CHAT_COMPLETIONS
        )
        val legacy = Json.parseToJsonElement(plain.body).jsonObject
        assertEquals("0.75", legacy.getValue("top_p").jsonPrimitive.content)
        assertTrue(legacy.containsKey("messages"))
        assertFalse(legacy.containsKey("input"))
        assertFalse(legacy.containsKey("tools"))
    }

    @Test
    fun orphanDuplicateAndUnfinishedCallResultsCannotBeReplayed() {
        val call = AgentToolCall("call_fixture", AgentFunctionCall("lookup", "{}"))
        val assistant = AgentChatMessage("assistant", toolCalls = listOf(call))
        val result = AgentChatMessage("tool", JsonPrimitive("fixture output"), toolCallId = call.id)
        for (messages in listOf(
            listOf(result),
            listOf(assistant),
            listOf(assistant, result, result),
            listOf(assistant, result.copy(toolCallId = "different")),
            listOf(assistant, AgentChatMessage("user", JsonPrimitive("interrupted")), result)
        )) {
            for (transport in ModelTransport.entries) assertThrows(StreamProtocolException::class.java) {
                AgentRequestEncoder.encode(agentTestRequest().copy(messages = messages), Json, transport)
            }
        }
    }

    @Test
    fun requestFlagsAndUnsupportedImageLocationsFailBeforeTransmission() {
        val request = ChatRequest(RESPONSES_MODEL, listOf(ChatMessage("user", "fixture")))
        for (invalid in listOf(request.copy(n = 2), request.copy(stream = false), request.copy(topP = Double.NaN))) {
            assertThrows(StreamProtocolException::class.java) {
                ChatRequestEncoder.encode(invalid, Json, ModelTransport.RESPONSES)
            }
        }
        val image = JsonArray(listOf(buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", "https://example.test/image.png") })
        }))
        assertThrows(StreamProtocolException::class.java) {
            AgentRequestEncoder.encode(agentTestRequest().copy(messages = listOf(
                AgentChatMessage("assistant", image)
            )), Json, ModelTransport.RESPONSES)
        }
    }
}
