package com.lin.hippyagent.core.agent.repair

import com.lin.hippyagent.core.model.FunctionInfo
import com.lin.hippyagent.core.model.ToolCallInfo
import com.lin.hippyagent.core.pool.FastId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

@Serializable
data class RepairedToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val source: String
)

@Serializable
data class ScavengeRepairReport(
    val scavenged: Int,
    val repairedCalls: List<RepairedToolCall>
)

class ScavengeRepair {

    fun scavenge(
        toolCalls: List<ToolCallInfo>,
        content: String,
        reasoningContent: String?,
        allowedNames: Set<String>
    ): ScavengeRepairReport {
        val combined = buildString {
            append(content)
            reasoningContent?.let { append(it) }
        }

        if (combined.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) {
            Timber.w("ScavengeRepair: input exceeds 100KB")
            return ScavengeRepairReport(0, emptyList())
        }

        val existing = toolCalls
            .map { "${it.function.name}:${it.function.arguments}" }
            .toMutableSet()
        val found = mutableListOf<RepairedToolCall>()
        val contentLen = content.length

        for (match in JSON_START.findAll(combined)) {
            if (found.size >= MAX_SCAVENGED) break

            val jsonStr = extractBalancedJson(combined, match.range.first) ?: continue
            val parsed = parseToolCallJson(jsonStr) ?: continue
            if (parsed.name !in allowedNames) continue

            val sig = "${parsed.name}:${parsed.arguments}"
            if (sig in existing) continue

            existing.add(sig)
            val source = if (match.range.first < contentLen) "content" else "reasoning"
            found.add(RepairedToolCall(FastId.next(), parsed.name, parsed.arguments, source))
            Timber.d("ScavengeRepair: found ${parsed.name} from $source")
        }

        return ScavengeRepairReport(found.size, found)
    }

    private fun extractBalancedJson(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escape = false
        var i = start

        while (i < text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                i++
                continue
            }
            if (inString) {
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
                i++
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun parseToolCallJson(jsonStr: String): ParsedCall? {
        val obj = try {
            LENIENT_JSON.parseToJsonElement(jsonStr).jsonObject
        } catch (_: Exception) {
            return null
        }

        val name1 = obj.getStringField("name")
        if (name1 != null) {
            val args = serializeArguments(obj["arguments"])
            return ParsedCall(name1, args)
        }

        val type = obj.getStringField("type")
        if (type == "function") {
            val fnObj = obj["function"] as? JsonObject ?: return null
            val fnName = fnObj.getStringField("name") ?: return null
            val args = serializeArguments(fnObj["arguments"])
            return ParsedCall(fnName, args)
        }

        return null
    }

    private fun serializeArguments(element: JsonElement?): String = when {
        element == null -> "{}"
        element is JsonPrimitive -> element.content
        else -> element.toString()
    }

    private fun JsonObject.getStringField(key: String): String? = try {
        this[key]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    private data class ParsedCall(val name: String, val arguments: String)

    companion object {
        private const val MAX_INPUT_BYTES = 100 * 1024
        private const val MAX_SCAVENGED = 4
        private val JSON_START = Regex("""\{\s*"(?:name|type)"""")
        private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

fun RepairedToolCall.toToolCallInfo(): ToolCallInfo = ToolCallInfo(
    id = id,
    type = "function",
    function = FunctionInfo(name = name, arguments = arguments)
)
