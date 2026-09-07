package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.agent.AgentStreamEvent
import com.tongxie.copilotgo.data.net.ApiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AgentStreamAssemblerTest {
    @Test
    fun interleavedArgumentsArePrivateUntilMatchingFinishAndStreamEnd() {
        val assembler = AgentStreamAssembler(Json)
        assertEquals(listOf(AgentStreamEvent.TextDelta("checking")), assembler.accept(agentChunk(content = "checking")))
        assertTrue(assembler.accept(agentChunk(listOf(
            agentFragment(1, "call_b", "lookup", """{"query":"b"""),
            agentFragment(0, "call_a", "lookup", """{"query":""")
        ))).isEmpty())
        assertTrue(assembler.accept(agentChunk(listOf(
            agentFragment(0, arguments = "\"a\"}"),
            agentFragment(1, arguments = "\\n\"}")
        ))).isEmpty())
        assertTrue(assembler.accept(agentChunk(finish = "tool_calls")).isEmpty())
        assertTrue(assembler.accept(SseEvent("message", "[DONE]")).isEmpty())
        val completed = assembler.endOfStream()
        assertEquals("tool_calls", completed.finishReason)
        assertEquals(0, completed.choiceIndex)
        assertEquals(listOf("call_a", "call_b"), completed.toolCalls.map { it.id })
        assertEquals(listOf("a", "b\n"), completed.toolCalls.map {
            AgentJsonGuard.objectValue(it.function.arguments, 1024).getValue("query").jsonPrimitive.content
        })
    }

    @Test
    fun fragmentedMetadataBeforeArgumentsAndStableRepeatedMetadataAreSupported() {
        val assembler = AgentStreamAssembler(Json)
        assembler.accept(agentChunk(listOf(agentFragment(id = "call_", name = "look", arguments = ""))))
        assembler.accept(agentChunk(listOf(agentFragment(id = "one", name = "up", arguments = """{"query":""", type = null))))
        assembler.accept(agentChunk(listOf(agentFragment(id = "call_one", name = "lookup", arguments = "\"x\"}"))))
        assembler.accept(agentChunk(finish = "tool_calls"))
        assertEquals("call_one", assembler.endOfStream().toolCalls.single().id)
    }

    @Test
    fun separateCallsMayUseTheSameFunctionButNotTheSameId() {
        val assembler = AgentStreamAssembler(Json)
        assembler.accept(agentChunk(listOf(
            agentFragment(0, "a", "lookup", "{}"),
            agentFragment(1, "b", "lookup", "{}")
        )))
        assembler.accept(agentChunk(finish = "tool_calls"))
        assertEquals(2, assembler.endOfStream().toolCalls.size)
        protocolFailure(
            agentChunk(listOf(agentFragment(0, "a", "lookup", "{}"), agentFragment(1, "a", "lookup", "{}"))),
            agentChunk(finish = "tool_calls")
        )
    }

    @Test
    fun duplicateIndexesAndDuplicateTerminalCallsAreRejected() {
        protocolFailure(agentChunk(listOf(
            agentFragment(0, "a", "lookup", "{}"), agentFragment(0, "b", "lookup", "{}")
        )))
        protocolFailure(agentCompleteCall(), agentChunk(finish = "tool_calls"), agentChunk(finish = "tool_calls"))
        protocolFailure(agentCompleteCall(), agentChunk(finish = "tool_calls"), agentCompleteCall())
        val assembler = AgentStreamAssembler(Json)
        assembler.accept(agentCompleteCall())
        assembler.accept(agentChunk(finish = "tool_calls"))
        assembler.endOfStream()
        assertThrows(StreamProtocolException::class.java) { assembler.endOfStream() }
    }

    @Test
    fun metadataCannotChangeAfterArgumentsBegin() {
        for (conflict in listOf(
            agentFragment(id = "different"),
            agentFragment(name = "different"),
            agentFragment(type = "not-function")
        )) {
            protocolFailure(
                agentChunk(listOf(agentFragment(id = "id", name = "lookup", arguments = "{"))),
                agentChunk(listOf(conflict))
            )
        }
    }

    @Test
    fun repeatedTypedHeadersBeforeArgumentsCannotReplaceOrConcatenateIdentities() {
        for (conflict in listOf(
            agentFragment(id = "different", name = "lookup", arguments = "{}"),
            agentFragment(id = "id", name = "different", arguments = "{}")
        )) protocolFailure(
            agentChunk(listOf(agentFragment(id = "id", name = "lookup", arguments = ""))),
            agentChunk(listOf(conflict)), agentChunk(finish = "tool_calls")
        )
        val assembler = AgentStreamAssembler(Json)
        assembler.accept(agentChunk(listOf(agentFragment(id = "id", name = "lookup", arguments = ""))))
        assembler.accept(agentChunk(listOf(agentFragment(id = "id", name = "lookup", arguments = "{}"))))
        assembler.accept(agentChunk(finish = "tool_calls"))
        assertEquals("id", assembler.endOfStream().toolCalls.single().id)
    }

    @Test
    fun prematureEofCannotBeRetriedIntoACompletedProposal() {
        val assembler = AgentStreamAssembler(Json)
        assembler.accept(agentCompleteCall())
        assertThrows(StreamProtocolException::class.java) { assembler.endOfStream() }
        assertThrows(StreamProtocolException::class.java) { assembler.accept(agentChunk(finish = "tool_calls")) }
    }

    @Test
    fun completeNonemptyIdsNamesAndTypesAreRequired() {
        for (call in listOf(
            agentFragment(name = "lookup", arguments = "{}"),
            agentFragment(id = "id", arguments = "{}"),
            agentFragment(id = "id", name = "lookup", arguments = "{}", type = null),
            agentFragment(id = " ", name = "lookup", arguments = "{}"),
            agentFragment(id = "id", name = "\n", arguments = "{}"),
            agentFragment(id = "id", name = "lookup", arguments = "{}", type = ""),
            agentFragment(id = "x".repeat(129), name = "lookup", arguments = "{}"),
            agentFragment(id = "id", name = "x".repeat(65), arguments = "{}")
        )) protocolFailure(agentChunk(listOf(call)), agentChunk(finish = "tool_calls"))
    }

    @Test
    fun argumentsMustBeOneStrictJsonObjectWithoutDuplicateKeys() {
        for (arguments in listOf(
            "", "{", "[]", "null", "true", "\"text\"", "{}{}", """{"x":1,}""",
            """{"x":NaN}""", """{"x":Infinity}""", """{"x":01}""", """{"x":1e}""",
            """{"x":unquoted}""", """{x:1}""", """{"x":"\q"}""",
            """{"x":1,"x":2}""", """{"x":1,"\u0078":2}""",
            """{"nested":[{"x":1,"x":2}]}"""
        )) protocolFailure(agentCompleteCall(arguments), agentChunk(finish = "tool_calls"))
    }

    @Test
    fun duplicateWireObjectKeysAreNotSilentlyOverwritten() {
        protocolFailure(SseEvent("message",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"a","id":"b","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        ))
        protocolFailure(SseEvent("message",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"a","type":"function","function":{"name":"lookup","name":"other","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        ))
    }

    @Test
    fun boundedNestedAndUtf8ArgumentsAcceptExactLimitAndRejectOverflow() {
        val exact = "{\"x\":\"" + "x".repeat(AgentWireLimits.MAX_ARGUMENT_BYTES - 8) + "\"}"
        assertEquals(AgentWireLimits.MAX_ARGUMENT_BYTES, exact.length)
        val assembler = AgentStreamAssembler(Json)
        assembler.accept(agentCompleteCall(exact))
        assembler.accept(agentChunk(finish = "tool_calls"))
        assertEquals(exact, assembler.endOfStream().toolCalls.single().function.arguments)
        protocolFailure(agentCompleteCall(exact.replace("\"}", "x\"}")), agentChunk(finish = "tool_calls"))
        protocolFailure(agentCompleteCall("{\"x\":\"" + "中".repeat(22_000) + "\"}"), agentChunk(finish = "tool_calls"))
        protocolFailure(
            agentCompleteCall("{\"x\":".repeat(33) + "0" + "}".repeat(33)),
            agentChunk(finish = "tool_calls")
        )
    }

    @Test
    fun totalArgumentsToolCountAndIndexesAreBounded() {
        val assembler = AgentStreamAssembler(Json)
        val large = "{\"x\":\"" + "x".repeat(AgentWireLimits.MAX_ARGUMENT_BYTES - 8) + "\"}"
        assertThrows(StreamProtocolException::class.java) {
            repeat(9) { index ->
                assembler.accept(agentChunk(listOf(agentFragment(index, "id_$index", "lookup", large))))
            }
        }
        protocolFailure(agentChunk(listOf(agentFragment(24, "id", "lookup", "{}"))))
        protocolFailure(agentChunk(listOf(agentFragment(-1, "id", "lookup", "{}"))))
        protocolFailure(
            agentChunk(listOf(agentFragment(1, "id", "lookup", "{}"))),
            agentChunk(finish = "tool_calls")
        )
        protocolFailure(agentChunk((0..24).map { agentFragment(it, "id_$it", "lookup", "{}") }))
        val allowed = AgentStreamAssembler(Json)
        allowed.accept(agentChunk((0 until 24).map { agentFragment(it, "id_$it", "lookup", "{}") }))
        allowed.accept(agentChunk(finish = "tool_calls"))
        assertEquals(24, allowed.endOfStream().toolCalls.size)
    }

    @Test
    fun contentAndRawEventGrowthAreBounded() {
        val assembler = AgentStreamAssembler(Json)
        val text = agentChunk(content = "x".repeat(1000))
        repeat(Constants.MAX_RESPONSE_CHARACTERS / 1000) { assembler.accept(text) }
        assertThrows(StreamProtocolException::class.java) { assembler.accept(agentChunk(content = "x")) }
        protocolFailure(SseEvent("message", "x".repeat(AgentWireLimits.MAX_EVENT_BYTES + 1)))
        val traffic = AgentStreamAssembler(Json)
        assertThrows(StreamProtocolException::class.java) {
            repeat(22) { traffic.accept(SseEvent("heartbeat", "x".repeat(800_000))) }
        }
        val events = AgentStreamAssembler(Json)
        repeat(AgentWireLimits.MAX_EVENTS) { events.accept(SseEvent("heartbeat", "")) }
        assertThrows(StreamProtocolException::class.java) { events.accept(SseEvent("heartbeat", "")) }
    }

    @Test
    fun unknownAndMismatchedFinishReasonsCannotComplete() {
        for (finish in listOf("stop", "length", "function_call", "unknown", "error", "")) {
            protocolFailure(agentCompleteCall(), agentChunk(finish = finish))
        }
        protocolFailure(agentChunk(finish = "tool_calls"))
        protocolFailure(agentChunk(finish = "stop"))
        protocolFailure(agentChunk(content = " \n", finish = "length"))
        protocolFailure(agentChunk(content = "partial"), SseEvent("message", "[DONE]"))
        protocolFailure(agentCompleteCall())
        protocolFailure(agentChunk(content = "partial"))
    }

    @Test
    fun contentOnlyStopAndLengthAllowTrailingUsageOrEof() {
        for (finish in listOf("stop", "length")) {
            val assembler = AgentStreamAssembler(Json)
            assertEquals(listOf(AgentStreamEvent.TextDelta("reply")), assembler.accept(agentChunk(content = "reply", finish = finish)))
            assembler.accept(SseEvent("message", """{"choices":[],"usage":{"total_tokens":2}}"""))
            val completed = assembler.endOfStream()
            assertEquals(finish, completed.finishReason)
            assertTrue(completed.toolCalls.isEmpty())
        }
    }

    @Test
    fun unrequestedChoiceCannotBeConfusedWithTheExecutableChoice() {
        protocolFailure(agentChunk(content = "wrong", finish = "stop", choice = 1))
        protocolFailure(agentChunk(listOf(agentFragment(id = "a", name = "lookup", arguments = "{}")), choice = -1))
        protocolFailure(SseEvent("message", """{"choices":[{"index":0,"delta":{"content":"ok"}},{"index":1,"delta":{"content":"wrong"}}]}"""))
        protocolFailure(SseEvent("message", """{"choices":[{"index":"0","delta":{"content":"wrong"},"finish_reason":"stop"}]}"""))
    }

    @Test
    fun malformedEventTypesAndLegacyFunctionCallsAreRejected() {
        for (event in listOf(
            SseEvent("responses.output_text.delta", "{}"),
            SseEvent("done", "[DONE]"),
            SseEvent("message", "not-json"),
            SseEvent("message", """{"object":"response","choices":[]}"""),
            SseEvent("message", """{"choices":[{"delta":{"function_call":{"name":"legacy"}}}]}"""),
            SseEvent("message", """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":{}}}]}}]}"""),
            SseEvent("message", """{"choices":[{"delta":{"content":42}}]}""")
        )) protocolFailure(event)
    }

    @Test
    fun namedJsonAndContentFilterErrorsRemainErrorsEvenAfterToolFinish() {
        for (error in listOf(
            SseEvent("error", """{"code":"overloaded"}"""),
            SseEvent("message", """{"error":{"code":"overloaded"}}"""),
            SseEvent("message", """{"type":"error","code":"overloaded"}""")
        )) {
            val assembler = AgentStreamAssembler(Json)
            assembler.accept(agentCompleteCall())
            assembler.accept(agentChunk(finish = "tool_calls"))
            val exception = assertThrows(ApiException::class.java) { assembler.accept(error) }
            assertEquals("overloaded", exception.errorCode)
            assertThrows(StreamProtocolException::class.java) { assembler.endOfStream() }
        }
        val filtered = AgentStreamAssembler(Json)
        assertEquals("content_filter", assertThrows(ApiException::class.java) {
            filtered.accept(agentChunk(finish = "content_filter"))
        }.errorCode)
    }

    private fun protocolFailure(vararg events: SseEvent) {
        val assembler = AgentStreamAssembler(Json)
        assertThrows(StreamProtocolException::class.java) {
            events.forEach { assembler.accept(it) }
            assembler.endOfStream()
        }
    }
}
