package com.lin.hippyagent.core.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object HippyJobJson {

    val json = Json { ignoreUnknownKeys = true }

    fun mapToJson(map: Map<String, Any>): String {
        val jsonObj = JsonObject(map.mapValues { (_, v) -> anyToJsonElement(v) })
        return json.encodeToString(JsonObject.serializer(), jsonObj)
    }

    fun jsonToMap(jsonStr: String): Map<String, Any> {
        if (jsonStr.isBlank() || jsonStr == "{}") return emptyMap()
        return try {
            val element = json.parseToJsonElement(jsonStr).jsonObject
            element.mapValues { (_, v) -> jsonElementToAny(v) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun anyToJsonElement(value: Any): JsonElement = when (value) {
        is String -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            JsonObject((value as Map<String, Any>).mapValues { (_, v) -> anyToJsonElement(v) })
        }
        is List<*> -> JsonArray(value.map { anyToJsonElement(it ?: "") })
        is JsonElement -> value
        else -> JsonPrimitive(value.toString())
    }

    private fun jsonElementToAny(element: JsonElement): Any = when (element) {
        is JsonPrimitive -> {
            if (element.isString) element.content
            else {
                val content = element.content
                content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
            }
        }
        is JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
        is JsonArray -> element.map { jsonElementToAny(it) }
    }
}
