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

sealed class NeedsProResult(val hasMarker: Boolean) {
    object NONE : NeedsProResult(false)
    class REQUESTED(val reasonText: String?) : NeedsProResult(true)
}
