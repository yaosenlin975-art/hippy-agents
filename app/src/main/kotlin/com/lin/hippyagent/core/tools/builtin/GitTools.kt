package com.lin.hippyagent.core.tools.builtin

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

private val shellMetaRegex = Regex("[;&|`\$(){}!<>\\n\\r]")

/** 清理 shell 参数，防止命令注入 */
private fun sanitizeShellArg(arg: String): String {
    // 移除危险的 shell 元字符
    return arg.replace(shellMetaRegex, "")
}

class GitStatusTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "git_status",
        description = "View Git repository status",
        parameters = emptyMap(),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }
        val (exitCode, output) = linuxManager.exec("git status --porcelain")
        return if (exitCode == 0) ToolResult(callId, true, output = output)
        else ToolResult(callId, false, error = output)
    }
}

class GitAddTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "git_add",
        description = "Stage files",
        parameters = mapOf(
            "files" to ToolParameter(
                name = "files",
                type = "string",
                description = "File paths to stage, space-separated",
                required = true
            )
        ),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val files = sanitizeShellArg(getRequiredArgument(arguments, "files"))
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }
        val (exitCode, output) = linuxManager.exec("git add $files")
        return if (exitCode == 0) ToolResult(callId, true, output = "Files staged successfully")
        else ToolResult(callId, false, error = output)
    }
}

class GitCommitTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "git_commit",
        description = "Commit changes",
        parameters = mapOf(
            "message" to ToolParameter(
                name = "message",
                type = "string",
                description = "Commit message",
                required = true
            )
        ),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val message = sanitizeShellArg(getRequiredArgument(arguments, "message"))
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }
        val escapedMessage = message.replace("\"", "\\\"")
        val (exitCode, output) = linuxManager.exec("git commit -m \"$escapedMessage\"")
        return if (exitCode == 0) ToolResult(callId, true, output = output)
        else ToolResult(callId, false, error = output)
    }
}

class GitPushTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "git_push",
        description = "Push to remote repository",
        parameters = emptyMap(),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }
        val (exitCode, output) = linuxManager.exec("git push")
        return if (exitCode == 0) ToolResult(callId, true, output = output)
        else ToolResult(callId, false, error = output)
    }
}

class GitPullTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "git_pull",
        description = "Pull from remote repository",
        parameters = emptyMap(),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }
        val (exitCode, output) = linuxManager.exec("git pull")
        return if (exitCode == 0) ToolResult(callId, true, output = output)
        else ToolResult(callId, false, error = output)
    }
}

class GitBranchTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "git_branch",
        description = "Branch operations",
        parameters = mapOf(
            "action" to ToolParameter(
                name = "action",
                type = "string",
                description = "Action: list/create/switch/delete",
                required = true
            ),
            "branch_name" to ToolParameter(
                name = "branch_name",
                type = "string",
                description = "Branch name",
                required = false
            )
        ),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val action = sanitizeShellArg(getRequiredArgument(arguments, "action"))
        val branchName = getOptionalArgument(arguments, "branch_name")?.let { sanitizeShellArg(it) }
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }

        val command = when (action) {
            "list" -> "git branch -a"
            "create" -> "git branch ${branchName ?: return ToolResult(callId, false, error = "branch_name required for create")}"
            "switch" -> "git checkout ${branchName ?: return ToolResult(callId, false, error = "branch_name required for switch")}"
            "delete" -> "git branch -d ${branchName ?: return ToolResult(callId, false, error = "branch_name required for delete")}"
            else -> return ToolResult(callId, false, error = "Unknown action: $action")
        }

        val (exitCode, output) = linuxManager.exec(command)
        return if (exitCode == 0) ToolResult(callId, true, output = output)
        else ToolResult(callId, false, error = output)
    }
}

class GitLogTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "git_log",
        description = "View commit history",
        parameters = mapOf(
            "limit" to ToolParameter(
                name = "limit",
                type = "integer",
                description = "Number of entries to show",
                required = false,
                defaultValue = 10
            )
        ),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val limit = (arguments["limit"] as? Number)?.toInt() ?: 10
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }
        val (exitCode, output) = linuxManager.exec("git log --oneline -$limit")
        return if (exitCode == 0) ToolResult(callId, true, output = output)
        else ToolResult(callId, false, error = output)
    }
}

class GitDiffTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "git_diff",
        description = "View changes",
        parameters = mapOf(
            "target" to ToolParameter(
                name = "target",
                type = "string",
                description = "Diff target (e.g., HEAD, branch name)",
                required = false,
                defaultValue = "HEAD"
            )
        ),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val target = getOptionalArgument(arguments, "target", "HEAD")!!
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }
        val (exitCode, output) = linuxManager.exec("git diff $target")
        return if (exitCode == 0) ToolResult(callId, true, output = output)
        else ToolResult(callId, false, error = output)
    }
}

class GitCloneTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "git_clone",
        description = "Clone a repository",
        parameters = mapOf(
            "url" to ToolParameter(
                name = "url",
                type = "string",
                description = "Repository URL",
                required = true
            ),
            "path" to ToolParameter(
                name = "path",
                type = "string",
                description = "Target path",
                required = false
            )
        ),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val url = sanitizeShellArg(getRequiredArgument(arguments, "url"))
        val path = getOptionalArgument(arguments, "path")?.let { sanitizeShellArg(it) }
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }
        val command = if (path != null) "git clone $url $path" else "git clone $url"
        val (exitCode, output) = linuxManager.exec(command)
        return if (exitCode == 0) ToolResult(callId, true, output = "Repository cloned successfully")
        else ToolResult(callId, false, error = output)
    }
}

