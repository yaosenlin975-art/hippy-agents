package com.lin.hippyagent.core.agent.repair

import com.lin.hippyagent.core.model.FunctionInfo
import com.lin.hippyagent.core.model.ToolCallInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

@Serializable
data class TruncationRepairReport(
    val truncationsFixed: Int
)

class TruncationRepair {

    fun repair(toolCalls: List<ToolCallInfo>): Pair<List<ToolCallInfo>, TruncationRepairReport> {
        var fixedCount = 0
        val repaired = toolCalls.map { call ->
            val original = call.function.arguments
            val fixed = fixTruncatedJson(original)
            if (fixed != original) {
                fixedCount++
                Timber.d("TruncationRepair: fixed arguments for ${call.function.name}")
                call.copy(function = call.function.copy(arguments = fixed))
            } else {
                call
            }
        }
        return repaired to TruncationRepairReport(fixedCount)
    }

    private fun fixTruncatedJson(json: String): String {
        try {
            JSON.parseToJsonElement(json)
            return json
        } catch (_: Exception) {}

        val fixed = tryFix(json)
        return try {
            JSON.parseToJsonElement(fixed)
            fixed
        } catch (_: Exception) {
            Timber.w("TruncationRepair: failed to fix, falling back to {}")
            "{}"
        }
    }

    private fun tryFix(json: String): String {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escape = false
        var isValueString = false
        var afterColon = false
        var lastValueEnd = 0

        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (escape) {
                escape = false
                i++
                continue
            }
            if (inString) {
                when (c) {
                    '\\' -> escape = true
                    '"' -> {
                        inString = false
                        if (isValueString) lastValueEnd = i + 1
                        afterColon = false
                    }
                }
                i++
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    isValueString = afterColon
                }
                '{' -> stack.addLast('{')
                '[' -> stack.addLast('[')
                '}' -> {
                    if (stack.isNotEmpty() && stack.last() == '{') stack.removeLast()
                    lastValueEnd = i + 1
                    afterColon = false
                }
                ']' -> {
                    if (stack.isNotEmpty() && stack.last() == '[') stack.removeLast()
                    lastValueEnd = i + 1
                    afterColon = false
                }
                ':' -> afterColon = true
                ',' -> afterColon = false
                ' ', '\n', '\r', '\t' -> {}
                else -> {
                    if (afterColon) {
                        when {
                            json.startsWith("true", i) -> {
                                i += 4; lastValueEnd = i; afterColon = false; continue
                            }
                            json.startsWith("false", i) -> {
                                i += 5; lastValueEnd = i; afterColon = false; continue
                            }
                            json.startsWith("null", i) -> {
                                i += 4; lastValueEnd = i; afterColon = false; continue
                            }
                            c.isDigit() || c == '-' -> {
                                var j = i + 1
                                while (j < json.length && json[j] in "0123456789.eE+-") j++
                                i = j; lastValueEnd = i; afterColon = false; continue
                            }
                        }
                    }
                }
            }
            i++
        }

        var fixed: String
        if (inString && isValueString) {
            fixed = json + "\""
        } else if (afterColon) {
            fixed = json.trimEnd() + "null"
        } else if (lastValueEnd > 0) {
            fixed = json.substring(0, lastValueEnd)
        } else {
            return "{}"
        }

        fixed = fixed.trimEnd()
        while (fixed.endsWith(',')) {
            fixed = fixed.dropLast(1).trimEnd()
        }

        var braceDepth = 0
        var bracketDepth = 0
        var inS = false
        var esc = false
        for (j in fixed.indices) {
            val ch = fixed[j]
            if (esc) { esc = false; continue }
            if (inS) {
                if (ch == '\\') esc = true
                else if (ch == '"') inS = false
                continue
            }
            when (ch) {
                '"' -> inS = true
                '{' -> braceDepth++
                '[' -> bracketDepth++
                '}' -> braceDepth--
                ']' -> bracketDepth--
            }
        }
        repeat(bracketDepth) { fixed += "]" }
        repeat(braceDepth) { fixed += "}" }

        return fixed
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
