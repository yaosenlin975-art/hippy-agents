package com.lin.hippyagent.core.linux.tools

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.linux.security.CommandSandbox
import com.lin.hippyagent.core.linux.security.ResourceLimiter
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import timber.log.Timber

/**
 * Execute Bash 工具：在 Linux 环境中执行 bash 命令
 */
class ExecuteBashTool(
    private val linuxManager: LinuxManager,
    private val commandSandbox: CommandSandbox = CommandSandbox(),
    private val resourceLimiter: ResourceLimiter = ResourceLimiter(),
    private val defaultTimeoutMs: Long = 30000,
    private val shellTimeoutMs: Long = defaultTimeoutMs
) : Tool() {

    override val definition: ToolDefinition = ToolDefinition(
        name = "execute_bash",
        description = "Execute a bash command in the Linux environment (Ubuntu 24.04 arm64)",
        parameters = mapOf(
            "command" to ToolParameter(
                name = "command",
                type = "string",
                description = "The bash command to execute",
                required = true
            ),
            "timeout" to ToolParameter(
                name = "timeout",
                type = "integer",
                description = "Timeout in milliseconds (default: ${shellTimeoutMs}ms)",
                required = false,
                defaultValue = shellTimeoutMs
            ),
            "safe_mode" to ToolParameter(
                name = "safe_mode",
                type = "boolean",
                description = "Enable safe mode with command validation (default: true)",
                required = false,
                defaultValue = "true"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val command = getRequiredArgument(arguments, "command")
        val timeout = getOptionalArgument(arguments, "timeout", shellTimeoutMs.toString())?.toLongOrNull() ?: shellTimeoutMs
        val safeMode = getOptionalArgument(arguments, "safe_mode", "true")?.toBooleanStrictOrNull() ?: true

        // 检查 Linux 环境是否就绪
        if (!linuxManager.isReady.value) {
            return ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = false,
                error = "Linux environment not ready. Please wait for initialization to complete."
            )
        }

        // 安全检查
        if (safeMode) {
            val validationResult = commandSandbox.validate(command)
            if (!validationResult.isSafe) {
                val criticalFindings = validationResult.findings.filter {
                    it.severity == com.lin.hippyagent.core.linux.security.Severity.CRITICAL ||
                    it.severity == com.lin.hippyagent.core.linux.security.Severity.HIGH
                }
                return ToolResult(
                    callId = com.lin.hippyagent.core.pool.FastId.next(),
                    success = false,
                    error = "Command blocked by security policy:\n${criticalFindings.joinToString("\n") { "- ${it.message}" }}"
                )
            }
        }

        // 资源检查
        val resourceCheck = resourceLimiter.canStartProcess()
        if (!resourceCheck.allowed) {
            return ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = false,
                error = "Resource limit exceeded: ${resourceCheck.reason}"
            )
        }

        // 记录进程启动
        val processId = "bash_${System.currentTimeMillis()}"
        resourceLimiter.recordProcessStart(processId, command)

        // 执行命令
        Timber.d("Executing bash command: $command")
        val startTime = System.currentTimeMillis()
        val (exitCode, output) = linuxManager.exec(command, timeout)
        val executionTime = System.currentTimeMillis() - startTime

        // 记录资源使用
        resourceLimiter.recordProcessEnd(processId)
        resourceLimiter.recordCpuTime(executionTime)

        // 检测权限请求标记（PRootEngine 返回 PERMISSION_NEEDED: command）
        if (output.startsWith("PERMISSION_NEEDED:")) {
            val needsApprovalCommand = output.removePrefix("PERMISSION_NEEDED:").trim()
            return ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = false,
                output = "命令需要用户授权：$needsApprovalCommand",
                forLLM = "⚠️ 命令「$needsApprovalCommand」需要用户授权才能执行。请等待用户确认后重试。",
                needsPermissionApproval = true,
                permissionCommand = needsApprovalCommand
            )
        }

        // 检测被阻止的命令
        if (output.startsWith("Command blocked:")) {
            return ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = false,
                output = output,
                error = output
            )
        }

        return if (exitCode == 0) {
            ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = true,
                output = output,
                forLLM = output
            )
        } else {
            ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = false,
                output = output,
                error = "Command failed with exit code $exitCode:\n$output"
            )
        }
    }
}

