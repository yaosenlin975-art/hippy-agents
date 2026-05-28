package com.lin.hippyagent.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Safe extension functions to handle JsonNull in API responses.
 * These prevent crashes when API returns null values for fields like tool_calls, choices, etc.
 */

fun JsonElement?.safeJsonArray(): JsonArray? =
    this?.takeIf { it is JsonArray && it !is JsonNull } as? JsonArray

fun JsonElement?.safeJsonObject(): JsonObject? =
    this?.takeIf { it is JsonObject && it !is JsonNull } as? JsonObject

fun JsonElement?.safeJsonPrimitive(): JsonPrimitive? =
    this?.takeIf { it is JsonPrimitive && it !is JsonNull } as? JsonPrimitive

fun JsonElement?.safeJsonPrimitiveContent(): String? =
    safeJsonPrimitive()?.content

