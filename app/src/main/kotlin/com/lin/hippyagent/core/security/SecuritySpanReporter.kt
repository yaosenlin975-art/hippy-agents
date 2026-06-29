package com.lin.hippyagent.core.security

import com.lin.hippyagent.core.security.pii.PiiMasker
import com.lin.hippyagent.core.trace.SpanCollector
import com.lin.hippyagent.core.trace.SpanType
import timber.log.Timber

object SecuritySpanReporter {

    enum class SecurityEventType {
        PII_MASKED,
        INJECTION_DETECTED,
        JAILBREAK_DETECTED,
        OUTPUT_VALIDATION_FAILED,
        MEMORY_UNTRUSTED_MARKED,
        RISK_LEVEL_BLOCKED
    }

    fun report(
        type: SecurityEventType,
        severity: RiskLevel,
        traceId: String,
        source: String,
        ruleId: String? = null,
        matchedSnippet: String? = null,
        blocked: Boolean = false
    ) {
        val props = buildMap {
            put("securityType", type.name)
            put("severity", severity.name)
            put("source", source)
            ruleId?.let { put("ruleId", it) }
            matchedSnippet?.let { put("matchedSnippet", sanitizeSnippet(it)) }
            put("blocked", blocked)
        }

        val span = SpanCollector.startSpan(
            type = SpanType.SECURITY_EVENT,
            traceId = traceId,
            props = props
        )
        SpanCollector.end(span)

        when (severity) {
            RiskLevel.CRITICAL, RiskLevel.BLOCKED -> Timber.w("SecurityEvent: $type $props")
            RiskLevel.HIGH -> Timber.i("SecurityEvent: $type $props")
            else -> Timber.d("SecurityEvent: $type $props")
        }
    }

    private fun sanitizeSnippet(snippet: String): String {
        val truncated = snippet.take(200)
        return PiiMasker().mask(truncated)
    }
}
