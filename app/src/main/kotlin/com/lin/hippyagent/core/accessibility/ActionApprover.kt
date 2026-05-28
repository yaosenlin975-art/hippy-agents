package com.lin.hippyagent.core.accessibility

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

enum class RiskLevel {
    LOW, MEDIUM, HIGH, BLOCKED
}

data class ApprovalRequest(
    val action: String,
    val target: String?,
    val value: String?,
    val riskLevel: RiskLevel,
    val packageName: String?,
    val requestId: String = java.util.UUID.randomUUID().toString()
)

data class ApprovalResult(
    val approved: Boolean,
    val duration: ApprovalDuration = ApprovalDuration.ONCE
)

enum class ApprovalDuration {
    ONCE, MINUTES_5, SESSION
}

class ActionApprover(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "accessibility_approver"
        private const val KEY_SESSION_APPROVED = "session_approved_actions"
        private const val KEY_TEMP_APPROVED_UNTIL = "temp_approved_until"
        private const val APPROVAL_TIMEOUT_MS = 30_000L
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val approvalChannel = Channel<ApprovalResult>(Channel.CONFLATED)
    val pendingRequest = MutableStateFlow<ApprovalRequest?>(null)

    fun assessRisk(action: String, target: String?, value: String?, packageName: String?): RiskLevel {
        if (packageName != null && isInBlockedPackage(packageName)) return RiskLevel.BLOCKED

        if (value != null && containsSensitiveContent(value)) return RiskLevel.HIGH

        return when (action) {
            "click", "long_click" -> RiskLevel.MEDIUM
            "input_text" -> if (value != null && looksSensitive(value)) RiskLevel.HIGH else RiskLevel.MEDIUM
            "scroll", "swipe" -> RiskLevel.LOW
            "press_back", "press_home", "press_recents" -> RiskLevel.LOW
            "open_notifications", "open_quick_settings" -> RiskLevel.LOW
            "launch_app" -> RiskLevel.MEDIUM
            else -> RiskLevel.MEDIUM
        }
    }

    suspend fun approve(request: ApprovalRequest): Boolean {
        when (request.riskLevel) {
            RiskLevel.LOW -> return true
            RiskLevel.BLOCKED -> {
                Timber.w("Action blocked: ${request.action} on ${request.packageName}")
                return false
            }
            RiskLevel.MEDIUM -> {
                if (isSessionApproved(request)) return true
            }
            RiskLevel.HIGH -> {
                // HIGH always requires explicit approval
            }
        }

        pendingRequest.value = request
        val result = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
            approvalChannel.receive()
        } ?: ApprovalResult(approved = false)

        pendingRequest.value = null

        if (result.approved) {
            when (result.duration) {
                ApprovalDuration.SESSION -> markSessionApproved(request)
                ApprovalDuration.MINUTES_5 -> markTempApproved(request)
                ApprovalDuration.ONCE -> {}
            }
        }

        return result.approved
    }

    fun respond(result: ApprovalResult) {
        approvalChannel.trySend(result)
    }

    private fun isInBlockedPackage(packageName: String): Boolean {
        val blocked = setOf(
            "com.android.providers.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
        )
        return packageName in blocked
    }

    private fun containsSensitiveContent(text: String): Boolean {
        val patterns = listOf(
            Regex("""(?i)(密码|password|passwd|pin|验证码|captcha)"""),
            Regex("""(?i)(支付|付款|转账|transfer|payment)"""),
            Regex("""(?i)(删除|清空|卸载|delete|remove|uninstall|clear)""")
        )
        return patterns.any { it.containsMatchIn(text) }
    }

    private fun looksSensitive(text: String): Boolean {
        val patterns = listOf(
            Regex("""(?i)(密码|password|pin|验证码)"""),
            Regex("""\d+[.,]\d{2}"""),
            Regex("""(?i)(支付|转账|付款)""")
        )
        return patterns.any { it.containsMatchIn(text) }
    }

    private fun isSessionApproved(request: ApprovalRequest): Boolean {
        val key = sessionKey(request.action, request.packageName)
        return prefs.getBoolean(KEY_SESSION_APPROVED, false) &&
                prefs.getStringSet(KEY_SESSION_APPROVED, emptySet())?.contains(key) == true
    }

    private fun markSessionApproved(request: ApprovalRequest) {
        val key = sessionKey(request.action, request.packageName)
        val existing = prefs.getStringSet(KEY_SESSION_APPROVED, emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add(key)
        prefs.edit().putStringSet(KEY_SESSION_APPROVED, existing).apply()
    }

    private fun markTempApproved(request: ApprovalRequest) {
        val until = System.currentTimeMillis() + 5 * 60 * 1000
        prefs.edit().putLong(KEY_TEMP_APPROVED_UNTIL, until).apply()
    }

    private fun sessionKey(action: String, packageName: String?): String {
        return "${action}@${packageName ?: "*"}"
    }

    fun clearSessionApprovals() {
        prefs.edit().remove(KEY_SESSION_APPROVED).remove(KEY_TEMP_APPROVED_UNTIL).apply()
    }
}

