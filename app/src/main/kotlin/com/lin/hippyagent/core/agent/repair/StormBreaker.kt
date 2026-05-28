package com.lin.hippyagent.core.agent.repair

import com.lin.hippyagent.core.model.ToolCallInfo
import kotlinx.serialization.Serializable
import timber.log.Timber

@Serializable
data class StormBreakResult(
    val suppressedCalls: List<Int>,
    val stormsBroken: Int
)

class StormBreaker(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val threshold: Int = DEFAULT_THRESHOLD
) {

    private val window = ArrayDeque<CallSignature>()

    fun check(toolCalls: List<ToolCallInfo>): StormBreakResult {
        val suppressed = mutableListOf<Int>()
        val stormNames = mutableSetOf<String>()

        for ((index, call) in toolCalls.withIndex()) {
            val sig = CallSignature(call.function.name, call.function.arguments)

            window.addLast(sig)
            if (window.size > windowSize) window.removeFirst()

            val repeatCount = window.count { it.name == sig.name }
            if (repeatCount >= threshold) {
                suppressed.add(index)
                stormNames.add(sig.name)
                Timber.w("StormBreaker: suppressed repetitive call ${sig.name} (count=$repeatCount)")
            }

            if (isMutating(sig.name)) {
                window.removeAll { !isMutating(it.name) }
            }
        }

        return StormBreakResult(suppressed, stormNames.size)
    }

    fun resetStorm() {
        window.clear()
    }

    private data class CallSignature(val name: String, val arguments: String)

    companion object {
        const val DEFAULT_WINDOW_SIZE = 6
        const val DEFAULT_THRESHOLD = 3

        private val MUTATING_PREFIXES = setOf(
            "write", "edit", "delete", "create", "execute", "send", "set",
            "append", "install", "make_call", "sms_send", "remove", "move",
            "rename", "mkdir", "touch", "chmod", "spawn", "update"
        )

        fun isMutating(toolName: String): Boolean {
            val lower = toolName.lowercase()
            return MUTATING_PREFIXES.any { lower.startsWith(it) }
        }
    }
}
