package com.lin.hippyagent.core.security

data class WorkspaceContext(
    val workspaceDir: java.io.File,
    val allowedPaths: List<String>,
    val secrets: Set<String> = emptySet()
)

data class ToolGuardContext(
    val toolName: String,
    val arguments: Map<String, Any>,
    val workspaceContext: WorkspaceContext,
    val executionLevel: ToolExecutionLevel,
    val sessionId: String? = null
)

interface Guardian {
    val name: String
    val priority: Int
    val alwaysRun: Boolean

    suspend fun check(
        toolName: String,
        arguments: Map<String, Any>,
        context: ToolGuardContext
    ): List<GuardFinding>

    suspend fun reload() {}
}

