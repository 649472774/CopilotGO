package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.agent.AgentChatMessage
import com.tongxie.copilotgo.data.agent.AgentFunctionCall
import com.tongxie.copilotgo.data.agent.AgentStreamEvent
import com.tongxie.copilotgo.data.agent.AgentToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CopilotProtocolIdentityTest {
    @Test
    fun ordinaryResponseAcceptsThePublic416CharacterEnvelopeIdBeforeAnyToolOrText() {
        val id = copilotOpaqueId(1, response = true)
        assertEquals(416, id.length)
        assertTrue(id.contains('+') && id.contains('/') && id.endsWith("=="))
        val assembler = ResponsesStreamAssembler(Json, allowTools = false)
        assembler.accept(responseEvent("response.created") {
            put("response", buildJsonObject { put("id", id); put("status", "in_progress") })
        })
        val deltas = assembler.accept(responseCompleted(responseMessage("answer"), id = id))
        assertEquals(listOf(AgentStreamEvent.TextDelta("answer")), deltas)
        assertEquals("stop", assembler.endOfStream().finishReason)
    }

    @Test
    fun ordinaryResponseAcceptsThePublic412CharacterOutputItemId() {
        val id = copilotOpaqueId(2)
        assertEquals(412, id.length)
        val assembler = ResponsesStreamAssembler(Json, allowTools = false)
        assembler.accept(responseCompleted(responseMessage("answer", id)))
        assertEquals(id, assembler.endOfStream().responsesOutput.single().requiredString("id"))
    }

    @Test
    fun rotatingCopilotEnvelopeAndItemIdsDoNotSplitOrDuplicateOrdinaryText() {
        val assembler = ResponsesStreamAssembler(Json, allowTools = false)
        val events = copilotTextEvents("Hello! How can I help?")
        val deltas = events.flatMap { assembler.accept(it) }
        assertEquals("Hello! How can I help?", deltas.joinToString("") { it.text })
        val completed = assembler.endOfStream()
        assertEquals("stop", completed.finishReason)
        assertTrue(completed.toolCalls.isEmpty())
        val finalOutput = Json.parseToJsonElement(events.last().data).jsonObject
            .getValue("response").jsonObject.getValue("output").jsonArray
        assertEquals(finalOutput.toList(), completed.responsesOutput)
    }

    @Test
    fun rotatingItemIdsKeepStableCallIdsOriginalArgumentsAndFinalOpaqueContinuation() {
        val events = copilotResponseEvents(reasoningResponseEvents())
        val assembler = ResponsesStreamAssembler(Json)
        val text = events.flatMap { assembler.accept(it) }.joinToString("") { it.text }
        val completed = assembler.endOfStream()
        val call = completed.toolCalls.single()
        assertEquals("call_fixture", call.id)
        assertEquals(REASONING_FIXTURE_ARGUMENTS, call.function.arguments)
        val request = agentTestRequest("gpt-5.6-sol").copy(messages = listOf(
            AgentChatMessage("user", JsonPrimitive("question")),
            AgentChatMessage("assistant", JsonPrimitive(text), completed.toolCalls,
                responsesOutput = completed.responsesOutput),
            AgentChatMessage("tool", JsonPrimitive("actual result"), toolCallId = call.id)
        ))
        val root = Json.parseToJsonElement(
            AgentRequestEncoder.encode(request, Json, ModelTransport.RESPONSES).body
        ).jsonObject
        val input = root.getValue("input").jsonArray
        assertEquals(completed.responsesOutput, input.subList(1, input.lastIndex))
        assertEquals(call.id, input.last().jsonObject.requiredString("call_id"))
        assertFalse(input.dropLast(1).any { (it as? JsonObject)?.string("type") == "function_call_output" })
    }

    @Test
    fun publicModelIdsWithDotsVersionsAndPreviewSuffixesAreNotFunctionNames() {
        val models = listOf(
            "gpt-4.1", "claude-sonnet-4.5", "gemini-3.1-pro-preview",
            "gpt-5.4-mini", "gpt-5.6-sol", "gpt-5.3-codex-spark-preview"
        )
        for (model in models) for (transport in ModelTransport.entries) {
            for (messages in listOf(
                listOf(ChatMessage("user", "first")),
                listOf(ChatMessage("user", "first"), ChatMessage("assistant", "answer"), ChatMessage("user", "follow-up"))
            )) {
                val request = ChatRequest(model, messages)
                val ordinary = Json.parseToJsonElement(ChatRequestEncoder.encode(request, Json, transport).body).jsonObject
                assertEquals(model, ordinary.requiredString("model"))
                assertFalse(ordinary.containsKey("tools"))
                val agent = agentTestRequest(model).copy(messages = messages.map {
                    AgentChatMessage(it.role, JsonPrimitive(it.content))
                })
                assertEquals(model, Json.parseToJsonElement(
                    AgentRequestEncoder.encode(agent, Json, transport).body
                ).jsonObject.requiredString("model"))
            }
        }
    }

    @Test
    fun opaqueCallIdsRetainExactPairingAcrossBothTransportsWithoutNameRestrictions() {
        val id = "call.v1/opaque+pair==:1"
        val call = AgentToolCall(id, AgentFunctionCall("lookup", """{"query":"fixture"}"""))
        val request = agentTestRequest("gpt-5.6-sol").copy(messages = listOf(
            AgentChatMessage("user", JsonPrimitive("question")),
            AgentChatMessage("assistant", toolCalls = listOf(call)),
            AgentChatMessage("tool", JsonPrimitive("result"), toolCallId = id)
        ))
        for (transport in ModelTransport.entries) {
            val body = AgentRequestEncoder.encode(request, Json, transport).body
            assertEquals(2, Regex(Regex.escape(id)).findAll(body).count())
            assertThrows(StreamProtocolException::class.java) {
                AgentRequestEncoder.encode(request.copy(messages = request.messages.dropLast(1) +
                    request.messages.last().copy(toolCallId = id.dropLast(1))), Json, transport)
            }
            assertThrows(StreamProtocolException::class.java) {
                AgentRequestEncoder.encode(request.copy(messages = request.messages + request.messages.last()), Json, transport)
            }
        }
        val assembler = AgentStreamAssembler(Json)
        assembler.accept(agentChunk(listOf(agentFragment(id = id, name = "lookup", arguments = "{}"))))
        assembler.accept(agentChunk(finish = "tool_calls"))
        assertEquals(id, assembler.endOfStream().toolCalls.single().id)
    }

    @Test
    fun toolNamesUseFunctionGrammarEvenThoughOpaqueIdsAllowPunctuation() {
        val request = agentTestRequest()
        val tool = request.tools.single()
        for (name in listOf("lookup.v1", "lookup/remote", "lookup+remote", "lookup=remote", "lookup:remote")) {
            for (transport in ModelTransport.entries) {
                assertThrows(StreamProtocolException::class.java) {
                    AgentRequestEncoder.encode(request.copy(tools = listOf(
                        tool.copy(function = tool.function.copy(name = name))
                    )), Json, transport)
                }
            }
            assertThrows(StreamProtocolException::class.java) {
                AgentStreamAssembler(Json).accept(agentChunk(listOf(
                    agentFragment(id = "call_1", name = name, arguments = "{}")
                )))
            }
            assertThrows(StreamProtocolException::class.java) {
                ResponsesStreamAssembler(Json).accept(responseCompleted(responseCall(name = name)))
            }
        }
    }

    @Test
    fun opaqueResponsesIdsHaveTheirOwnExactBoundAndRejectControlsOrWhitespace() {
        for (size in listOf(128, 129, 416, 4096)) {
            val assembler = ResponsesStreamAssembler(Json)
            assembler.accept(responseCompleted(responseMessage("ok", "m".repeat(size)), id = "r".repeat(size)))
            assertEquals("stop", assembler.endOfStream().finishReason)
        }
        for (id in listOf("", " ", "bad id", "x\n", "x\u0000", "x\u007f", "x\u0085", "x\u202e", "x".repeat(4097))) {
            assertThrows(StreamProtocolException::class.java) {
                ResponsesStreamAssembler(Json).accept(responseCompleted(responseMessage("ok"), id = id))
            }
            assertThrows(StreamProtocolException::class.java) {
                ResponsesStreamAssembler(Json).accept(responseCompleted(responseMessage("ok", id)))
            }
        }
    }

    @Test
    fun aKnownItemAliasCannotMoveToAnotherOutputIndexOrAuthorizeChangedCalls() {
        for (event in listOf(
            responseArguments("{}", index = 2, id = "fc_first"),
            responseItemDone(2, responseCall("{}", "call_second", "fc_first")),
            responseItemDone(1, responseCall("{}", "call_changed", "fc_rotated")),
            responseItemDone(1, responseCall("{}", "call_first", "fc_rotated", name = "changed"))
        )) {
            val assembler = ResponsesStreamAssembler(Json)
            assembler.accept(responseAdded(0, responseReasoning()))
            assembler.accept(responseAdded(1, responseCall("", "call_first", "fc_first", complete = false)))
            assembler.accept(responseAdded(2, responseCall("", "call_second", "fc_second", complete = false)))
            assertThrows(StreamProtocolException::class.java) { assembler.accept(event) }
            assertThrows(StreamProtocolException::class.java) { assembler.endOfStream() }
        }
    }

    @Test
    fun rotatingIdsNeverAuthorizeChangedArgumentsMissingOutputsOrDuplicateCompletion() {
        val original = reasoningResponseOutput()
        for (output in listOf(
            original.dropLast(1),
            original + original.last(),
            original.dropLast(1) + responseCall("""{"query":"substituted"}"""),
            listOf(original[0], responseMessage("substituted"), original[2]),
            listOf(original[0], original[2], original[1])
        )) {
            val events = copilotResponseEvents(
                reasoningResponseEvents().dropLast(1) + responseCompleted(*output.toTypedArray())
            )
            val assembler = ResponsesStreamAssembler(Json)
            assertThrows(StreamProtocolException::class.java) { events.forEach { assembler.accept(it) } }
            assertTrue(assembler.outputItems.isEmpty())
            assertThrows(StreamProtocolException::class.java) { assembler.endOfStream() }
        }
        val assembler = ResponsesStreamAssembler(Json)
        val events = copilotResponseEvents(reasoningResponseEvents() + reasoningResponseEvents().last())
        events.dropLast(1).forEach { assembler.accept(it) }
        assertThrows(StreamProtocolException::class.java) { assembler.accept(events.last()) }
        assertThrows(StreamProtocolException::class.java) { assembler.endOfStream() }
    }

    @Test
    fun rotatingIdsCannotRestartAResponseOrReplayAnExplicitEventSequence() {
        val created = responseEvent("response.created") {
            put("response", buildJsonObject { put("id", "resp_first"); put("status", "in_progress") })
        }
        val assembler = ResponsesStreamAssembler(Json)
        assembler.accept(created)
        assertThrows(StreamProtocolException::class.java) {
            assembler.accept(responseEvent("response.created") {
                put("response", buildJsonObject { put("id", "resp_second"); put("status", "in_progress") })
            })
        }
        for (sequence in listOf(JsonPrimitive(-1), JsonPrimitive(0), JsonPrimitive("1"))) {
            val stream = ResponsesStreamAssembler(Json)
            val events = copilotTextEvents("answer")
            stream.accept(events.first())
            val root = Json.parseToJsonElement(events[1].data).jsonObject
            assertThrows(StreamProtocolException::class.java) {
                stream.accept(events[1].copy(data = JsonObject(root + ("sequence_number" to sequence)).toString()))
            }
            assertThrows(StreamProtocolException::class.java) { stream.endOfStream() }
        }
    }
}
