package com.lin.hippyagent.core.linux.security

import com.lin.hippyagent.core.security.PatternLibrary
import timber.log.Timber

/**
 * 命令沙箱：验证和过滤命令，防止危险操作
 */
class CommandSandbox {
    companion object {
        // 危险命令黑名单
        private val DANGEROUS_COMMANDS: List<Regex> =
            PatternLibrary.DANGEROUS_COMMAND_PATTERNS + PatternLibrary.SANDBOX_EXTRA_DANGEROUS

        // 危险参数模式
        private val DANGEROUS_PATTERNS: List<Regex> = PatternLibrary.SANDBOX_DANGEROUS_PARAMS

        // 允许的命令白名单（基础命令）
        private val ALLOWED_COMMANDS = setOf(
            "ls", "cd", "pwd", "echo", "cat", "head", "tail", "grep", "find",
            "cp", "mv", "rm", "mkdir", "rmdir", "touch", "chmod", "chown",
            "ps", "top", "kill", "killall",
            "df", "du", "free", "uptime",
            "whoami", "id", "uname", "hostname",
            "date", "cal",
            "apt", "apt-get", "dpkg",
            "git", "curl", "wget",
            "python", "python3", "pip", "pip3",
            "node", "npm", "npx",
            "java", "javac",
            "gcc", "g++", "make",
            "ssh", "scp", "rsync",
            "tar", "gzip", "gunzip", "zip", "unzip",
            "vim", "nano", "emacs",
            "bash", "sh", "zsh"
        )
    }

    /**
     * 验证命令安全性
     * @return ValidationResult
     */
    fun validate(command: String): ValidationResult {
        val findings = mutableListOf<SecurityFinding>()

        // 检查危险命令
        for (pattern in DANGEROUS_COMMANDS) {
            if (pattern.containsMatchIn(command)) {
                findings.add(
                    SecurityFinding(
                        type = FindingType.DANGEROUS_COMMAND,
                        severity = Severity.CRITICAL,
                        message = "Dangerous command detected: ${pattern.pattern}",
                        command = command
                    )
                )
            }
        }

        // 检查危险参数
        for (pattern in DANGEROUS_PATTERNS) {
            if (pattern.containsMatchIn(command)) {
                findings.add(
                    SecurityFinding(
                        type = FindingType.DANGEROUS_PATTERN,
                        severity = Severity.HIGH,
                        message = "Dangerous pattern detected: ${pattern.pattern}",
                        command = command
                    )
                )
            }
        }

        // 检查命令长度
        if (command.length > 10000) {
            findings.add(
                SecurityFinding(
                    type = FindingType.COMMAND_TOO_LONG,
                    severity = Severity.MEDIUM,
                    message = "Command exceeds maximum length (10000 chars)",
                    command = command
                )
            )
        }

        // 检查嵌套 Shell
        if (command.count { it == '(' } != command.count { it == ')' }) {
            findings.add(
                SecurityFinding(
                    type = FindingType.SYNTAX_ERROR,
                    severity = Severity.LOW,
                    message = "Unbalanced parentheses detected",
                    command = command
                )
            )
        }

        return ValidationResult(
            isSafe = findings.none { it.severity == Severity.CRITICAL || it.severity == Severity.HIGH },
            findings = findings
        )
    }

    /**
     * 清理命令
     * @return 清理后的命令
     */
    fun sanitize(command: String): String {
        var sanitized = command

        // 移除危险字符
        sanitized = sanitized.replace(Regex("[;&|`\$(){}!<>\\n\\r]"), "")

        // 移除路径遍历
        sanitized = sanitized.replace(Regex("\\.\\.\\/"), "")

        // 移除多余空格
        sanitized = sanitized.replace(Regex("\\s+"), " ").trim()

        return sanitized
    }

    /**
     * 检查命令是否在白名单中
     */
    fun isCommandAllowed(command: String): Boolean {
        val baseCommand = command.split("\\s+".toRegex()).firstOrNull() ?: return false
        return ALLOWED_COMMANDS.contains(baseCommand)
    }
}

/**
 * 安全发现类型
 */
enum class FindingType {
    DANGEROUS_COMMAND,
    DANGEROUS_PATTERN,
    COMMAND_TOO_LONG,
    SYNTAX_ERROR,
    UNALLOWED_COMMAND
}

/**
 * 严重程度
 */
enum class Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * 安全发现
 */
data class SecurityFinding(
    val type: FindingType,
    val severity: Severity,
    val message: String,
    val command: String
)

/**
 * 验证结果
 */
data class ValidationResult(
    val isSafe: Boolean,
    val findings: List<SecurityFinding>
)

