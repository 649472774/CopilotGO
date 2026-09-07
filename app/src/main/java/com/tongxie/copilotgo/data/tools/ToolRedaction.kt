package com.tongxie.copilotgo.data.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object ToolRedaction {
    private val sensitiveNames = setOf(
        "password", "passwd", "secret", "clientsecret", "token", "accesstoken",
        "refreshtoken", "apikey", "authorization", "cookie", "credential", "credentials"
    )

    fun arguments(value: JsonObject, redactText: (String) -> String, schema: JsonObject? = null): JsonObject {
        val hiddenNames = sensitiveNames.toMutableSet()
        fun findHidden(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    (element["properties"] as? JsonObject)?.forEach { (key, child) ->
                        if ((child as? JsonObject)?.get("writeOnly") == JsonPrimitive(true)) {
                            hiddenNames += key.lowercase().filter { it.isLetterOrDigit() }
                        }
                    }
                    element.filterKeys { it !in setOf("default", "examples", "enum", "const") }
                        .values.forEach(::findHidden)
                }
                is JsonArray -> element.forEach(::findHidden)
                else -> Unit
            }
        }
        schema?.let(::findHidden)
        return redactObject(value, redactText, hiddenNames)
    }

    private fun redactObject(value: JsonObject, redactText: (String) -> String, hiddenNames: Set<String>): JsonObject =
        JsonObject(value.mapValues { (key, child) ->
            if (key.lowercase().filter { it.isLetterOrDigit() } in hiddenNames) JsonPrimitive("[redacted]")
            else redact(child, redactText, hiddenNames)
        })

    private fun redact(value: JsonElement, redactText: (String) -> String, hiddenNames: Set<String>): JsonElement = when (value) {
        is JsonObject -> redactObject(value, redactText, hiddenNames)
        is JsonArray -> JsonArray(value.map { redact(it, redactText, hiddenNames) })
        is JsonPrimitive -> if (value.isString) JsonPrimitive(redactText(value.content)) else value
    }
}
