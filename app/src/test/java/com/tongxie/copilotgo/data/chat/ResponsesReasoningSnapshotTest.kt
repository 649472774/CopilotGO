package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.agent.AgentChatMessage
import com.tongxie.copilotgo.data.agent.AgentChatRequest
import com.tongxie.copilotgo.data.agent.AgentStreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ResponsesReasoningSnapshotTest {
    @Test
    fun ordinaryResponseAcceptsChangedCiphertextAfterReasoningItemDone() {
        val done = JsonObject(responseReasoning() +
            ("encrypted_content" to JsonPrimitive(copilotOpaqueCiphertext(1))))
        val final = JsonObject(done + ("encrypted_content" to JsonPrimitive(copilotOpaqueCiphertext(2))))
        val assembler = ResponsesStreamAssembler(Json, allowTools = false)
        assembler.accept(responseEvent("response.created") {
            put("response", buildJsonObject { put("id", "resp_same"); put("status", "in_progress") })
        })
        assembler.accept(responseAdded(0, JsonObject(done + ("encrypted_content" to JsonNull))))
        assembler.accept(responseItemDone(0, done))
        val deltas = assembler.accept(responseCompleted(final, responseMessage("answer"), id = "resp_same"))
        assertEquals(listOf(AgentStreamEvent.TextDelta("answer")), deltas)
        assertEquals(listOf(final, responseMessage("answer")), assembler.endOfStream().responsesOutput)
    }

    @Test
    fun plainLunaAndSolReasoningUseTheTerminalPairWithoutDisplayingOpaqueState() {
        for (model in MODELS) {
            val events = copilotTextEvents("answer", model, includeEncryptedReasoning = true)
            val assembler = ResponsesStreamAssembler(Json, allowTools = false)
            val deltas = events.flatMap { assembler.accept(it) }
            val final = output(events.last())
            val done = item(events.first { it.event == "response.output_item.done" })
            assertNotEquals(done.requiredString("id"), final[0].requiredString("id"))
            assertNotEquals(done.requiredString("encrypted_content"), final[0].requiredString("encrypted_content"))
            assertEquals("answer", deltas.joinToString("") { it.text })
            val completed = assembler.endOfStream()
            assertEquals("stop", completed.finishReason)
            assertTrue(completed.toolCalls.isEmpty())
            assertEquals(final, completed.responsesOutput)
        }
    }

    @Test
    fun toolContinuationReplaysTheWholeTerminalItemNotAMixOfOpaqueSnapshots() {
        for (model in MODELS) {
            val events = copilotResponseEvents(reasoningResponseEvents(), model)
            val assembler = ResponsesStreamAssembler(Json)
            val deltas = events.flatMap { assembler.accept(it) }
            val completed = assembler.endOfStream()
            val final = output(events.last())
            assertEquals(final, completed.responsesOutput)
            assertEquals(REASONING_FIXTURE_TEXT, deltas.joinToString("") { it.text })
            assertEquals(REASONING_FIXTURE_ARGUMENTS, completed.toolCalls.single().function.arguments)
            val next = agentTestRequest(model).copy(messages = listOf(
                AgentChatMessage("user", JsonPrimitive("synthetic question")),
                AgentChatMessage("assistant", JsonPrimitive(REASONING_FIXTURE_TEXT), completed.toolCalls,
                    responsesOutput = completed.responsesOutput),
                AgentChatMessage("tool", JsonPrimitive("synthetic result"), toolCallId = "call_fixture")
            ))
            val validated = AgentRequestEncoder.validate(next, Json)
            val encoded = AgentRequestEncoder.encode(validated.request, Json, ModelTransport.RESPONSES)
            val input = Json.parseToJsonElement(encoded.body).jsonObject.getValue("input").jsonArray
            assertEquals(final, input.subList(1, input.lastIndex))
            assertEquals("call_fixture", input.last().jsonObject.requiredString("call_id"))
            val serialized = Json.encodeToString(AgentChatRequest.serializer(), next)
            for (event in events.filter { it.event == "response.output_item.done" }) {
                val previous = item(event)
                assertFalse(encoded.body.contains(previous.requiredString("id")))
                previous.string("encrypted_content")?.let { assertFalse(encoded.body.contains(it)) }
            }
            final.forEach { value ->
                assertFalse(serialized.contains(value.requiredString("id")))
                value.string("encrypted_content")?.let { assertFalse(serialized.contains(it)) }
            }
        }
    }

    @Test
    fun terminalCanSupplyCiphertextWhenEarlierReasoningItemHadNone() {
        for (earlier in listOf<JsonElement?>(null, JsonNull)) {
            val done = JsonObject(responseReasoning().filterKeys { it != "encrypted_content" } +
                (earlier?.let { mapOf("encrypted_content" to it) } ?: emptyMap()))
            val assembler = ResponsesStreamAssembler(Json)
            assembler.accept(responseItemDone(0, done))
            assembler.accept(responseCompleted(responseReasoning(), responseCall()))
            assertEquals(listOf(responseReasoning(), responseCall()), assembler.endOfStream().responsesOutput)
        }
    }

    @Test
    fun rotatingCiphertextDoesNotHideChangesToStableFieldsOrMissingOutput() {
        val events = copilotResponseEvents(reasoningResponseEvents())
        val original = output(events.last())
        val summary = JsonArray(listOf(buildJsonObject {
            put("type", "summary_text"); put("text", "different logical summary")
        }))
        val cases = listOf(
            "推理摘要" to original.changed(0, "summary", summary),
            "call_id" to original.changed(2, "call_id", JsonPrimitive("changed_call")),
            "工具名称" to original.changed(2, "name", JsonPrimitive("changed_name")),
            "工具参数增量与完整参数" to original.changed(2, "arguments", JsonPrimitive("""{"query":"changed"}""")),
            "文字增量与完整输出" to original.changed(1, "content", responseMessage("changed").getValue("content")),
            "类型" to original.changed(0, "type", JsonPrimitive("message")),
            "尚未完成" to original.changed(0, "status", JsonPrimitive("in_progress")),
            "阶段标识" to original.changed(1, "phase", JsonPrimitive("final_answer")),
            "遗漏输出项" to original.dropLast(1)
        )
        for ((reason, changed) in cases) {
            val assembler = ResponsesStreamAssembler(Json)
            events.dropLast(1).forEach { assembler.accept(it) }
            val failure = assertThrows(StreamProtocolException::class.java) {
                assembler.accept(terminal(events.last(), changed))
            }
            assertTrue(failure.message, failure.message.orEmpty().contains(reason))
            assertTrue(assembler.outputItems.isEmpty())
            assertThrows(StreamProtocolException::class.java) { assembler.endOfStream() }
        }
    }

    @Test
    fun finalCiphertextCannotBeFilledFromAnEarlierSnapshotIfMissingOrMalformed() {
        val events = copilotResponseEvents(reasoningResponseEvents())
        val original = output(events.last())
        for (encrypted in listOf<JsonElement?>(null, JsonNull, JsonPrimitive(""), JsonPrimitive(" "), JsonPrimitive(1))) {
            val fields = original.first().toMutableMap()
            if (encrypted == null) fields.remove("encrypted_content") else fields["encrypted_content"] = encrypted
            val assembler = ResponsesStreamAssembler(Json)
            events.dropLast(1).forEach { assembler.accept(it) }
            assertThrows(StreamProtocolException::class.java) {
                assembler.accept(terminal(events.last(), listOf(JsonObject(fields)) + original.drop(1)))
            }
            assertTrue(assembler.outputItems.isEmpty())
            assertThrows(StreamProtocolException::class.java) { assembler.endOfStream() }
        }
    }

    @Test
    fun opaqueReplacementStillRejectsCrossIndexAliasesAndBrokenLifecycle() {
        val events = copilotResponseEvents(reasoningResponseEvents())
        val oldReasoningId = item(events.first { it.event == "response.output_item.done" }).requiredString("id")
        val assembler = ResponsesStreamAssembler(Json)
        events.dropLast(1).forEach { assembler.accept(it) }
        val changed = output(events.last()).changed(2, "id", JsonPrimitive(oldReasoningId))
        assertTrue(assertThrows(StreamProtocolException::class.java) {
            assembler.accept(terminal(events.last(), changed))
        }.message.orEmpty().contains("索引"))
        val duplicate = ResponsesStreamAssembler(Json)
        events.forEach { duplicate.accept(it) }
        assertThrows(StreamProtocolException::class.java) { duplicate.accept(responseCompleted(*output(events.last()).toTypedArray())) }
        assertThrows(StreamProtocolException::class.java) { duplicate.endOfStream() }
        val missing = ResponsesStreamAssembler(Json)
        events.dropLast(1).forEach { missing.accept(it) }
        assertThrows(StreamProtocolException::class.java) { missing.endOfStream() }
        assertTrue(missing.outputItems.isEmpty())
    }

    private fun output(event: SseEvent): List<JsonObject> =
        Json.parseToJsonElement(event.data).jsonObject.getValue("response").jsonObject
            .getValue("output").jsonArray.map { it.jsonObject }

    private fun item(event: SseEvent): JsonObject = Json.parseToJsonElement(event.data).jsonObject.getValue("item").jsonObject

    private fun List<JsonObject>.changed(index: Int, key: String, value: JsonElement): List<JsonObject> =
        mapIndexed { position, item -> if (position == index) JsonObject(item + (key to value)) else item }

    private fun terminal(event: SseEvent, output: List<JsonObject>): SseEvent {
        val root = Json.parseToJsonElement(event.data).jsonObject
        val response = root.getValue("response").jsonObject
        return event.copy(data = JsonObject(root + ("response" to JsonObject(
            response + ("output" to JsonArray(output))
        ))).toString())
    }

    companion object {
        private val MODELS = listOf("gpt-5.6-luna", "gpt-5.6-sol")
    }
}
