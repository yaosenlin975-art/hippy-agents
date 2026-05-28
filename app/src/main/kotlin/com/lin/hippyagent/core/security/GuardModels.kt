package com.lin.hippyagent.core.security

import androidx.annotation.ColorRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.UUID

enum class ToolExecutionLevel(val value: String) {
    STRICT("strict"),
    SMART("smart"),
    AUTO("auto"),
    OFF("off");

    fun isOff(): Boolean = this == OFF
    fun requiresApprovalForAllTools(): Boolean = this == STRICT
    fun isSmartMode(): Boolean = this == SMART

    companion object {
        fun fromConfig(levelStr: String): ToolExecutionLevel =
            entries.find { it.value == levelStr.lowercase() } ?: AUTO
    }
}

enum class GuardSeverity(
    val value: String,
    val color: Color,
    val icon: ImageVector,
    val severityLevel: Int
) {
    CRITICAL("CRITICAL", Color(0xFFD32F2F), Icons.Filled.Error, 5),
    HIGH("HIGH", Color(0xFFFF9800), Icons.Filled.Warning, 4),
    MEDIUM("MEDIUM", Color(0xFFFFC107), Icons.Filled.Info, 3),
    LOW("LOW", Color(0xFF2196F3), Icons.Outlined.Info, 2),
    INFO("INFO", Color(0xFF9C27B0), Icons.Filled.HelpOutline, 1),
    SAFE("SAFE", Color(0xFF4CAF50), Icons.Filled.CheckCircle, 0);

    companion object {
        fun fromValue(value: String): GuardSeverity =
            entries.find { it.value == value.uppercase() } ?: SAFE
    }
}

enum class GuardThreatCategory(val value: String) {
    COMMAND_INJECTION("command_injection"),
    PATH_TRAVERSAL("path_traversal"),
    DATA_EXFILTRATION("data_exfiltration"),
    PERMISSION_ESCALATION("permission_escalation"),
    SHELL_EVASION("shell_evasion"),
    RESOURCE_ABUSE("resource_abuse"),
    PRIVACY_VIOLATION("privacy_violation"),
    PERMISSION_DENIED("permission_denied");

    fun displayName(): String = when (this) {
        COMMAND_INJECTION -> "命令注入"
        PATH_TRAVERSAL -> "路径遍历"
        DATA_EXFILTRATION -> "数据泄露"
        PERMISSION_ESCALATION -> "权限提升"
        SHELL_EVASION -> "Shell 逃逸"
        RESOURCE_ABUSE -> "资源滥用"
        PRIVACY_VIOLATION -> "隐私违规"
        PERMISSION_DENIED -> "权限缺失"
    }
}

data class GuardFinding(
    val id: String = UUID.randomUUID().toString(),
    val ruleId: String,
    val category: GuardThreatCategory,
    val severity: GuardSeverity,
    val title: String,
    val description: String,
    val toolName: String,
    val paramName: String? = null,
    val matchedValue: String? = null,
    val matchedPattern: String? = null,
    val snippet: String? = null,
    val remediation: String? = null,
    val guardian: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class ToolGuardCheckResult(
    val findings: List<GuardFinding>,
    val requiresApproval: Boolean = false,
    val isDenied: Boolean = false,
    val denialReason: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isSafe: Boolean get() = !isDenied && findings.isEmpty()
    val maxSeverity: GuardSeverity get() = findings.maxByOrNull { it.severity.severityLevel }?.severity ?: GuardSeverity.SAFE
    val findingsCount: Int get() = findings.size

    fun formatSummary(maxItems: Int = 3): String {
        if (findings.isEmpty()) return "未发现安全风险"
        return findings.take(maxItems).joinToString("\n") { "- [${it.severity.value}] ${it.description}" } +
            if (findings.size > maxItems) "\n- ... 还有 ${findings.size - maxItems} 项发现已省略" else ""
    }
}

