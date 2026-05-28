package com.lin.hippyagent.core.tools.builtin

import com.lin.hippyagent.core.memory.profile.UserProfileManager
import com.lin.hippyagent.core.memory.commonmemory.MemoryRepository
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import com.lin.hippyagent.core.storage.StorageManager
import java.io.File

class MemoryEvolutionTool(
    private val memoryRepository: MemoryRepository,
    private val storageManager: StorageManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "memory_evolution",
        description = "更新全局记忆和用户画像。从 CommonMemory 中提取模式，更新用户画像。",
        parameters = mapOf(
            "action" to ToolParameter(
                name = "action",
                type = "string",
                description = "操作: evolve(从记忆中进化用户画像), show(查看当前用户画像)",
                required = true
            )
        )
    )

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val action = getRequiredArgument(args, "action")
        val callId = args["callId"] as? String ?: ""
        val workspaceDir = File(storageManager.getWorkingDir(), "workspaces/${ctx.agentId}")
        val profileManager = UserProfileManager(workspaceDir)

        return when (action) {
            "evolve" -> {
                val memories = memoryRepository.searchHybrid("", limit = 50).map { it.first }
                profileManager.evolveFromMemories(memories, emptyList())
                ToolResult(callId, true, forLLM = "用户画像已从 ${memories.size} 条记忆中进化更新", forUser = "已更新用户画像")
            }
            "show" -> {
                val profile = profileManager.getProfile()
                ToolResult(callId, true, forLLM = "当前用户画像: name=${profile.name}, habits=${profile.taskHabits}, topics=${profile.preferredTopics}")
            }
            else -> ToolResult(callId, false, error = "Unknown action: $action")
        }
    }

    override suspend fun execute(arguments: Map<String, Any>): ToolResult = execute(ToolContext(), arguments)
}
