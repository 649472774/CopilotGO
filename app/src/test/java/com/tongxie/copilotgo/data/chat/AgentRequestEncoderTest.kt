package com.tongxie.copilotgo.data.chat

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.agent.AgentChatMessage
import com.tongxie.copilotgo.data.agent.AgentFunctionCall
import com.tongxie.copilotgo.data.agent.AgentToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AgentRequestEncoderTest {
    @Test
    fun actualWireDtosPreserveToolsCallsResultsAndVisionContent() {
        val call = AgentToolCall("call_fixture", AgentFunctionCall("lookup", """{"query":"fixture"}"""))
        val vision = imageContent("https://example.test/fixture.png")
        val request = agentTestRequest().copy(messages = listOf(
            AgentChatMessage("user", JsonPrimitive("fixture")),
            AgentChatMessage("assistant", toolCalls = listOf(call)),
            AgentChatMessage("tool", JsonPrimitive("""{"result":"ok"}"""), toolCallId = call.id),
            AgentChatMessage("user", vision)
        ))
        val encoded = AgentRequestEncoder.encode(request, Json)
        assertTrue(encoded.needsVision)
        val root = Json.parseToJsonElement(encoded.body).jsonObject
        assertTrue(root.getValue("stream").jsonPrimitive.boolean)
        assertEquals(1, root.getValue("n").jsonPrimitive.int)
        assertEquals("auto", root.getValue("tool_choice").jsonPrimitive.content)
        assertFalse(root.getValue("parallel_tool_calls").jsonPrimitive.boolean)
        val tool = root.getValue("tools").jsonArray.single().jsonObject
        assertEquals("function", tool.getValue("type").jsonPrimitive.content)
        assertTrue(tool.getValue("function").jsonObject.getValue("parameters") is JsonObject)
        val messages = root.getValue("messages").jsonArray
        val sentCall = messages[1].jsonObject.getValue("tool_calls").jsonArray.single().jsonObject
        assertEquals("function", sentCall.getValue("type").jsonPrimitive.content)
        val arguments = sentCall.getValue("function").jsonObject.getValue("arguments").jsonPrimitive
        assertTrue(arguments.isString)
        assertEquals(call.function.arguments, arguments.content)
        assertEquals(call.id, messages[2].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("""{"result":"ok"}""", messages[2].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(vision, messages[3].jsonObject.getValue("content"))
        assertFalse(encoded.body.contains("toolCalls"))
        assertFalse(encoded.body.contains("toolCallId"))
    }

    @Test
    fun runtimeFlagsAndUnsupportedContentHaveClearErrors() {
        val request = agentTestRequest()
        for (invalid in listOf(
            request.copy(stream = false), request.copy(n = 2), request.copy(n = 0),
            request.copy(model = ""), request.copy(toolChoice = "custom"), request.copy(temperature = Double.NaN),
            request.copy(messages = emptyList()),
            request.copy(messages = listOf(AgentChatMessage("function", JsonPrimitive("legacy")))),
            request.copy(messages = listOf(AgentChatMessage("user", JsonObject(emptyMap())))),
            request.copy(messages = listOf(AgentChatMessage("user", JsonPrimitive(42)))),
            request.copy(messages = listOf(AgentChatMessage("tool", JsonPrimitive("result"))))
        )) {
            assertFalse(assertThrows(StreamProtocolException::class.java) {
                AgentRequestEncoder.encode(invalid, Json)
            }.message.isNullOrBlank())
        }
    }

    @Test
    fun toolDefinitionsHaveBoundedUniqueNamesSchemasAndCountsWithoutClaimingSchemaValidation() {
        val request = agentTestRequest()
        val tool = request.tools.single()
        for (invalid in listOf(
            request.copy(tools = List(AgentWireLimits.MAX_TOOL_DEFINITIONS + 1) {
                tool.copy(function = tool.function.copy(name = "tool_$it"))
            }),
            request.copy(tools = listOf(tool, tool)),
            request.copy(tools = listOf(tool.copy(type = "legacy"))),
            request.copy(tools = listOf(tool.copy(function = tool.function.copy(name = "")))),
            request.copy(tools = listOf(tool.copy(function = tool.function.copy(
                parameters = buildJsonObject { put("description", "x".repeat(AgentWireLimits.MAX_SCHEMA_BYTES)) }
            )))),
            request.copy(tools = listOf(tool.copy(function = tool.function.copy(
                parameters = Json.parseToJsonElement("{\"x\":".repeat(33) + "0" + "}".repeat(33)).jsonObject
            ))))
        )) assertThrows(StreamProtocolException::class.java) { AgentRequestEncoder.encode(invalid, Json) }
        val unknownSchema = request.copy(tools = listOf(tool.copy(function = tool.function.copy(
            parameters = buildJsonObject { put("type", "fixture-schema-owned-by-tool") }
        ))))
        assertTrue(AgentRequestEncoder.encode(unknownSchema, Json).body.contains("fixture-schema-owned-by-tool"))
        val fullCatalog = request.copy(tools = List(64) {
            tool.copy(function = tool.function.copy(name = "tool_$it"))
        })
        assertTrue(AgentRequestEncoder.encode(fullCatalog, Json).body.contains("tool_63"))
    }

    @Test
    fun schemaAggregateHistoryArgumentsAndTextAreBounded() {
        val request = agentTestRequest()
        val tool = request.tools.single()
        val largeSchema = buildJsonObject { put("description", "x".repeat(60_000)) }
        assertThrows(StreamProtocolException::class.java) {
            AgentRequestEncoder.encode(request.copy(tools = List(9) {
                tool.copy(function = tool.function.copy(name = "tool_$it", parameters = largeSchema))
            }), Json)
        }
        for (arguments in listOf(
            """{"x":1,"x":2}""", "[]", "{", "{\"x\":\"" + "x".repeat(65_536) + "\"}"
        )) {
            assertThrows(StreamProtocolException::class.java) {
                AgentRequestEncoder.encode(request.copy(messages = listOf(AgentChatMessage(
                    "assistant", toolCalls = listOf(AgentToolCall("id", AgentFunctionCall("lookup", arguments)))
                ))), Json)
            }
        }
        assertThrows(StreamProtocolException::class.java) {
            AgentRequestEncoder.encode(request.copy(messages = listOf(AgentChatMessage(
                "user", JsonPrimitive("x".repeat(Constants.MAX_RESPONSE_CHARACTERS + 1))
            ))), Json)
        }
    }

    @Test
    fun completeEncodedRequestAndJsonNodeGrowthAreBoundedBeforeStringEncoding() {
        val request = agentTestRequest()
        val image = imageContent("data:image/png;base64," + "a".repeat(1_000_000))
        assertThrows(StreamProtocolException::class.java) {
            AgentRequestEncoder.encode(request.copy(messages = List(26) { AgentChatMessage("user", image) }), Json)
        }
        assertThrows(StreamProtocolException::class.java) {
            AgentRequestEncoder.encode(request.copy(messages = listOf(AgentChatMessage(
                "user", JsonArray(List(AgentWireLimits.MAX_JSON_NODES + 1) { JsonNull })
            ))), Json)
        }
    }

    @Test
    fun visionAllowsHttpsOrEmbeddedImagesButNotCleartextOrResponsesInputTypes() {
        for (url in listOf("https://example.test/image.png", "data:image/png;base64,Zml4dHVyZQ==")) {
            assertTrue(AgentRequestEncoder.encode(agentTestRequest().copy(messages = listOf(
                AgentChatMessage("user", imageContent(url))
            )), Json).needsVision)
        }
        for (content in listOf(
            imageContent("http://example.test/image.png"),
            imageContent("file:///fixture.png"),
            JsonArray(listOf(buildJsonObject { put("type", "input_text"); put("text", "responses-only") }))
        )) {
            assertThrows(StreamProtocolException::class.java) {
                AgentRequestEncoder.encode(agentTestRequest().copy(messages = listOf(AgentChatMessage("user", content))), Json)
            }
        }
    }

    private fun imageContent(url: String) = JsonArray(listOf(buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject { put("url", url) })
    }))
}
