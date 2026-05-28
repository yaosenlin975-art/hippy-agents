package com.lin.hippyagent.core.security.guardians

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lin.hippyagent.core.security.*
import timber.log.Timber
import java.util.UUID

class ShellEvasionGuardian : Guardian {
    override val name = "shell_evasion"
    override val priority = 15
    override val alwaysRun = true

    private val evasionPatterns = listOf(
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

    private val obfuscationIndicators = listOf(
        Regex("""\$\{IFS\}"""),
        Regex("""\$\{PATH\:"""),
        Regex("""''"""),
        Regex("""(?i)\bcat\b\s+.*\bcat\b"""),
        Regex("""(?i)head\s+-c\s+\d+\s+"""),
        Regex("""(?i)tail\s+-c\s+\d+\s+"""),
        Regex("""(?i)rev\s+"""),
        Regex("""(?i)tr\s+""")
    )

    override suspend fun check(
        toolName: String,
        arguments: Map<String, Any>,
        context: ToolGuardContext
    ): List<GuardFinding> {
        val findings = mutableListOf<GuardFinding>()
        val shellParams = setOf("command", "cmd", "script", "shell", "exec", "code", "expression")

        for ((key, value) in arguments) {
            if (value !is String) continue
            val isShellParam = key in shellParams || key.contains("command", ignoreCase = true) ||
                    key.contains("script", ignoreCase = true) || key.contains("exec", ignoreCase = true)
            if (!isShellParam) continue

            for (pattern in evasionPatterns) {
                if (pattern.containsMatchIn(value)) {
                    findings.add(
                        GuardFinding(
                            id = UUID.randomUUID().toString(),
                            ruleId = "SHELL_EVASION_DETECTED",
                            category = GuardThreatCategory.COMMAND_INJECTION,
                            severity = GuardSeverity.CRITICAL,
                            title = "Shell 逃逸/混淆检测",
                            description = "工具 $toolName 的参数 '$key' 包含疑似逃逸/混淆模式: ${pattern.pattern}",
                            toolName = toolName,
                            paramName = key,
                            matchedPattern = pattern.pattern,
                            snippet = value.take(100),
                            remediation = "禁止使用 Shell 逃逸和混淆技术，请使用直接命令",
                            guardian = name
                        )
                    )
                    break
                }
            }

            for (pattern in obfuscationIndicators) {
                if (pattern.containsMatchIn(value)) {
                    findings.add(
                        GuardFinding(
                            id = UUID.randomUUID().toString(),
                            ruleId = "SHELL_OBFUSCATION_DETECTED",
                            category = GuardThreatCategory.COMMAND_INJECTION,
                            severity = GuardSeverity.HIGH,
                            title = "Shell 混淆检测",
                            description = "工具 $toolName 的参数 '$key' 包含疑似混淆指示器: ${pattern.pattern}",
                            toolName = toolName,
                            paramName = key,
                            matchedPattern = pattern.pattern,
                            snippet = value.take(100),
                            remediation = "禁止使用 Shell 混淆技术",
                            guardian = name
                        )
                    )
                    break
                }
            }
        }
        return findings
    }
}

class FilePathGuardian(private val context: Context? = null) : Guardian {
    override val name = "file_path"
    override val priority = 12
    override val alwaysRun = true

    private val criticalPaths = setOf(
        "/system/bin",
        "/system/xbin",
        "/system/app",
        "/system/priv-app",
        "/system/framework",
        "/vendor/bin",
        "/sbin",
        "/proc",
        "/sys",
        "/dev",
        "/data/local/tmp",
        "/data/data",
        "/init",
        "/init.rc"
    )

    private val sensitiveExtensions = setOf(
        ".so", ".apk", ".dex", ".odex", ".vdex", ".art", ".oat",
        ".conf", ".prop", ".rc", ".sh"
    )

    private val pathKeys = setOf("path", "file_path", "filePath", "directory", "dir",
        "source", "destination", "dest", "output", "input", "filename", "uri")

    override suspend fun check(
        toolName: String,
        arguments: Map<String, Any>,
        guardContext: ToolGuardContext
    ): List<GuardFinding> {
        val findings = mutableListOf<GuardFinding>()

        for ((key, value) in arguments) {
            if (value !is String) continue
            if (key !in pathKeys && !key.contains("path", ignoreCase = true) &&
                !key.contains("file", ignoreCase = true) && !key.contains("dir", ignoreCase = true)) continue

            for (critical in criticalPaths) {
                if (value.startsWith(critical) || value.contains("/../$critical") || value.contains("/..$critical")) {
                    findings.add(
                        GuardFinding(
                            id = UUID.randomUUID().toString(),
                            ruleId = "FILE_PATH_CRITICAL",
                            category = GuardThreatCategory.PATH_TRAVERSAL,
                            severity = GuardSeverity.CRITICAL,
                            title = "访问系统关键路径",
                            description = "工具 $toolName 尝试访问系统关键路径 $value",
                            toolName = toolName,
                            paramName = key,
                            matchedValue = value,
                            remediation = "禁止访问系统关键路径，仅允许访问应用沙箱内路径",
                            guardian = name
                        )
                    )
                }
            }

            if (value.contains("..") && (value.contains("/..") || value.contains("\\.."))) {
                findings.add(
                    GuardFinding(
                        id = UUID.randomUUID().toString(),
                        ruleId = "FILE_PATH_TRAVERSAL",
                        category = GuardThreatCategory.PATH_TRAVERSAL,
                        severity = GuardSeverity.HIGH,
                        title = "路径穿越攻击",
                        description = "工具 $toolName 的参数 '$key' 包含路径穿越序列: $value",
                        toolName = toolName,
                        paramName = key,
                        matchedValue = value,
                        matchedPattern = "../",
                        remediation = "禁止使用相对路径穿越，请使用绝对路径",
                        guardian = name
                    )
                )
            }

            for (ext in sensitiveExtensions) {
                if (value.endsWith(ext, ignoreCase = true)) {
                    val isOwnSandbox = context != null && value.contains(context.packageName)
                    if (!isOwnSandbox) {
                        findings.add(
                            GuardFinding(
                                id = UUID.randomUUID().toString(),
                                ruleId = "FILE_PATH_SENSITIVE_EXT",
                                category = GuardThreatCategory.PATH_TRAVERSAL,
                                severity = GuardSeverity.MEDIUM,
                                title = "访问敏感文件类型",
                                description = "工具 $toolName 尝试访问敏感文件类型 $ext: $value",
                                toolName = toolName,
                                paramName = key,
                                matchedValue = value,
                                remediation = "访问系统文件类型需要额外审批",
                                guardian = name
                            )
                        )
                    }
                }
            }

            val symlinks = setOf("/proc/self", "/proc/\$\$", "/dev/stdin", "/dev/stdout", "/dev/stderr")
            for (symlink in symlinks) {
                if (value.contains(symlink)) {
                    findings.add(
                        GuardFinding(
                            id = UUID.randomUUID().toString(),
                            ruleId = "FILE_PATH_SYMLINK",
                            category = GuardThreatCategory.PATH_TRAVERSAL,
                            severity = GuardSeverity.HIGH,
                            title = "通过符号链接绕过路径限制",
                            description = "工具 $toolName 的参数包含符号链接路径: $value",
                            toolName = toolName,
                            paramName = key,
                            matchedValue = value,
                            remediation = "禁止使用符号链接绕过路径限制",
                            guardian = name
                        )
                    )
                }
            }
        }
        return findings
    }
}

class AndroidPermissionGuardian(private val context: Context) : Guardian {
    override val name = "android_permission"
    override val priority = 20
    override val alwaysRun = true

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

    override suspend fun check(
        toolName: String,
        arguments: Map<String, Any>,
        guardContext: ToolGuardContext
    ): List<GuardFinding> {
        val findings = mutableListOf<GuardFinding>()
        val requiredPermission = permissionToolMap[toolName] ?: return findings

        val grantResult = ContextCompat.checkSelfPermission(context, requiredPermission)
        if (grantResult != PackageManager.PERMISSION_GRANTED) {
            findings.add(
                GuardFinding(
                    id = UUID.randomUUID().toString(),
                    ruleId = "ANDROID_PERMISSION_MISSING",
                    category = GuardThreatCategory.PERMISSION_DENIED,
                    severity = GuardSeverity.HIGH,
                    title = "缺少 Android 运行时权限",
                    description = "工具 $toolName 需要权限 $requiredPermission 但未授予",
                    toolName = toolName,
                    remediation = "请先在系统设置中授予该权限",
                    guardian = name
                )
            )
        }
        return findings
    }
}

class StorageAccessGuardian : Guardian {
    override val name = "storage_access"
    override val priority = 30
    override val alwaysRun = false

    private val restrictedDirs = setOf(
        "/data/data",
        "/system",
        "/vendor",
        "/storage/emulated/0/Android/data"
    )

    override suspend fun check(
        toolName: String,
        arguments: Map<String, Any>,
        context: ToolGuardContext
    ): List<GuardFinding> {
        val findings = mutableListOf<GuardFinding>()
        val pathKeys = listOf("path", "file_path", "filePath", "directory", "uri")

        for ((key, value) in arguments) {
            if (key in pathKeys && value is String) {
                for (restricted in restrictedDirs) {
                    if (value.startsWith(restricted) && !value.contains("com.lin.hippyagent")) {
                        findings.add(
                            GuardFinding(
                                id = UUID.randomUUID().toString(),
                                ruleId = "STORAGE_ACCESS_RESTRICTED",
                                category = GuardThreatCategory.PATH_TRAVERSAL,
                                severity = GuardSeverity.HIGH,
                                title = "访问受限存储区域",
                                description = "工具 $toolName 尝试访问受限目录 $value",
                                toolName = toolName,
                                paramName = key,
                                matchedValue = value,
                                remediation = "请使用 Storage Access Framework 访问外部存储",
                                guardian = name
                            )
                        )
                    }
                }
            }
        }
        return findings
    }
}

class NetworkPolicyGuardian : Guardian {
    override val name = "network_policy"
    override val priority = 40
    override val alwaysRun = false

    private val blockedDomains = setOf(
        "localhost",
        "127.0.0.1",
        "0.0.0.0"
    )

    private val sensitiveParams = setOf("url", "endpoint", "host", "domain", "api_key", "token")

    override suspend fun check(
        toolName: String,
        arguments: Map<String, Any>,
        context: ToolGuardContext
    ): List<GuardFinding> {
        val findings = mutableListOf<GuardFinding>()

        for ((key, value) in arguments) {
            if (key in sensitiveParams && value is String) {
                for (blocked in blockedDomains) {
                    if (value.contains(blocked, ignoreCase = true)) {
                        findings.add(
                            GuardFinding(
                                id = UUID.randomUUID().toString(),
                                ruleId = "NETWORK_LOCALHOST_ACCESS",
                                category = GuardThreatCategory.DATA_EXFILTRATION,
                                severity = GuardSeverity.MEDIUM,
                                title = "访问本地网络",
                                description = "工具 $toolName 尝试访问本地地址 $value",
                                toolName = toolName,
                                paramName = key,
                                matchedValue = value,
                                remediation = "端侧 Agent 禁止访问本地网络服务",
                                guardian = name
                            )
                        )
                    }
                }

                if (key in setOf("api_key", "token") && value.length > 8) {
                    findings.add(
                        GuardFinding(
                            id = UUID.randomUUID().toString(),
                            ruleId = "NETWORK_CREDENTIAL_EXPOSURE",
                            category = GuardThreatCategory.DATA_EXFILTRATION,
                            severity = GuardSeverity.HIGH,
                            title = "网络请求可能泄露凭证",
                            description = "工具 $toolName 的参数 '$key' 包含疑似密钥",
                            toolName = toolName,
                            paramName = key,
                            matchedPattern = "${key}=***",
                            remediation = "请使用 SecureStorage 管理密钥，不要在网络请求参数中传递",
                            guardian = name
                        )
                    )
                }
            }
        }
        return findings
    }
}

