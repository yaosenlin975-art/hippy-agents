package com.lin.hippyagent.core.security.guardians

import com.lin.hippyagent.core.security.GuardFinding
import com.lin.hippyagent.core.security.GuardSeverity
import com.lin.hippyagent.core.security.GuardThreatCategory
import com.lin.hippyagent.core.security.Guardian
import com.lin.hippyagent.core.security.ToolGuardContext
import java.util.UUID

class AccessibilityGuardian : Guardian {

    companion object {
        private val BLOCKED_PACKAGES = setOf(
            "com.android.providers.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
        )

        private val SENSITIVE_PATTERNS = listOf(
            Regex("""(?i)(密码|password|passwd|pin|验证码|captcha)"""),
            Regex("""(?i)(支付|付款|转账|transfer|payment)"""),
            Regex("""(?i)(删除|清空|卸载|delete|remove|uninstall|clear)""")
        )
    }

    override val name = "accessibility_guard"
    override val priority = 10
    override val alwaysRun = false

    override suspend fun check(
        toolName: String,
        arguments: Map<String, Any>,
        context: ToolGuardContext
    ): List<GuardFinding> {
        if (toolName !in listOf("screen_interact", "screen_observe")) return emptyList()

        val findings = mutableListOf<GuardFinding>()

        if (toolName == "screen_interact") {
            val action = arguments["action"]?.toString() ?: return findings
            val target = arguments["target"]?.toString()
            val value = arguments["value"]?.toString()

            checkBlockedTarget(target, findings)
            checkSensitiveContent(action, value, findings)
            checkHighRiskAction(action, findings)
        }

        return findings
    }

    private fun checkBlockedTarget(target: String?, findings: MutableList<GuardFinding>) {
        if (target == null) return
        for (pkg in BLOCKED_PACKAGES) {
            if (target.contains(pkg)) {
                findings.add(
                    GuardFinding(
                        id = UUID.randomUUID().toString(),
                        ruleId = "A11Y_BLOCKED_PACKAGE",
                        category = GuardThreatCategory.PERMISSION_ESCALATION,
                        severity = GuardSeverity.CRITICAL,
                        title = "禁止操控系统安全应用",
                        description = "目标包名 $pkg 在黑名单中，禁止操控",
                        toolName = "screen_interact",
                        paramName = "target",
                        matchedValue = target
                    )
                )
            }
        }
    }

    private fun checkSensitiveContent(action: String, value: String?, findings: MutableList<GuardFinding>) {
        if (action != "input_text" || value == null) return
        for (pattern in SENSITIVE_PATTERNS) {
            if (pattern.containsMatchIn(value)) {
                findings.add(
                    GuardFinding(
                        id = UUID.randomUUID().toString(),
                        ruleId = "A11Y_SENSITIVE_INPUT",
                        category = GuardThreatCategory.PRIVACY_VIOLATION,
                        severity = GuardSeverity.HIGH,
                        title = "输入内容包含敏感信息",
                        description = "输入文本可能包含密码/支付/删除等敏感内容，需要用户确认",
                        toolName = "screen_interact",
                        paramName = "value",
                        matchedPattern = pattern.pattern
                    )
                )
                break
            }
        }
    }

    private fun checkHighRiskAction(action: String, findings: MutableList<GuardFinding>) {
        when (action) {
            "input_text" -> {
                findings.add(
                    GuardFinding(
                        id = UUID.randomUUID().toString(),
                        ruleId = "A11Y_INPUT_ACTION",
                        category = GuardThreatCategory.PRIVACY_VIOLATION,
                        severity = GuardSeverity.MEDIUM,
                        title = "文本输入操作",
                        description = "Agent正在向输入框输入文本，首次使用需要用户确认",
                        toolName = "screen_interact",
                        paramName = "action",
                        matchedValue = action
                    )
                )
            }
            "launch_app" -> {
                findings.add(
                    GuardFinding(
                        id = UUID.randomUUID().toString(),
                        ruleId = "A11Y_LAUNCH_APP",
                        category = GuardThreatCategory.PERMISSION_ESCALATION,
                        severity = GuardSeverity.MEDIUM,
                        title = "启动应用操作",
                        description = "Agent正在启动第三方应用",
                        toolName = "screen_interact",
                        paramName = "action",
                        matchedValue = action
                    )
                )
            }
        }
    }
}

