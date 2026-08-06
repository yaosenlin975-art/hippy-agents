package com.lin.hippyagent.core.tools

import com.lin.hippyagent.core.security.GuardSeverity
import com.lin.hippyagent.core.security.GuardThreatCategory
import com.lin.hippyagent.core.security.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGuardianTest {

    private val guardian = ToolGuardian(context = null)

    private fun check(tool: String, args: Map<String, Any>, workspace: List<String> = emptyList()) =
        runBlocking { guardian.checkToolCall(tool, args, workspace) }

    // ═══════════ safe calls ═══════════

    @Test
    fun safeToolCall_passes() {
        val result = check("list_directory", mapOf("path" to "/sdcard/Download"))
        assertTrue(result.passed)
        assertEquals(RiskLevel.SAFE, result.riskLevel)
    }

    @Test
    fun readFileInWorkspace_passes() {
        val result = check("read_file", mapOf("path" to "/data/workspace/a.txt"), workspace = listOf("/data/workspace"))
        assertTrue(result.passed)
    }

    // ═══════════ shell risk ═══════════

    @Test
    fun executeShell_alwaysMedium() {
        val result = check("execute_shell", mapOf("cmd" to "ls"))
        assertTrue(result.passed)
        assertEquals(RiskLevel.MEDIUM, result.riskLevel)
        assertTrue(result.findings.any { it.ruleId == "TOOL_SHELL_EXECUTE" })
    }

    @Test
    fun shellEvasion_detected_criticalAndBlocked() {
        val result = check("execute_shell", mapOf("cmd" to "echo \\x2fetc\\x2fpasswd"))
        assertFalse(result.passed)
        assertEquals(RiskLevel.CRITICAL, result.riskLevel)
        assertTrue(result.findings.any { it.ruleId == "SHELL_EVASION_DETECTED" })
    }

    @Test
    fun shellObfuscation_detected_high() {
        val result = check("execute_shell", mapOf("command" to "echo ''"))
        assertTrue(result.findings.any { it.ruleId == "SHELL_OBFUSCATION_DETECTED" })
        assertEquals(GuardSeverity.HIGH, result.findings.first { it.ruleId == "SHELL_OBFUSCATION_DETECTED" }.severity)
    }

    // ═══════════ delete / write operations ═══════════

    @Test
    fun deleteOperation_outsideWorkspace_highRisk() {
        val result = check("delete_file", mapOf("path" to "/sdcard/a.txt"))
        assertFalse(result.passed)
        assertEquals(RiskLevel.HIGH, result.riskLevel)
        assertTrue(result.findings.any { it.ruleId == "TOOL_DELETE_OP" && it.severity == GuardSeverity.HIGH })
    }

    @Test
    fun deleteOperation_insideWorkspace_mediumRisk() {
        val result = check("delete_file", mapOf("path" to "/data/workspace/a.txt"), workspace = listOf("/data/workspace"))
        assertEquals(RiskLevel.MEDIUM, result.riskLevel)
        assertTrue(result.findings.any { it.ruleId == "TOOL_DELETE_OP" && it.severity == GuardSeverity.MEDIUM })
    }

    @Test
    fun writeOperation_outsideWorkspace_mediumRisk() {
        val result = check("write_file", mapOf("path" to "/tmp/a.txt"))
        assertEquals(RiskLevel.MEDIUM, result.riskLevel)
        assertTrue(result.findings.any { it.ruleId == "TOOL_WRITE_OP" })
    }

    // ═══════════ path traversal ═══════════

    @Test
    fun pathTraversal_flagged_high() {
        val result = check("read_file", mapOf("path" to "/sdcard/../etc/passwd"))
        assertTrue(result.findings.any { it.ruleId == "FILE_PATH_TRAVERSAL" })
        assertEquals(GuardSeverity.HIGH, result.findings.first { it.ruleId == "FILE_PATH_TRAVERSAL" }.severity)
    }

    @Test
    fun criticalPath_blocked() {
        val result = check("read_file", mapOf("path" to "/system/bin/sh"))
        assertFalse(result.passed)
        assertTrue(result.findings.any { it.ruleId == "FILE_PATH_CRITICAL" })
        assertEquals(GuardSeverity.CRITICAL, result.findings.first { it.ruleId == "FILE_PATH_CRITICAL" }.severity)
    }

    @Test
    fun sensitiveExtension_mediumRisk() {
        val result = check("read_file", mapOf("path" to "/sdcard/libnative.so"))
        assertTrue(result.findings.any { it.ruleId == "FILE_PATH_SENSITIVE_EXT" })
        assertEquals(GuardSeverity.MEDIUM, result.findings.first { it.ruleId == "FILE_PATH_SENSITIVE_EXT" }.severity)
    }

    @Test
    fun symlinkPath_highRisk() {
        val result = check("read_file", mapOf("path" to "/proc/self/maps"))
        assertTrue(result.findings.any { it.ruleId == "FILE_PATH_SYMLINK" })
        assertEquals(GuardSeverity.HIGH, result.findings.first { it.ruleId == "FILE_PATH_SYMLINK" }.severity)
    }

    @Test
    fun workspacePath_exemptFromCriticalCheck() {
        // /system 开头但在 workspace 内 → 豁免
        val result = check("read_file", mapOf("path" to "/data/workspace/system-backup.txt"), workspace = listOf("/data/workspace"))
        assertFalse(result.findings.any { it.ruleId == "FILE_PATH_CRITICAL" })
    }

    // ═══════════ storage access ═══════════

    @Test
    fun storageRestrictedDir_highRisk() {
        val result = check("read_file", mapOf("path" to "/data/data/com.other.app/db"))
        assertTrue(result.findings.any { it.ruleId == "STORAGE_ACCESS_RESTRICTED" })
        assertEquals(GuardSeverity.HIGH, result.findings.first { it.ruleId == "STORAGE_ACCESS_RESTRICTED" }.severity)
    }

    // ═══════════ network policy ═══════════

    @Test
    fun localhostNetworkAccess_mediumRisk() {
        val result = check("web_fetch", mapOf("url" to "http://localhost:8080/api"))
        assertTrue(result.findings.any { it.ruleId == "NETWORK_LOCALHOST_ACCESS" })
        assertEquals(GuardSeverity.MEDIUM, result.findings.first { it.ruleId == "NETWORK_LOCALHOST_ACCESS" }.severity)
    }

    @Test
    fun credentialExposure_highRisk() {
        val result = check("web_fetch", mapOf("url" to "https://api.example.com", "api_key" to "sk-1234567890"))
        assertTrue(result.findings.any { it.ruleId == "NETWORK_CREDENTIAL_EXPOSURE" })
        assertEquals(GuardSeverity.HIGH, result.findings.first { it.ruleId == "NETWORK_CREDENTIAL_EXPOSURE" }.severity)
    }

    @Test
    fun shortToken_notFlagged() {
        val result = check("web_fetch", mapOf("url" to "https://api.example.com", "token" to "short"))
        assertFalse(result.findings.any { it.ruleId == "NETWORK_CREDENTIAL_EXPOSURE" })
    }

    // ═══════════ accessibility ═══════════

    @Test
    fun a11yBlockedPackage_critical() {
        val result = check("screen_interact", mapOf("action" to "launch_app", "target" to "com.android.providers.settings"))
        assertFalse(result.passed)
        assertTrue(result.findings.any { it.ruleId == "A11Y_BLOCKED_PACKAGE" })
        assertEquals(GuardSeverity.CRITICAL, result.findings.first { it.ruleId == "A11Y_BLOCKED_PACKAGE" }.severity)
    }

    @Test
    fun a11ySensitiveInput_highRisk() {
        val result = check("screen_interact", mapOf("action" to "input_text", "value" to "password 123456"))
        assertTrue(result.findings.any { it.ruleId == "A11Y_SENSITIVE_INPUT" })
        assertEquals(GuardSeverity.HIGH, result.findings.first { it.ruleId == "A11Y_SENSITIVE_INPUT" }.severity)
    }

    @Test
    fun a11yInputText_mediumRisk() {
        val result = check("screen_interact", mapOf("action" to "input_text", "value" to "普通文本"))
        assertTrue(result.findings.any { it.ruleId == "A11Y_INPUT_ACTION" })
    }

    // ═══════════ dangerous patterns ═══════════

    @Test
    fun dangerousCommandPattern_critical() {
        val result = check("execute_shell", mapOf("cmd" to "rm -rf /"))
        assertFalse(result.passed)
        assertTrue(result.findings.any { it.ruleId == "DANGEROUS_PATTERN" })
        assertEquals(GuardSeverity.CRITICAL, result.findings.first { it.ruleId == "DANGEROUS_PATTERN" }.severity)
    }

    @Test
    fun dangerousPath_highRisk() {
        val result = check("execute_shell", mapOf("cmd" to "cat /etc/passwd"))
        assertTrue(result.findings.any { it.ruleId == "DANGEROUS_PATH" })
        assertEquals(GuardSeverity.HIGH, result.findings.first { it.ruleId == "DANGEROUS_PATH" }.severity)
    }

    @Test
    fun inputTooLong_mediumRisk() {
        val long = "a".repeat(10_001)
        val result = check("some_tool", mapOf("text" to long))
        assertTrue(result.findings.any { it.ruleId == "INPUT_TOO_LONG" })
        assertEquals(GuardSeverity.MEDIUM, result.findings.first { it.ruleId == "INPUT_TOO_LONG" }.severity)
    }

    @Test
    fun callIdExcluded_fromChecks() {
        val result = check("execute_shell", mapOf("callId" to "abc123", "cmd" to "ls"))
        // callId 不触发 DANGEROUS_PATTERN, 但 cmd 是 shell 参数仍正常检测
        assertTrue(result.findings.none { it.ruleId == "DANGEROUS_PATTERN" && it.paramName == "callId" })
    }

    // ═══════════ isCommandSafe ═══════════

    @Test
    fun isCommandSafe_rmRf_critical() {
        val result = guardian.isCommandSafe("rm -rf /data")
        assertFalse(result.passed)
        assertEquals(RiskLevel.CRITICAL, result.riskLevel)
    }

    @Test
    fun isCommandSafe_plainCommand_safe() {
        val result = guardian.isCommandSafe("ls -la /sdcard")
        assertTrue(result.passed)
        assertEquals(RiskLevel.SAFE, result.riskLevel)
    }

    @Test
    fun isCommandSafe_dangerousPath_high() {
        val result = guardian.isCommandSafe("cat /etc/shadow")
        assertEquals(RiskLevel.HIGH, result.riskLevel)
        assertTrue(result.findings.any { it.ruleId == "DANGEROUS_PATH" })
    }
}
