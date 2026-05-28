package com.lin.hippyagent.core.linux.tools

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import timber.log.Timber

/**
 * Install Package 工具：使用 apt 安装软件包
 */
class InstallPackageTool(
    private val linuxManager: LinuxManager
) : Tool() {

    override val definition: ToolDefinition = ToolDefinition(
        name = "install_package",
        description = "Install a package using apt package manager in the Linux environment",
        parameters = mapOf(
            "package_name" to ToolParameter(
                name = "package_name",
                type = "string",
                description = "The name of the package to install",
                required = true
            ),
            "update_first" to ToolParameter(
                name = "update_first",
                type = "boolean",
                description = "Whether to update apt before installing (default: true)",
                required = false,
                defaultValue = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val packageName = getRequiredArgument(arguments, "package_name")
        val updateFirst = getOptionalArgument(arguments, "update_first", "true")?.toBooleanStrictOrNull() ?: true

        // 检查 Linux 环境是否就绪
        if (!linuxManager.isReady.value) {
            return ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = false,
                error = "Linux environment not ready. Please wait for initialization to complete."
            )
        }

        // 安全检查包名
        val sanitizedPackage = sanitizePackageName(packageName)
        if (sanitizedPackage.isBlank()) {
            return ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = false,
                error = "Invalid package name: $packageName"
            )
        }

        // 构建命令
        val commands = mutableListOf<String>()
        
        if (updateFirst) {
            commands.add("apt update -qq")
        }
        
        commands.add("apt install -y -qq $sanitizedPackage")
        
        val command = commands.joinToString(" && ")

        // 执行命令
        Timber.d("Installing package: $sanitizedPackage")
        val (exitCode, output) = linuxManager.exec(command, timeout = 120_000) // 2分钟超时

        return if (exitCode == 0) {
            ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = true,
                output = "Package '$sanitizedPackage' installed successfully.\n$output",
                forLLM = "Package '$sanitizedPackage' installed successfully."
            )
        } else {
            ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = false,
                output = output,
                error = "Failed to install package '$sanitizedPackage':\n$output"
            )
        }
    }

    /**
     * 清理包名，防止命令注入
     */
    private fun sanitizePackageName(packageName: String): String {
        // 只允许字母、数字、连字符、下划线和点
        return packageName.replace(Regex("[^a-zA-Z0-9\\-_.]"), "")
    }
}

