package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.agent.AgentChatMessage
import com.tongxie.copilotgo.data.agent.AgentChatRequest
import com.tongxie.copilotgo.data.agent.AgentFunctionCall
import com.tongxie.copilotgo.data.agent.AgentToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ResponsesContinuationTest {
    private val model = ModelInfo(
        RESPONSES_MODEL, supportedEndpoints = listOf("/responses"),
        capabilities = ModelCapabilities(supports = ModelSupports(toolCalls = true))
    )

    @Test
    fun replaysOriginalReasoningPhaseAndFunctionItemsExactlyInsteadOfRegeneratingThem() {
        val request = continuation()
        val encoded = AgentRequestEncoder.encode(request, Json, model, 96 * 1024)
        val input = Json.parseToJsonElement(encoded.body).jsonObject.getValue("input").jsonArray
        assertEquals(5, input.size)
        assertEquals(reasoningResponseOutput(), input.subList(1, 4))
        assertEquals("call_fixture", input.last().jsonObject.getValue("call_id").jsonPrimitive.content)
        assertEquals("function_call_output", input.last().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(REASONING_FIXTURE_ARGUMENTS,
            input[3].jsonObject.getValue("arguments").jsonPrimitive.content)
        assertFalse(encoded.body.contains("responsesOutput"))
        assertFalse(Json.encodeToString(AgentChatRequest.serializer(), request).contains(REASONING_FIXTURE_CIPHERTEXT))
        assertEquals("commentary", input[2].jsonObject.getValue("phase").jsonPrimitive.content)
    }

    @Test
    fun validatesAndDetachesOpaqueStateBeforeAnyAccountOrNetworkWork() {
        val mutable = reasoningResponseOutput().first().toMutableMap()
        val output = listOf(JsonObject(mutable)) + reasoningResponseOutput().drop(1)
        val validated = AgentRequestEncoder.validate(continuation(output), Json)
        mutable["encrypted_content"] = JsonPrimitive("changed-after-validation")
        val encoded = AgentRequestEncoder.encode(validated.request, Json, model, 96 * 1024)
        assertTrue(encoded.body.contains(REASONING_FIXTURE_CIPHERTEXT))
        assertFalse(encoded.body.contains("changed-after-validation"))
        assertThrows(StreamProtocolException::class.java) {
            AgentRequestEncoder.encode(validated.request, Json, ModelTransport.CHAT_COMPLETIONS)
        }
    }

    @Test
    fun cannotSubstituteToolIdentifiersArgumentsMessagesOrRolesUsingOpaqueState() {
        val original = reasoningResponseOutput()
        val variants = listOf(
            original.dropLast(1),
            original + original.last(),
            original.dropLast(1) + JsonObject(original.last() + ("call_id" to JsonPrimitive("other_call"))),
            original.dropLast(1) + JsonObject(original.last() + ("name" to JsonPrimitive("other_tool"))),
            original.dropLast(1) + JsonObject(original.last() + ("arguments" to JsonPrimitive("""{"query":"different"}"""))),
            original.dropLast(1) + JsonObject(original.last() + ("arguments" to JsonPrimitive("""{"query":"a","query":"b"}"""))),
            listOf(original[0], responseMessage("different"), original[2]),
            listOf(JsonObject(original[0] + ("status" to JsonPrimitive("in_progress")))) + original.drop(1),
            listOf(JsonObject(original[0].filterKeys { it != "encrypted_content" })) + original.drop(1),
            listOf(JsonObject(original[0] + ("type" to JsonPrimitive("compaction")))) + original.drop(1)
        )
        for (output in variants) assertThrows(StreamProtocolException::class.java) {
            AgentRequestEncoder.encode(continuation(output), Json, model)
        }
        val request = continuation()
        val misplaced = request.copy(messages = request.messages.map {
            if (it.role == "user") it.copy(responsesOutput = original) else it
        })
        assertThrows(StreamProtocolException::class.java) { AgentRequestEncoder.validate(misplaced, Json) }
    }

    @Test
    fun exactSelectedWireBudgetIncludesOpaqueStateExcludedFromChatDtoSerialization() {
        val budget = 96 * 1024
        val original = reasoningResponseOutput()
        val larger = listOf(JsonObject(original[0] + ("encrypted_content" to JsonPrimitive("x".repeat(budget))))) +
            original.drop(1)
        val request = continuation(larger)
        assertTrue(Json.encodeToString(AgentChatRequest.serializer(), request).toByteArray().size < budget)
        val exact = AgentRequestEncoder.encode(request, Json, model)
        assertTrue(exact.byteCount > budget)
        assertThrows(StreamProtocolException::class.java) {
            AgentRequestEncoder.encode(request, Json, model, budget)
        }
        assertEquals(exact.body, AgentRequestEncoder.encode(request, Json, model, exact.byteCount).body)
        assertThrows(StreamProtocolException::class.java) {
            AgentRequestEncoder.encode(request, Json, model, exact.byteCount - 1)
        }
    }

    @Test
    fun opaqueResponseBytesAndItemCountsAreBoundedBeforeSerialization() {
        val original = reasoningResponseOutput()
        val oversized = listOf(JsonObject(original[0] +
            ("encrypted_content" to JsonPrimitive("x".repeat(ResponsesOutputGuard.MAX_BYTES))))) + original.drop(1)
        assertThrows(StreamProtocolException::class.java) {
            AgentRequestEncoder.validate(continuation(oversized), Json)
        }
        assertThrows(StreamProtocolException::class.java) {
            AgentRequestEncoder.validate(continuation(List(ResponsesOutputGuard.MAX_ITEMS + 1) { original[0] }), Json)
        }
    }

    private fun continuation(output: List<JsonObject> = reasoningResponseOutput()): AgentChatRequest =
        agentTestRequest(RESPONSES_MODEL).copy(messages = listOf(
            AgentChatMessage("user", JsonPrimitive("杭州现在天气如何？")),
            AgentChatMessage(
                "assistant", JsonPrimitive(REASONING_FIXTURE_TEXT),
                toolCalls = listOf(AgentToolCall("call_fixture", AgentFunctionCall(
                    "lookup", """{"date":"2026-09-18","query":"杭州 天气"}"""
                ))),
                responsesOutput = output
            ),
            AgentChatMessage("tool", JsonPrimitive("""{"result":"synthetic fixture"}"""), toolCallId = "call_fixture")
        ))
}
