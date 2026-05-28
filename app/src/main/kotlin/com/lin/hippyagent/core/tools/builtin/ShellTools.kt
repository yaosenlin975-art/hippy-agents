package com.lin.hippyagent.core.tools.builtin

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

class ExecuteShellTool(
    private val linuxManager: LinuxManager,
    private val defaultTimeoutMs: Long = 30000
) : Tool() {
    override val definition = ToolDefinition(
        name = "execute_shell",
        description = "Execute a shell command in the Linux environment",
        parameters = mapOf(
            "command" to ToolParameter(
                name = "command",
                type = "string",
                description = "The shell command to execute",
                required = true
            ),
            "timeout" to ToolParameter(
                name = "timeout",
                type = "integer",
                description = "Timeout in milliseconds (default: ${defaultTimeoutMs}ms)",
                required = false,
                defaultValue = defaultTimeoutMs
            )
        ),
        requiredPermissions = listOf("SHELL_EXECUTE"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val command = getRequiredArgument(arguments, "command")
        val callId = arguments["callId"] as? String ?: ""
        val timeout = getOptionalArgument(arguments, "timeout", defaultTimeoutMs.toString())?.toLongOrNull() ?: defaultTimeoutMs

        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }

        val (exitCode, output) = linuxManager.exec(command, timeout)
        return if (exitCode == 0) {
            ToolResult(callId, true, output)
        } else {
            ToolResult(callId, false, error = "Command failed with exit code $exitCode:\n$output")
        }
    }
}

class GetWorkingDirectoryTool : Tool() {
    override val definition = ToolDefinition(
        name = "get_working_directory",
        description = "Get current working directory",
        parameters = emptyMap()
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val cwd = System.getProperty("user.dir") ?: "/"
        return ToolResult(callId, true, cwd)
    }
}

class GetEnvironmentTool(
    private val configStorage: com.lin.hippyagent.core.storage.ConfigStorage? = null
) : Tool() {
    private companion object {
        const val PREFIX = "env_var_"
    }

    override val definition = ToolDefinition(
        name = "get_environment",
        description = "List or get environment variables. Call without parameters to list ALL environment variables (both system and user-defined). Call with a name to get a specific variable's value.",
        parameters = mapOf(
            "name" to ToolParameter(
                name = "name",
                type = "string",
                description = "Environment variable name. Leave empty/omit to list ALL variables.",
                required = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val name = getOptionalArgument(arguments, "name")

        if (name != null) {
            val customValue = configStorage?.getString(PREFIX + name)
            if (!customValue.isNullOrBlank()) {
                return ToolResult(callId, true, customValue)
            }
            val value = System.getenv(name) ?: "Environment variable not found: $name"
            return ToolResult(callId, true, value)
        }

        val systemEnv = System.getenv().entries.associate { it.key to it.value }
        val customEnv = configStorage?.getAllKeys()
            ?.filter { it.startsWith(PREFIX) }
            ?.associate { it.removePrefix(PREFIX) to configStorage.getString(it) }
            ?: emptyMap()
        val merged = systemEnv + customEnv
        val env = merged.entries.joinToString("\n") { "${it.key}=${it.value}" }
        return ToolResult(callId, true, env)
    }
}

class SetEnvironmentTool(
    private val configStorage: com.lin.hippyagent.core.storage.ConfigStorage
) : Tool() {
    private companion object {
        const val PREFIX = "env_var_"
    }

    override val definition = ToolDefinition(
        name = "set_environment",
        description = "Set a custom environment variable",
        parameters = mapOf(
            "name" to ToolParameter(
                name = "name",
                type = "string",
                description = "Variable name",
                required = true
            ),
            "value" to ToolParameter(
                name = "value",
                type = "string",
                description = "Variable value",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val name = getRequiredArgument(arguments, "name")
        val value = getRequiredArgument(arguments, "value")
        configStorage.putString(PREFIX + name, value)
        return ToolResult(callId, true, "Set $name=$value")
    }
}

class DeleteEnvironmentTool(
    private val configStorage: com.lin.hippyagent.core.storage.ConfigStorage
) : Tool() {
    private companion object {
        const val PREFIX = "env_var_"
    }

    override val definition = ToolDefinition(
        name = "delete_environment",
        description = "Delete a custom environment variable",
        parameters = mapOf(
            "name" to ToolParameter(
                name = "name",
                type = "string",
                description = "Variable name to delete",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val name = getRequiredArgument(arguments, "name")
        val key = PREFIX + name
        if (!configStorage.contains(key)) {
            return ToolResult(callId, false, error = "Variable not found: $name")
        }
        configStorage.remove(key)
        return ToolResult(callId, true, "Deleted $name")
    }
}

