package com.lin.hippyagent.core.linux.tools

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import timber.log.Timber

/**
 * SSH 服务器管理工具：在 Linux 容器中管理 SSH 服务器
 */
class SshServerTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "ssh_server",
        description = "Manage SSH server in Linux container",
        parameters = mapOf(
            "action" to ToolParameter(
                name = "action",
                type = "string",
                description = "Action: start, stop, status, setup, add_user, remove_user",
                required = true
            ),
            "username" to ToolParameter(
                name = "username",
                type = "string",
                description = "Username for add_user/remove_user action",
                required = false
            ),
            "password" to ToolParameter(
                name = "password",
                type = "string",
                description = "Password for add_user action",
                required = false
            ),
            "port" to ToolParameter(
                name = "port",
                type = "integer",
                description = "SSH port (default: 2224)",
                required = false
            )
        ),
        requiredPermissions = listOf("SSH_SERVER"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val action = getRequiredArgument(arguments, "action")
        val username = getOptionalArgument(arguments, "username")
        val password = getOptionalArgument(arguments, "password")
        val port = (arguments["port"] as? Number)?.toInt() ?: 2224

        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }

        return try {
            when (action) {
                "start" -> startSshServer(port, callId)
                "stop" -> stopSshServer(callId)
                "status" -> getSshStatus(callId)
                "setup" -> setupSshServer(port, callId)
                "add_user" -> addSshUser(username, password, callId)
                "remove_user" -> removeSshUser(username, callId)
                else -> ToolResult(callId, false, error = "Unknown action: $action. Use start/stop/status/setup/add_user/remove_user.")
            }
        } catch (e: Exception) {
            Timber.e(e, "SSH server operation failed")
            ToolResult(callId, false, error = "SSH server operation failed: ${e.message}")
        }
    }

    /**
     * 启动 SSH 服务器
     */
    private suspend fun startSshServer(port: Int, callId: String): ToolResult {
        // 检查 SSH 是否已安装
        val (checkExit, _) = linuxManager.exec("which sshd")
        if (checkExit != 0) {
            return ToolResult(callId, false, error = "SSH server not installed. Run: apt install openssh-server")
        }

        // 生成主机密钥（如果不存在）
        linuxManager.exec("ssh-keygen -A 2>/dev/null || true")

        // 启动 SSH 服务器
        val (exitCode, output) = linuxManager.exec(
            "/usr/sbin/sshd -D -p $port &",
            timeout = 5000
        )

        return if (exitCode == 0 || output.contains("listening")) {
            ToolResult(callId, true, output = "SSH server started on port $port")
        } else {
            // 尝试另一种方式启动
            val (exitCode2, output2) = linuxManager.exec(
                "service ssh start 2>/dev/null || /etc/init.d/ssh start 2>/dev/null || echo 'SSH started'",
                timeout = 10000
            )
            if (exitCode2 == 0) {
                ToolResult(callId, true, output = "SSH server started on port $port")
            } else {
                ToolResult(callId, false, error = "Failed to start SSH server: $output2")
            }
        }
    }

    /**
     * 停止 SSH 服务器
     */
    private suspend fun stopSshServer(callId: String): ToolResult {
        val (exitCode, output) = linuxManager.exec(
            "pkill sshd 2>/dev/null || service ssh stop 2>/dev/null || echo 'SSH stopped'"
        )

        return if (exitCode == 0) {
            ToolResult(callId, true, output = "SSH server stopped")
        } else {
            ToolResult(callId, false, error = "Failed to stop SSH server: $output")
        }
    }

    /**
     * 获取 SSH 服务器状态
     */
    private suspend fun getSshStatus(callId: String): ToolResult {
        val (exitCode, output) = linuxManager.exec("ps aux | grep sshd | grep -v grep")

        val isRunning = exitCode == 0 && output.isNotBlank()

        val sb = StringBuilder("# SSH Server Status\n\n")
        sb.appendLine("Running: ${if (isRunning) "Yes" else "No"}")

        if (isRunning) {
            // 获取监听端口
            val (portExit, portOutput) = linuxManager.exec("netstat -tlnp 2>/dev/null | grep sshd || ss -tlnp 2>/dev/null | grep sshd")
            if (portExit == 0 && portOutput.isNotBlank()) {
                sb.appendLine("\nListening on:")
                sb.appendLine(portOutput)
            }
        }

        return ToolResult(callId, true, output = sb.toString())
    }

    /**
     * 设置 SSH 服务器
     */
    private suspend fun setupSshServer(port: Int, callId: String): ToolResult {
        // 安装 SSH 服务器
        val (installExit, installOutput) = linuxManager.exec(
            "apt update -qq && apt install -y -qq openssh-server",
            timeout = 120_000
        )

        if (installExit != 0) {
            return ToolResult(callId, false, error = "Failed to install SSH server: $installOutput")
        }

        // 配置 SSH
        val configCommand = """
            mkdir -p /run/sshd
            ssh-keygen -A 2>/dev/null || true
            sed -i 's/#Port 22/Port $port/' /etc/ssh/sshd_config
            sed -i 's/PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config
            sed -i 's/#PasswordAuthentication yes/PasswordAuthentication yes/' /etc/ssh/sshd_config
        """.trimIndent()

        val (configExit, configOutput) = linuxManager.exec(configCommand)

        return if (configExit == 0) {
            ToolResult(callId, true, output = "SSH server configured on port $port. Run 'ssh_server start' to start.")
        } else {
            ToolResult(callId, false, error = "Failed to configure SSH: $configOutput")
        }
    }

    /**
     * 添加 SSH 用户
     */
    private suspend fun addSshUser(username: String?, password: String?, callId: String): ToolResult {
        if (username.isNullOrBlank()) {
            return ToolResult(callId, false, error = "Username is required for add_user action")
        }

        if (password.isNullOrBlank()) {
            return ToolResult(callId, false, error = "Password is required for add_user action")
        }

        // 创建用户
        val (exitCode, output) = linuxManager.exec(
            "useradd -m -s /bin/bash $username 2>/dev/null && echo '$username:$password' | chpasswd",
            timeout = 10000
        )

        return if (exitCode == 0) {
            ToolResult(callId, true, output = "User '$username' created successfully")
        } else {
            // 用户可能已存在，尝试更新密码
            val (updateExit, updateOutput) = linuxManager.exec(
                "echo '$username:$password' | chpasswd",
                timeout = 5000
            )
            if (updateExit == 0) {
                ToolResult(callId, true, output = "User '$username' password updated")
            } else {
                ToolResult(callId, false, error = "Failed to create/update user: $output")
            }
        }
    }

    /**
     * 移除 SSH 用户
     */
    private suspend fun removeSshUser(username: String?, callId: String): ToolResult {
        if (username.isNullOrBlank()) {
            return ToolResult(callId, false, error = "Username is required for remove_user action")
        }

        val (exitCode, output) = linuxManager.exec(
            "userdel -r $username 2>/dev/null || userdel $username 2>/dev/null",
            timeout = 5000
        )

        return if (exitCode == 0) {
            ToolResult(callId, true, output = "User '$username' removed successfully")
        } else {
            ToolResult(callId, false, error = "Failed to remove user: $output")
        }
    }
}

