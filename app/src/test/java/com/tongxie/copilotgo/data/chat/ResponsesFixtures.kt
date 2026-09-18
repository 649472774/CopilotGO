package com.tongxie.copilotgo.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse

internal const val RESPONSES_MODEL = "server-frontier-fixture"

internal fun responsesModels(endpoints: List<String> = listOf("/responses")) = MockResponse().setBody(
    buildJsonObject {
        put("data", JsonArray(listOf(buildJsonObject {
            put("id", RESPONSES_MODEL)
            put("model_picker_enabled", true)
            put("supported_endpoints", JsonArray(endpoints.map(::JsonPrimitive)))
            put("capabilities", buildJsonObject {
                put("type", "chat")
                put("supports", buildJsonObject {
                    put("streaming", true)
                    put("tool_calls", true)
                    put("vision", true)
                })
            })
        })))
    }.toString()
)

internal fun responseEvent(type: String, block: JsonObjectBuilder.() -> Unit = {}): SseEvent =
    SseEvent(type, buildJsonObject { put("type", type); block() }.toString())

internal fun responseMessage(text: String = "", id: String = "msg_fixture", complete: Boolean = true) =
    buildJsonObject {
        put("type", "message")
        put("id", id)
        put("role", "assistant")
        put("status", if (complete) "completed" else "in_progress")
        put("content", JsonArray(if (!complete && text.isEmpty()) emptyList() else listOf(buildJsonObject {
            put("type", "output_text")
            put("text", text)
            put("annotations", JsonArray(emptyList()))
        })))
    }

internal fun responseCall(
    arguments: String = "{}",
    callId: String = "call_fixture",
    id: String = "fc_fixture",
    name: String = "lookup",
    complete: Boolean = true
) = buildJsonObject {
    put("type", "function_call")
    put("id", id)
    put("call_id", callId)
    put("name", name)
    put("arguments", arguments)
    put("status", if (complete) "completed" else "in_progress")
}

internal fun responseReasoning(id: String = "rs_fixture") = buildJsonObject {
    put("type", "reasoning")
    put("id", id)
    put("summary", JsonArray(emptyList()))
    put("encrypted_content", "synthetic-opaque-reasoning")
}

internal fun responseAdded(index: Int, item: JsonObject) = responseEvent("response.output_item.added") {
    put("output_index", index)
    put("item", item)
}

internal fun responseItemDone(index: Int, item: JsonObject) = responseEvent("response.output_item.done") {
    put("output_index", index)
    put("item", item)
}

internal fun responseText(text: String, index: Int = 0, id: String = "msg_fixture") =
    responseEvent("response.output_text.delta") {
        put("output_index", index)
        put("content_index", 0)
        put("item_id", id)
        put("delta", text)
    }

internal fun responseArguments(text: String, index: Int = 0, id: String = "fc_fixture") =
    responseEvent("response.function_call_arguments.delta") {
        put("output_index", index)
        put("item_id", id)
        put("delta", text)
    }

internal fun responseCompleted(
    vararg output: JsonObject,
    status: String = "completed",
    id: String = "resp_fixture"
) = responseEvent("response.completed") {
    put("response", buildJsonObject {
        put("id", id)
        put("status", status)
        put("model", RESPONSES_MODEL)
        put("output", JsonArray(output.toList()))
    })
}

internal const val REASONING_FIXTURE_CIPHERTEXT = "synthetic-encrypted-reasoning-not-user-text"
internal const val REASONING_FIXTURE_TEXT = "正在查询。"
internal const val REASONING_FIXTURE_ARGUMENTS = """{ "query": "杭州 天气", "date": "2026-09-18" }"""

internal fun reasoningResponseOutput(): List<JsonObject> = listOf(
    buildJsonObject {
        put("type", "reasoning")
        put("id", "rs_fixture")
        put("summary", JsonArray(listOf(buildJsonObject {
            put("type", "summary_text")
            put("text", "Synthetic reasoning summary, not an answer.")
        })))
        put("encrypted_content", REASONING_FIXTURE_CIPHERTEXT)
    },
    JsonObject(responseMessage(REASONING_FIXTURE_TEXT) + ("phase" to JsonPrimitive("commentary"))),
    responseCall(REASONING_FIXTURE_ARGUMENTS)
)

/** Synthetic values following the official Responses reasoning/message/function SSE contract. */
internal fun reasoningResponseEvents(): List<SseEvent> {
    val output = reasoningResponseOutput()
    return listOf(
        responseEvent("response.created") {
            put("response", buildJsonObject {
                put("id", "resp_fixture"); put("object", "response"); put("status", "in_progress")
                put("output", JsonArray(emptyList())); put("error", JsonNull)
            })
        },
        responseAdded(0, buildJsonObject {
            put("type", "reasoning"); put("id", "rs_fixture")
            put("summary", JsonArray(emptyList())); put("encrypted_content", JsonNull)
        }),
        responseEvent("response.reasoning_summary_part.added") {
            put("output_index", 0); put("item_id", "rs_fixture"); put("summary_index", 0)
            put("part", buildJsonObject { put("type", "summary_text"); put("text", "") })
        },
        responseEvent("response.reasoning_summary_text.delta") {
            put("output_index", 0); put("item_id", "rs_fixture"); put("summary_index", 0)
            put("delta", "Synthetic reasoning summary, not an answer.")
        },
        responseEvent("response.reasoning_summary_text.done") {
            put("output_index", 0); put("item_id", "rs_fixture"); put("summary_index", 0)
            put("text", "Synthetic reasoning summary, not an answer.")
        },
        responseEvent("response.reasoning_summary_part.done") {
            put("output_index", 0); put("item_id", "rs_fixture"); put("summary_index", 0)
            put("part", buildJsonObject {
                put("type", "summary_text"); put("text", "Synthetic reasoning summary, not an answer.")
            })
        },
        responseItemDone(0, output[0]),
        responseAdded(1, JsonObject(responseMessage(complete = false) + ("phase" to JsonPrimitive("commentary")))),
        responseEvent("response.content_part.added") {
            put("output_index", 1); put("item_id", "msg_fixture"); put("content_index", 0)
            put("part", buildJsonObject {
                put("type", "output_text"); put("text", ""); put("annotations", JsonArray(emptyList()))
            })
        },
        responseText(REASONING_FIXTURE_TEXT, 1),
        responseEvent("response.output_text.done") {
            put("output_index", 1); put("item_id", "msg_fixture"); put("content_index", 0)
            put("text", REASONING_FIXTURE_TEXT)
        },
        responseItemDone(1, output[1]),
        responseAdded(2, responseCall(arguments = "", complete = false)),
        responseArguments(REASONING_FIXTURE_ARGUMENTS.take(18), 2),
        responseArguments(REASONING_FIXTURE_ARGUMENTS.drop(18), 2),
        responseEvent("response.function_call_arguments.done") {
            put("output_index", 2); put("item_id", "fc_fixture"); put("arguments", REASONING_FIXTURE_ARGUMENTS)
        },
        responseItemDone(2, output[2]),
        responseCompleted(*output.toTypedArray())
    ).mapIndexed { index, event ->
        event.copy(data = JsonObject(Json.parseToJsonElement(event.data).jsonObject +
            ("sequence_number" to JsonPrimitive(index))).toString())
    }
}
