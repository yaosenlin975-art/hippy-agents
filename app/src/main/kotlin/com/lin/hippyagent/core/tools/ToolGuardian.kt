package com.lin.hippyagent.core.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lin.hippyagent.core.security.GuardFinding
import com.lin.hippyagent.core.security.GuardSeverity
import com.lin.hippyagent.core.security.GuardThreatCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

class ToolGuardian(
    private val context: Context? = null
) {
    data class SecurityCheck(
        val passed: Boolean,
        val riskLevel: RiskLevel = RiskLevel.SAFE,
        val reason: String? = null,
        val findings: List<GuardFinding> = emptyList()
    )

    enum class RiskLevel { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

    private val shellEvasionPatterns = SHELL_EVASION_PATTERNS

    private val shellObfuscationIndicators = SHELL_OBFUSCATION_INDICATORS

    private val dangerousPatterns = DANGEROUS_PATTERNS

    private val dangerousPaths = listOf(
        "/etc/passwd",
        "/etc/shadow",
        "/etc/sudoers",
    )

    private val criticalPaths = setOf(
        "/system/bin", "/system/xbin", "/system/app", "/system/priv-app",
        "/system/framework", "/vendor/bin", "/sbin", "/proc", "/sys",
        "/dev", "/data/local/tmp", "/data/data", "/init", "/init.rc"
    )

    private val sensitiveExtensions = setOf(
        ".so", ".apk", ".dex", ".odex", ".vdex", ".art", ".oat",
        ".conf", ".prop", ".rc", ".sh"
    )

    private val storageRestrictedDirs = setOf(
        "/data/data", "/system", "/vendor", "/storage/emulated/0/Android/data"
    )

    private val networkBlockedDomains = setOf("localhost", "127.0.0.1", "0.0.0.0")
    private val networkSensitiveParams = setOf("url", "endpoint", "host", "domain", "api_key", "token")

    private val blockedA11yPackages = setOf(
        "com.android.providers.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    private val sensitiveInputPatterns = SENSITIVE_INPUT_PATTERNS

    private val permissionToolMap = mapOf(
        "read_contacts" to android.Manifest.permission.READ_CONTACTS,
        "write_contacts" to android.Manifest.permission.WRITE_CONTACTS,
        "read_sms" to android.Manifest.permission.READ_SMS,
        "send_sms" to android.Manifest.permission.SEND_SMS,
        "get_location" to android.Manifest.permission.ACCESS_FINE_LOCATION,
        "read_phone_state" to android.Manifest.permission.READ_PHONE_STATE,
        "record_audio" to android.Manifest.permission.RECORD_AUDIO,
        "read_calendar" to android.Manifest.permission.READ_CALENDAR,
        "write_calendar" to android.Manifest.permission.WRITE_CALENDAR,
        "read_call_log" to android.Manifest.permission.READ_CALL_LOG,
        "camera_capture" to android.Manifest.permission.CAMERA
    )

    private val shellParams = setOf("command", "cmd", "script", "shell", "exec", "code", "expression")
    private val pathKeys = setOf("path", "file_path", "filePath", "directory", "dir",
        "source", "destination", "dest", "output", "input", "filename", "uri")

    private val fileOperationTools = setOf(
        "read_file", "write_file", "edit_file", "append_file", "delete_file",
        "list_directory", "read_logcat",
        "get_file_size", "get_text_lines_count", "get_working_directory"
    )

    suspend fun checkToolCall(
        toolName: String,
        arguments: Map<String, Any>,
        workspacePaths: List<String> = emptyList()
    ): SecurityCheck = withContext(Dispatchers.Default) {
        Timber.d("ToolGuardian.checkToolCall: tool=%s args=%s", toolName, arguments)
        val findings = mutableListOf<GuardFinding>()

        checkToolNameRisk(toolName, arguments, workspacePaths, findings)
        if (toolName !in fileOperationTools) {
            checkShellEvasion(toolName, arguments, findings)
        }
        checkFilePaths(toolName, arguments, workspacePaths, findings)
        checkAndroidPermissions(toolName, findings)
        checkStorageAccess(toolName, arguments, workspacePaths, findings)
        checkNetworkPolicy(toolName, arguments, findings)
        checkAccessibility(toolName, arguments, findings)
        if (toolName !in fileOperationTools) {
            checkDangerousPatterns(toolName, arguments, findings)
        }

        val maxSeverity = findings.maxByOrNull { it.severity.severityLevel }?.severity
        val riskLevel = when {
            maxSeverity == null -> RiskLevel.SAFE
            maxSeverity == GuardSeverity.CRITICAL -> RiskLevel.CRITICAL
            maxSeverity == GuardSeverity.HIGH -> RiskLevel.HIGH
            maxSeverity == GuardSeverity.MEDIUM -> RiskLevel.MEDIUM
            maxSeverity == GuardSeverity.LOW -> RiskLevel.LOW
            else -> RiskLevel.SAFE
        }

        val passed = riskLevel < RiskLevel.HIGH
        val reason = if (!passed) findings.first { it.severity.severityLevel >= GuardSeverity.HIGH.severityLevel }.description else null

        SecurityCheck(passed = passed, riskLevel = riskLevel, reason = reason, findings = findings)
    }

    private fun checkToolNameRisk(toolName: String, arguments: Map<String, Any>, workspacePaths: List<String>, findings: MutableList<GuardFinding>) {
        val isWorkspaceOp = arguments.values.any { value ->
            value is String && workspacePaths.any { value.replace('\\', '/').startsWith(it) }
        }
        when {
            toolName == "execute_shell" -> findings.add(finding(
                ruleId = "TOOL_SHELL_EXECUTE", category = GuardThreatCategory.COMMAND_INJECTION,
                severity = GuardSeverity.MEDIUM, title = "Shell 执行", description = "Shell 执行需要确认",
                toolName = toolName
            ))
            toolName.contains("delete", ignoreCase = true) -> findings.add(finding(
                ruleId = "TOOL_DELETE_OP", category = GuardThreatCategory.RESOURCE_ABUSE,
                severity = if (isWorkspaceOp) GuardSeverity.MEDIUM else GuardSeverity.HIGH,
                title = "删除操作", description = "删除操作需要确认",
                toolName = toolName
            ))
            toolName.contains("write", ignoreCase = true) -> findings.add(finding(
                ruleId = "TOOL_WRITE_OP", category = GuardThreatCategory.RESOURCE_ABUSE,
                severity = if (isWorkspaceOp) GuardSeverity.LOW else GuardSeverity.MEDIUM,
                title = "写入操作", description = "写入操作需要确认",
                toolName = toolName
            ))
        }
    }

    private fun checkShellEvasion(toolName: String, arguments: Map<String, Any>, findings: MutableList<GuardFinding>) {
        for ((key, value) in arguments) {
            if (value !is String) continue
            val isShell = key in shellParams || key.contains("command", ignoreCase = true) ||
                    key.contains("script", ignoreCase = true) || key.contains("exec", ignoreCase = true)
            if (!isShell) continue

            for (pattern in shellEvasionPatterns) {
                if (pattern.containsMatchIn(value)) {
                    findings.add(finding(
                        ruleId = "SHELL_EVASION_DETECTED", category = GuardThreatCategory.COMMAND_INJECTION,
                        severity = GuardSeverity.CRITICAL, title = "Shell 逃逸/混淆检测",
                        description = "工具 $toolName 的参数 '$key' 包含疑似逃逸/混淆模式: ${pattern.pattern}",
                        toolName = toolName, paramName = key, matchedPattern = pattern.pattern, snippet = value.take(100)
                    ))
                    break
                }
            }
            for (pattern in shellObfuscationIndicators) {
                if (pattern.containsMatchIn(value)) {
                    findings.add(finding(
                        ruleId = "SHELL_OBFUSCATION_DETECTED", category = GuardThreatCategory.COMMAND_INJECTION,
                        severity = GuardSeverity.HIGH, title = "Shell 混淆检测",
                        description = "工具 $toolName 的参数 '$key' 包含疑似混淆指示器: ${pattern.pattern}",
                        toolName = toolName, paramName = key, matchedPattern = pattern.pattern, snippet = value.take(100)
                    ))
                    break
                }
            }
        }
    }

    private fun checkFilePaths(toolName: String, arguments: Map<String, Any>, workspacePaths: List<String>, findings: MutableList<GuardFinding>) {
        for ((key, value) in arguments) {
            if (value !is String) continue
            if (key !in pathKeys && !key.contains("path", ignoreCase = true) &&
                !key.contains("file", ignoreCase = true) && !key.contains("dir", ignoreCase = true)) continue

            val normalized = value.replace('\\', '/')
            if (workspacePaths.any { normalized.startsWith(it) }) continue

            for (critical in criticalPaths) {
                if (normalized.startsWith(critical) || normalized.contains("/../$critical") || normalized.contains("/..$critical")) {
                    findings.add(finding(
                        ruleId = "FILE_PATH_CRITICAL", category = GuardThreatCategory.PATH_TRAVERSAL,
                        severity = GuardSeverity.CRITICAL, title = "访问系统关键路径",
                        description = "工具 $toolName 尝试访问系统关键路径 $value",
                        toolName = toolName, paramName = key, matchedValue = value
                    ))
                }
            }

            if (normalized.contains("..") && (normalized.contains("/..") || normalized.contains("\\.."))) {
                findings.add(finding(
                    ruleId = "FILE_PATH_TRAVERSAL", category = GuardThreatCategory.PATH_TRAVERSAL,
                    severity = GuardSeverity.HIGH, title = "路径穿越攻击",
                    description = "工具 $toolName 的参数 '$key' 包含路径穿越序列: $value",
                    toolName = toolName, paramName = key, matchedValue = value, matchedPattern = "../"
                ))
            }

            for (ext in sensitiveExtensions) {
                if (normalized.endsWith(ext, ignoreCase = true)) {
                    val isOwnSandbox = context != null && normalized.contains(context.packageName)
                    if (!isOwnSandbox) {
                        findings.add(finding(
                            ruleId = "FILE_PATH_SENSITIVE_EXT", category = GuardThreatCategory.PATH_TRAVERSAL,
                            severity = GuardSeverity.MEDIUM, title = "访问敏感文件类型",
                            description = "工具 $toolName 尝试访问敏感文件类型 $ext: $value",
                            toolName = toolName, paramName = key, matchedValue = value
                        ))
                    }
                }
            }

            val symlinks = setOf("/proc/self", "/proc/\$\$", "/dev/stdin", "/dev/stdout", "/dev/stderr")
            for (symlink in symlinks) {
                if (normalized.contains(symlink)) {
                    findings.add(finding(
                        ruleId = "FILE_PATH_SYMLINK", category = GuardThreatCategory.PATH_TRAVERSAL,
                        severity = GuardSeverity.HIGH, title = "通过符号链接绕过路径限制",
                        description = "工具 $toolName 的参数包含符号链接路径: $value",
                        toolName = toolName, paramName = key, matchedValue = value
                    ))
                }
            }
        }
    }

    private fun checkAndroidPermissions(toolName: String, findings: MutableList<GuardFinding>) {
        val requiredPermission = permissionToolMap[toolName] ?: return
        val ctx = context ?: return
        if (ContextCompat.checkSelfPermission(ctx, requiredPermission) != PackageManager.PERMISSION_GRANTED) {
            findings.add(finding(
                ruleId = "ANDROID_PERMISSION_MISSING", category = GuardThreatCategory.PERMISSION_DENIED,
                severity = GuardSeverity.HIGH, title = "缺少 Android 运行时权限",
                description = "工具 $toolName 需要权限 $requiredPermission 但未授予",
                toolName = toolName
            ))
        }
    }

    private fun checkStorageAccess(toolName: String, arguments: Map<String, Any>, workspacePaths: List<String>, findings: MutableList<GuardFinding>) {
        for ((key, value) in arguments) {
            if (key in pathKeys && value is String) {
                val normalized = value.replace('\\', '/')
                if (workspacePaths.any { normalized.startsWith(it) }) continue
                for (restricted in storageRestrictedDirs) {
                    if (normalized.startsWith(restricted) && !normalized.contains("com.lin.hippyagent")) {
                        findings.add(finding(
                            ruleId = "STORAGE_ACCESS_RESTRICTED", category = GuardThreatCategory.PATH_TRAVERSAL,
                            severity = GuardSeverity.HIGH, title = "访问受限存储区域",
                            description = "工具 $toolName 尝试访问受限目录 $value",
                            toolName = toolName, paramName = key, matchedValue = value
                        ))
                    }
                }
            }
        }
    }

    private fun checkNetworkPolicy(toolName: String, arguments: Map<String, Any>, findings: MutableList<GuardFinding>) {
        for ((key, value) in arguments) {
            if (key in networkSensitiveParams && value is String) {
                for (blocked in networkBlockedDomains) {
                    if (value.contains(blocked, ignoreCase = true)) {
                        findings.add(finding(
                            ruleId = "NETWORK_LOCALHOST_ACCESS", category = GuardThreatCategory.DATA_EXFILTRATION,
                            severity = GuardSeverity.MEDIUM, title = "访问本地网络",
                            description = "工具 $toolName 尝试访问本地地址 $value",
                            toolName = toolName, paramName = key, matchedValue = value
                        ))
                    }
                }
                if (key in setOf("api_key", "token") && value.length > 8) {
                    findings.add(finding(
                        ruleId = "NETWORK_CREDENTIAL_EXPOSURE", category = GuardThreatCategory.DATA_EXFILTRATION,
                        severity = GuardSeverity.HIGH, title = "网络请求可能泄露凭证",
                        description = "工具 $toolName 的参数 '$key' 包含疑似密钥",
                        toolName = toolName, paramName = key, matchedPattern = "${key}=***"
                    ))
                }
            }
        }
    }

    private fun checkAccessibility(toolName: String, arguments: Map<String, Any>, findings: MutableList<GuardFinding>) {
        if (toolName !in listOf("screen_interact", "screen_observe")) return

        val action = arguments["action"]?.toString() ?: return
        val target = arguments["target"]?.toString()
        val value = arguments["value"]?.toString()

        if (target != null) {
            for (pkg in blockedA11yPackages) {
                if (target.contains(pkg)) {
                    findings.add(finding(
                        ruleId = "A11Y_BLOCKED_PACKAGE", category = GuardThreatCategory.PERMISSION_ESCALATION,
                        severity = GuardSeverity.CRITICAL, title = "禁止操控系统安全应用",
                        description = "目标包名 $pkg 在黑名单中，禁止操控",
                        toolName = "screen_interact", paramName = "target", matchedValue = target
                    ))
                }
            }
        }

        if (action == "input_text" && value != null) {
            for (pattern in sensitiveInputPatterns) {
                if (pattern.containsMatchIn(value)) {
                    findings.add(finding(
                        ruleId = "A11Y_SENSITIVE_INPUT", category = GuardThreatCategory.PRIVACY_VIOLATION,
                        severity = GuardSeverity.HIGH, title = "输入内容包含敏感信息",
                        description = "输入文本可能包含密码/支付/删除等敏感内容，需要用户确认",
                        toolName = "screen_interact", paramName = "value", matchedPattern = pattern.pattern
                    ))
                    break
                }
            }
        }

        when (action) {
            "input_text" -> findings.add(finding(
                ruleId = "A11Y_INPUT_ACTION", category = GuardThreatCategory.PRIVACY_VIOLATION,
                severity = GuardSeverity.MEDIUM, title = "文本输入操作",
                description = "Agent正在向输入框输入文本，首次使用需要用户确认",
                toolName = "screen_interact", paramName = "action", matchedValue = action
            ))
            "launch_app" -> findings.add(finding(
                ruleId = "A11Y_LAUNCH_APP", category = GuardThreatCategory.PERMISSION_ESCALATION,
                severity = GuardSeverity.MEDIUM, title = "启动应用操作",
                description = "Agent正在启动第三方应用",
                toolName = "screen_interact", paramName = "action", matchedValue = action
            ))
        }
    }

    private val contentParams = setOf("content", "text", "message", "body", "data", "value", "input_text", "reply")

    private fun checkDangerousPatterns(toolName: String, arguments: Map<String, Any>, findings: MutableList<GuardFinding>) {
        for ((key, value) in arguments) {
            if (key == "callId") continue
            val valueStr = value.toString()
            val isShellParam = key in shellParams || key.contains("command", ignoreCase = true) ||
                    key.contains("script", ignoreCase = true) || key.contains("exec", ignoreCase = true)
            val isContentParam = key in contentParams

            if (isShellParam || !isContentParam) {
                for (pattern in dangerousPatterns) {
                    if (pattern.containsMatchIn(valueStr)) {
                        findings.add(finding(
                            ruleId = "DANGEROUS_PATTERN", category = GuardThreatCategory.COMMAND_INJECTION,
                            severity = GuardSeverity.CRITICAL, title = "检测到危险命令模式",
                            description = "检测到危险命令模式: ${pattern.pattern}",
                            toolName = toolName, paramName = key, matchedPattern = pattern.pattern, snippet = valueStr.take(100)
                        ))
                        break
                    }
                }
            }

            if (!isContentParam) {
                for (path in dangerousPaths) {
                    val search = "/$path"
                    val normalizedValue = "/${valueStr.replace('\\', '/').trimStart('/')}"
                    if (normalizedValue.contains(search)) {
                        findings.add(finding(
                            ruleId = "DANGEROUS_PATH", category = GuardThreatCategory.PATH_TRAVERSAL,
                            severity = GuardSeverity.HIGH, title = "检测到危险路径",
                            description = "检测到危险路径: $path",
                            toolName = toolName, paramName = key, matchedValue = valueStr
                        ))
                    }
                }

                if (valueStr.contains("../") || valueStr.contains("..\\")) {
                    findings.add(finding(
                        ruleId = "PATH_TRAVERSAL_ATTEMPT", category = GuardThreatCategory.PATH_TRAVERSAL,
                        severity = GuardSeverity.MEDIUM, title = "检测到路径遍历尝试",
                        description = "检测到路径遍历尝试",
                        toolName = toolName, paramName = key, matchedValue = valueStr
                    ))
                }
            }

            if (valueStr.length > 10000) {
                findings.add(finding(
                    ruleId = "INPUT_TOO_LONG", category = GuardThreatCategory.RESOURCE_ABUSE,
                    severity = GuardSeverity.MEDIUM, title = "输入过长",
                    description = "输入过长 (${valueStr.length} 字符)",
                    toolName = toolName, paramName = key
                ))
            }
        }
    }

    internal fun isCommandSafe(command: String): SecurityCheck {
        val findings = mutableListOf<GuardFinding>()
        for (pattern in dangerousPatterns) {
            if (pattern.containsMatchIn(command)) {
                findings.add(finding(
                    ruleId = "DANGEROUS_COMMAND", category = GuardThreatCategory.COMMAND_INJECTION,
                    severity = GuardSeverity.CRITICAL, title = "检测到危险命令",
                    description = "检测到危险命令: ${pattern.pattern}",
                    toolName = "execute_shell", matchedPattern = pattern.pattern
                ))
                break
            }
        }
        for (path in dangerousPaths) {
            val normalizedCmd = "/${command.replace('\\', '/').trimStart('/')}"
            if (normalizedCmd.contains("/$path")) {
                findings.add(finding(
                    ruleId = "DANGEROUS_PATH", category = GuardThreatCategory.PATH_TRAVERSAL,
                    severity = GuardSeverity.HIGH, title = "检测到危险路径",
                    description = "检测到危险路径: $path",
                    toolName = "execute_shell", matchedValue = command
                ))
            }
        }
        val maxSeverity = findings.maxByOrNull { it.severity.severityLevel }?.severity
        val riskLevel = when {
            maxSeverity == null -> RiskLevel.SAFE
            maxSeverity == GuardSeverity.CRITICAL -> RiskLevel.CRITICAL
            maxSeverity == GuardSeverity.HIGH -> RiskLevel.HIGH
            maxSeverity == GuardSeverity.MEDIUM -> RiskLevel.MEDIUM
            maxSeverity == GuardSeverity.LOW -> RiskLevel.LOW
            else -> RiskLevel.SAFE
        }
        return SecurityCheck(passed = riskLevel < RiskLevel.HIGH, riskLevel = riskLevel,
            reason = findings.firstOrNull()?.description, findings = findings)
    }

    fun logAudit(toolName: String, arguments: Map<String, Any>, checkResult: SecurityCheck) {
        Timber.d("ToolGuardian.logAudit: tool=%s risk=%s passed=%s findings=%d", toolName, checkResult.riskLevel, checkResult.passed, checkResult.findings.size)
        if (checkResult.riskLevel >= RiskLevel.MEDIUM) {
            Timber.w("Security audit: tool=$toolName risk=${checkResult.riskLevel} reason=${checkResult.reason}")
        }
    }

    private fun finding(
        ruleId: String, category: GuardThreatCategory, severity: GuardSeverity,
        title: String, description: String, toolName: String,
        paramName: String? = null, matchedValue: String? = null,
        matchedPattern: String? = null, snippet: String? = null
    ) = GuardFinding(
        id = UUID.randomUUID().toString(),
        ruleId = ruleId, category = category, severity = severity,
        title = title, description = description, toolName = toolName,
        paramName = paramName, matchedValue = matchedValue,
        matchedPattern = matchedPattern, snippet = snippet,
        guardian = "ToolGuardian"
    )

    companion object {
        private val SHELL_EVASION_PATTERNS = listOf(
            Regex("""\\x[0-9a-fA-F]{2}"""),
            Regex("""\\u[0-9a-fA-F]{4}"""),
            Regex("""\$\{.*\}"""),
            Regex("""\$\([^)]+\)"""),
            Regex("""`[^`]+`"""),
            Regex("""(?i)base64\s+--decode"""),
            Regex("""(?i)xxd\s+-r"""),
            Regex("""(?i)printf\s+\\x"""),
            Regex("""(?i)echo\s+-e\s+\\x"""),
            Regex("""(?i)eval\s+["']"""),
            Regex("""(?i)exec\s+["']"""),
            Regex("""(?i)python[23]?\s+-c"""),
            Regex("""(?i)perl\s+-e"""),
            Regex("""(?i)ruby\s+-e"""),
            Regex("""(?i)env\s+-[iS]"""),
            Regex("""(?i)/dev/tcp/"""),
            Regex("""(?i)nc\s+-[elp]"""),
            Regex("""(?i)curl\s+.*\|\s*sh"""),
            Regex("""(?i)wget\s+.*\|\s*sh"""),
            Regex("""(?i)chmod\s+\+x"""),
            Regex("""(?i)chown\s+root"""),
            Regex("""(?i)nohup\s+"""),
            Regex("""(?i)setsid\s+"""),
            Regex(""";\s*rm\s+-rf"""),
            Regex("""\|\s*rm\s+-rf"""),
            Regex("""&&\s*rm\s+-rf"""),
            Regex("""(?i)su\s+-c"""),
            Regex("""(?i)sudo\s+"""),
            Regex("""(?i)mount\s+-o\s+remount"""),
            Regex("""(?i)iptables\s+"""),
            Regex("""(?i)insmod\s+"""),
            Regex("""(?i)rmmod\s+""")
        )

        private val SHELL_OBFUSCATION_INDICATORS = listOf(
            Regex("""\$\{IFS\}"""),
            Regex("""\$\{PATH\:"""),
            Regex("""''"""),
            Regex("""(?i)\bcat\b\s+.*\bcat\b"""),
            Regex("""(?i)head\s+-c\s+\d+\s+"""),
            Regex("""(?i)tail\s+-c\s+\d+\s+"""),
            Regex("""(?i)rev\s+"""),
            Regex("""(?i)tr\s+""")
        )

        private val DANGEROUS_PATTERNS = listOf(
            Regex("rm\\s+-rf\\s+/"),
            Regex("dd\\s+if="),
            Regex("mkfs"),
            Regex("chmod\\s+777\\s+/"),
            Regex("chown\\s+root"),
            Regex("sudo\\s+"),
            Regex("su\\s+-"),
            Regex("curl.*\\|.*sh"),
            Regex("wget.*\\|.*sh"),
            Regex("DROP\\s+TABLE", RegexOption.IGNORE_CASE),
            Regex("DELETE\\s+FROM", RegexOption.IGNORE_CASE),
            Regex("INSERT\\s+INTO", RegexOption.IGNORE_CASE),
            Regex("\\$\\(.*\\)"),
            Regex("`.*`"),
        )

        private val SENSITIVE_INPUT_PATTERNS = listOf(
            Regex("""(?i)(密码|password|passwd|pin|验证码|captcha)"""),
            Regex("""(?i)(支付|付款|转账|transfer|payment)"""),
            Regex("""(?i)(删除|清空|卸载|delete|remove|uninstall|clear)""")
        )
    }
}
