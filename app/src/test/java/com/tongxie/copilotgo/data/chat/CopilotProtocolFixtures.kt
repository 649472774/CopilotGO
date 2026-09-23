package com.tongxie.copilotgo.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64

// Synthetic envelopes matching the 416/412-character Base64 IDs and per-event rotation in
// https://github.com/OpeOginni/github-copilot-openai-compatible/issues/1. No captured IDs are stored.
internal fun copilotOpaqueId(nonce: Int, response: Boolean = false): String =
    syntheticOpaqueValue(nonce, if (response) 310 else 308)

internal fun copilotOpaqueCiphertext(nonce: Int): String = syntheticOpaqueValue(nonce, 768)

private fun syntheticOpaqueValue(nonce: Int, size: Int): String {
    val bytes = ByteArray(size) { (it * 37).toByte() }
    repeat(4) { bytes[it] = (nonce ushr (it * 8)).toByte() }
    return Base64.getEncoder().encodeToString(bytes)
}

internal fun copilotResponseEvents(
    events: List<SseEvent>,
    model: String = "gpt-5.6-sol",
    seed: Int = 0,
    rotateReasoningCiphertext: Boolean = true
): List<SseEvent> {
    var nonce = seed
    fun item(value: JsonObject): JsonObject {
        val fields = value.toMutableMap()
        fields["id"] = JsonPrimitive(copilotOpaqueId(nonce++))
        if (rotateReasoningCiphertext && value.string("type") == "reasoning" &&
            (value["encrypted_content"] as? JsonPrimitive)?.isString == true
        ) {
            fields["encrypted_content"] = JsonPrimitive(copilotOpaqueCiphertext(nonce++))
        }
        return JsonObject(fields)
    }
    return events.mapIndexed { index, event ->
        val root = Json.parseToJsonElement(event.data).jsonObject.toMutableMap()
        (root["item"] as? JsonObject)?.let { root["item"] = item(it) }
        if ("item_id" in root) root["item_id"] = JsonPrimitive(copilotOpaqueId(nonce++))
        (root["response"] as? JsonObject)?.let { value ->
            val response = value.toMutableMap()
            response["id"] = JsonPrimitive(copilotOpaqueId(nonce++, response = true))
            response["model"] = JsonPrimitive(model)
            value["output"]?.let { output ->
                response["output"] = JsonArray(output.jsonArray.map { item(it.jsonObject) })
            }
            root["response"] = JsonObject(response)
        }
        root["sequence_number"] = JsonPrimitive(index)
        event.copy(data = JsonObject(root).toString())
    }
}

internal fun copilotTextEvents(
    text: String,
    model: String = "gpt-5.6-sol",
    seed: Int = 0,
    includeEncryptedReasoning: Boolean = false
): List<SseEvent> {
    val reasoning = if (includeEncryptedReasoning) responseReasoning()
        else JsonObject(responseReasoning().filterKeys { it != "encrypted_content" })
    return copilotResponseEvents(listOf(
        responseEvent("response.created") {
            put("response", buildJsonObject {
                put("id", "replaced"); put("status", "in_progress"); put("output", JsonArray(emptyList()))
            })
        },
        responseEvent("response.in_progress") {
            put("response", buildJsonObject { put("id", "replaced"); put("status", "in_progress") })
        },
        responseAdded(0, reasoning),
        responseItemDone(0, reasoning),
        responseAdded(1, responseMessage(complete = false)),
        responseText(text.take(1), index = 1),
        responseText(text.drop(1), index = 1),
        responseEvent("response.output_text.done") {
            put("output_index", 1); put("content_index", 0); put("item_id", "replaced"); put("text", text)
        },
        responseItemDone(1, responseMessage(text)),
        responseCompleted(reasoning, responseMessage(text))
    ), model, seed)
}
