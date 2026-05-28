package com.lin.hippyagent.core.tools.builtin

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

class GradleBuildTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "gradle_build",
        description = "Execute Gradle build",
        parameters = mapOf(
            "task" to ToolParameter(
                name = "task",
                type = "string",
                description = "Gradle task name (e.g., assembleDebug)",
                required = false,
                defaultValue = "assembleDebug"
            )
        ),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val task = getOptionalArgument(arguments, "task", "assembleDebug")!!
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }
        val (exitCode, output) = linuxManager.exec("./gradlew $task", timeout = 300_000)
        return if (exitCode == 0) ToolResult(callId, true, output = output)
        else ToolResult(callId, false, error = output)
    }
}

class GradleTestTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "gradle_test",
        description = "Run Gradle tests",
        parameters = emptyMap(),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }
        val (exitCode, output) = linuxManager.exec("./gradlew test", timeout = 300_000)
        return if (exitCode == 0) ToolResult(callId, true, output = output)
        else ToolResult(callId, false, error = output)
    }
}

class GradleCleanTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "gradle_clean",
        description = "Clean Gradle build",
        parameters = emptyMap(),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }
        val (exitCode, output) = linuxManager.exec("./gradlew clean")
        return if (exitCode == 0) ToolResult(callId, true, output = "Clean completed")
        else ToolResult(callId, false, error = output)
    }
}

class GradleTasksTool(
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "gradle_tasks",
        description = "List available Gradle tasks",
        parameters = emptyMap(),
        requiredPermissions = listOf("SHELL_EXECUTE")
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }
        val (exitCode, output) = linuxManager.exec("./gradlew tasks --all")
        return if (exitCode == 0) ToolResult(callId, true, output = output)
        else ToolResult(callId, false, error = output)
    }
}

