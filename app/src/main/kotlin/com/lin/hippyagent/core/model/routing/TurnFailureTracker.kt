package com.lin.hippyagent.core.model.routing

import timber.log.Timber

class TurnFailureTracker(
    private val threshold: Int = DEFAULT_THRESHOLD
) {
    private var failureCount = 0
    private var escalated = false

    val isEscalated: Boolean get() = escalated

    fun noteFailure(signal: FailureSignal): Boolean {
        failureCount++
        Timber.d("TurnFailureTracker: signal=$signal, count=$failureCount/$threshold")
        if (failureCount >= threshold && !escalated) {
            escalated = true
            Timber.w("TurnFailureTracker: escalation triggered after $failureCount failures")
            return true
        }
        return false
    }

    fun reset() {
        failureCount = 0
        escalated = false
    }

    enum class FailureSignal {
        SEARCH_MISMATCH,
        SCAVENGED,
        TRUNCATED,
        REPEAT_LOOP
    }

    companion object {
        const val DEFAULT_THRESHOLD = 3
    }
}

object NeedsProDetector {
    private const val MARKER = "<<<NEEDS_PRO>>>"
    private const val MARKER_WITH_REASON = "<<<NEEDS_PRO:"

    fun detect(text: String): NeedsProResult {
        val markerIndex = text.indexOf(MARKER)
        if (markerIndex < 0) return NeedsProResult.NONE

        val reasonStart = text.indexOf(MARKER_WITH_REASON)
        if (reasonStart >= 0) {
            val reasonEnd = text.indexOf(">>>", reasonStart + MARKER_WITH_REASON.length)
            if (reasonEnd >= 0) {
                val reason = text.substring(reasonStart + MARKER_WITH_REASON.length, reasonEnd).trim()
                return NeedsProResult.REQUESTED(reason.ifBlank { null })
            }
        }
        return NeedsProResult.REQUESTED(null)
    }

    fun stripMarker(text: String): String {
        var result = text
        result = result.replace(Regex("<<<NEEDS_PRO(?::\\s*[^>]*?)?>>>"), "")
        return result.trim()
    }
}

/**
 * 智能体显式声明切换 (XML 风格标记) — 模式与模型的 mid-turn 切换协议。
 *
 * 支持的标记:
 * - `<switch_to_mode>work</switch_to_mode>` / `<switch_to_mode>chat</switch_to_mode>` (大小写不敏感)
 * - `<switch_to_model>complex</switch_to_model>` / `<switch_to_model>main</switch_to_model>`
 *
 * 解析后从原始文本剥离避免泄露给用户。
 */
object SwitchDeclarationDetector {
    private val SWITCH_TO_MODE = Regex(
        "<switch_to_mode>\\s*(work|chat|auto|none)\\s*</switch_to_mode>",
        RegexOption.IGNORE_CASE
    )
    private val SWITCH_TO_MODEL = Regex(
        "<switch_to_model>\\s*(complex|main|light)\\s*</switch_to_model>",
        RegexOption.IGNORE_CASE
    )

    fun detectModeSwitch(text: String): String? {
        val match = SWITCH_TO_MODE.find(text) ?: return null
        return match.groupValues[1].lowercase()
    }

    fun detectModelSwitch(text: String): String? {
        val match = SWITCH_TO_MODEL.find(text) ?: return null
        return match.groupValues[1].lowercase()
    }

    fun stripAll(text: String): String {
        var result = text
        result = SWITCH_TO_MODE.replace(result, "")
        result = SWITCH_TO_MODEL.replace(result, "")
        return result.trim()
    }
}

sealed class NeedsProResult(val hasMarker: Boolean) {
    object NONE : NeedsProResult(false)
    class REQUESTED(val reasonText: String?) : NeedsProResult(true)
}
