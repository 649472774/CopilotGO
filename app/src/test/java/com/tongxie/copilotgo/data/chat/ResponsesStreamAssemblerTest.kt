package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.agent.AgentStreamEvent
import com.tongxie.copilotgo.data.net.ApiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ResponsesStreamAssemblerTest {
    @Test
    fun textAndTerminalSnapshotsDoNotDuplicateAndReasoningIsNotShown() {
        val assembler = ResponsesStreamAssembler(Json)
        assembler.accept(responseAdded(0, responseReasoning()))
        assembler.accept(responseAdded(1, responseMessage(complete = false)))
        assertEquals(listOf(AgentStreamEvent.TextDelta("hello")), assembler.accept(responseText("hello", 1)))
        assertEquals(listOf(AgentStreamEvent.TextDelta(" world")), assembler.accept(responseText(" world", 1)))
        assembler.accept(responseEvent("response.output_text.done") {
            put("output_index", 1); put("content_index", 0); put("item_id", "msg_fixture"); put("text", "hello world")
        })
        assertTrue(assembler.accept(responseItemDone(1, responseMessage("hello world"))).isEmpty())
        assertTrue(assembler.accept(responseCompleted(responseReasoning(), responseMessage("hello world"))).isEmpty())
        assertEquals("stop", assembler.endOfStream().finishReason)
        assertEquals(2, assembler.outputItems.size)
    }

    @Test
    fun completedOnlySnapshotsRecoverTextWithoutInventingSuccessBeforeStreamEnd() {
        val assembler = ResponsesStreamAssembler(Json)
        assertEquals(listOf(AgentStreamEvent.TextDelta("full answer")),
            assembler.accept(responseCompleted(responseMessage("full answer"))))
        assertFalse(assembler.isDone)
        assembler.accept(SseEvent("message", "[DONE]"))
        assertTrue(assembler.isDone)
        assertEquals(
            AgentStreamEvent.Completed("stop", responsesOutput = listOf(responseMessage("full answer"))),
            assembler.endOfStream()
        )
        assertThrows(StreamProtocolException::class.java) { assembler.endOfStream() }
    }

    @Test
    fun interleavedFunctionArgumentsUseCallIdsNotOutputItemIdsAndWaitForResponseCompletion() {
        val assembler = ResponsesStreamAssembler(Json)
        assembler.accept(responseAdded(0, responseReasoning()))
        assembler.accept(responseAdded(1, responseCall("", "call_a", "fc_a", complete = false)))
        assembler.accept(responseAdded(2, responseCall("", "call_b", "fc_b", complete = false)))
        assembler.accept(responseArguments("""{"query":""", 2, "fc_b"))
        assembler.accept(responseArguments("""{"query":"杭""", 1, "fc_a"))
        assembler.accept(responseArguments("\"other\"}", 2, "fc_b"))
        assembler.accept(responseArguments("州\"}", 1, "fc_a"))
        val a = responseCall("""{"query":"杭州"}""", "call_a", "fc_a")
        val b = responseCall("""{"query":"other"}""", "call_b", "fc_b")
        assembler.accept(responseItemDone(2, b))
        assembler.accept(responseItemDone(1, a))
        assertTrue(assembler.accept(responseCompleted(responseReasoning(), a, b)).isEmpty())
        val completed = assembler.endOfStream()
        assertEquals("tool_calls", completed.finishReason)
        assertEquals(listOf("call_a", "call_b"), completed.toolCalls.map { it.id })
        assertEquals(listOf("""{"query":"杭州"}""", """{"query":"other"}"""),
            completed.toolCalls.map { it.function.arguments })
    }

    @Test
    fun missingFailedIncompleteAndConflictingTerminalStatesNeverAuthorizeTools() {
        for (suffix in listOf<SseEvent?>(
            null,
            SseEvent("message", "[DONE]"),
            responseCompleted(responseCall(), status = "in_progress"),
            responseEvent("response.incomplete") {
                put("response", buildJsonObject {
                    put("id", "resp_fixture"); put("status", "incomplete")
                    put("incomplete_details", buildJsonObject { put("reason", "max_output_tokens") })
                })
            },
            responseEvent("response.failed") {
                put("response", buildJsonObject {
                    put("id", "resp_fixture"); put("status", "failed")
                    put("error", buildJsonObject { put("code", "overloaded") })
                })
            }
        )) {
            val assembler = ResponsesStreamAssembler(Json)
            assembler.accept(responseItemDone(0, responseCall()))
            assertThrows(java.io.IOException::class.java) {
                suffix?.let { assembler.accept(it) }
                assembler.endOfStream()
            }
        }
    }

    @Test
    fun trailingErrorsAndDuplicateTerminalsCannotTurnAProposalIntoSuccess() {
        for (suffix in listOf(
            responseCompleted(responseCall()),
            SseEvent("error", """{"code":"overloaded"}"""),
            responseEvent("error") { put("code", "overloaded") },
            responseAdded(1, responseMessage(complete = false))
        )) {
            val assembler = ResponsesStreamAssembler(Json)
            assembler.accept(responseCompleted(responseCall()))
            assertThrows(java.io.IOException::class.java) { assembler.accept(suffix) }
            assertThrows(StreamProtocolException::class.java) { assembler.endOfStream() }
        }
    }

    @Test
    fun mismatchedIdsArgumentsMissingOutputsAndRefusalsFailClosed() {
        for (event in listOf(
            responseArguments("{}", id = "different"),
            responseItemDone(0, responseCall(callId = "changed")),
            responseItemDone(0, responseCall(name = "changed")),
            responseItemDone(0, responseCall(id = "changed")),
            responseItemDone(0, responseCall("[]")),
            responseItemDone(0, responseCall("""{"x":1,"x":2}""")),
            responseCompleted(responseMessage("unrelated"))
        )) {
            val assembler = ResponsesStreamAssembler(Json)
            assembler.accept(responseAdded(0, responseCall("", complete = false)))
            assertThrows(StreamProtocolException::class.java) { assembler.accept(event) }
        }
        val changedArguments = ResponsesStreamAssembler(Json)
        changedArguments.accept(responseAdded(0, responseCall("", complete = false)))
        changedArguments.accept(responseArguments("""{"x":"""))
        assertThrows(StreamProtocolException::class.java) {
            changedArguments.accept(responseItemDone(0, responseCall("""{"y":1}""")))
        }
        val refusal = ResponsesStreamAssembler(Json)
        refusal.accept(responseAdded(0, responseMessage(complete = false)))
        assertThrows(ApiException::class.java) {
            refusal.accept(responseEvent("response.refusal.delta") { put("delta", "refused") })
        }
    }

    @Test
    fun duplicateCallsUnsupportedOutputAndOrdinaryToolRequestsAreRejected() {
        val duplicates = ResponsesStreamAssembler(Json)
        assertThrows(StreamProtocolException::class.java) {
            duplicates.accept(responseCompleted(responseCall(id = "fc_a"), responseCall(id = "fc_b")))
        }
        assertThrows(StreamProtocolException::class.java) {
            ResponsesStreamAssembler(Json, allowTools = false).accept(responseCompleted(responseCall()))
        }
        assertThrows(StreamProtocolException::class.java) {
            ResponsesStreamAssembler(Json).accept(responseCompleted(buildJsonObject {
                put("type", "web_search_call"); put("id", "unrequested_builtin")
            }))
        }
        assertThrows(StreamProtocolException::class.java) {
            ResponsesStreamAssembler(Json).accept(responseCompleted(responseReasoning()))
        }
    }

    @Test
    fun contradictoryTextAndMalformedEventFramingAreRejected() {
        val assembler = ResponsesStreamAssembler(Json)
        assembler.accept(responseAdded(0, responseMessage(complete = false)))
        assembler.accept(responseText("partial"))
        assertThrows(StreamProtocolException::class.java) {
            assembler.accept(responseCompleted(responseMessage("different")))
        }
        for (event in listOf(
            SseEvent("response.completed", """{"type":"response.failed"}"""),
            SseEvent("message", "not-json"),
            SseEvent("message", """{"type":"response.completed","type":"error"}"""),
            responseEvent("response.completed") {
                put("response", buildJsonObject { put("id", "resp_fixture"); put("status", "completed"); put("output", JsonArray(emptyList())) })
            },
            responseText("text without an output item")
        )) {
            assertThrows(StreamProtocolException::class.java) { ResponsesStreamAssembler(Json).accept(event) }
        }
    }

    @Test
    fun argumentsAndToolCountsAreBoundedBeforeExecution() {
        val assembler = ResponsesStreamAssembler(Json)
        assembler.accept(responseAdded(0, responseCall("", complete = false)))
        assertThrows(StreamProtocolException::class.java) {
            assembler.accept(responseArguments("x".repeat(AgentWireLimits.MAX_ARGUMENT_BYTES + 1)))
        }
        val tooMany = ResponsesStreamAssembler(Json)
        assertThrows(StreamProtocolException::class.java) {
            tooMany.accept(responseCompleted(*(0..AgentWireLimits.MAX_TOOLS).map {
                responseCall(callId = "call_$it", id = "fc_$it")
            }.toTypedArray()))
        }
    }

    @Test
    fun reasoningCiphertextPhasesAndFunctionStateStayOpaqueUntilSuccessfulCompletion() {
        val assembler = ResponsesStreamAssembler(Json)
        val deltas = reasoningResponseEvents().flatMap { assembler.accept(it) }
        assertEquals(listOf(AgentStreamEvent.TextDelta(REASONING_FIXTURE_TEXT)), deltas)
        val completed = assembler.endOfStream()
        assertEquals("tool_calls", completed.finishReason)
        assertEquals(reasoningResponseOutput(), completed.responsesOutput)
        assertEquals("call_fixture", completed.toolCalls.single().id)
        assertEquals(REASONING_FIXTURE_ARGUMENTS, completed.toolCalls.single().function.arguments)
        assertFalse(deltas.joinToString().contains(REASONING_FIXTURE_CIPHERTEXT))
        assertFalse(deltas.joinToString().contains("Synthetic reasoning summary"))
    }

    @Test
    fun missingMalformedOrChangedEncryptedStateCannotAuthorizeTools() {
        for (encrypted in listOf(null, JsonNull, JsonPrimitive(""), JsonPrimitive(1))) {
            val reasoning = JsonObject(responseReasoning().filterKeys { it != "encrypted_content" } +
                (encrypted?.let { mapOf("encrypted_content" to it) } ?: emptyMap()))
            val assembler = ResponsesStreamAssembler(Json)
            assertThrows(StreamProtocolException::class.java) {
                assembler.accept(responseCompleted(reasoning, responseCall()))
            }
            assertTrue(assembler.outputItems.isEmpty())
            assertThrows(StreamProtocolException::class.java) { assembler.endOfStream() }
        }
        val changed = ResponsesStreamAssembler(Json)
        changed.accept(responseItemDone(0, responseReasoning()))
        assertThrows(StreamProtocolException::class.java) {
            changed.accept(responseCompleted(
                JsonObject(responseReasoning() + ("encrypted_content" to JsonPrimitive("changed"))),
                responseCall()
            ))
        }
        val phase = ResponsesStreamAssembler(Json)
        phase.accept(responseItemDone(0,
            JsonObject(responseMessage("text") + ("phase" to JsonPrimitive("commentary")))))
        assertThrows(StreamProtocolException::class.java) {
            phase.accept(responseCompleted(
                JsonObject(responseMessage("text") + ("phase" to JsonPrimitive("final_answer")))
            ))
        }
    }

    @Test
    fun trailingErrorsErasePendingOpaqueOutputAndNeverReturnItToTheRuntime() {
        val assembler = ResponsesStreamAssembler(Json)
        reasoningResponseEvents().forEach { assembler.accept(it) }
        assertFalse(assembler.outputItems.isEmpty())
        assertThrows(ApiException::class.java) {
            assembler.accept(SseEvent("error", """{"code":"overloaded"}"""))
        }
        assertTrue(assembler.outputItems.isEmpty())
        assertThrows(StreamProtocolException::class.java) { assembler.endOfStream() }
    }
}
