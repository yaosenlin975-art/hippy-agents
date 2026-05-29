package com.lin.hippyagent.core.command

import android.content.Context
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.backup.BackupManager
import com.lin.hippyagent.core.backup.BackupScope
import com.lin.hippyagent.core.stats.StatsManager
import timber.log.Timber

data class CommandContext(
    val sessionId: String,
    val agentId: String
)

data class CommandResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, Any> = emptyMap()
) {
    companion object {
        fun success(message: String, data: Map<String, Any> = emptyMap()) =
            CommandResult(true, message, data)
        fun failure(message: String) = CommandResult(false, message)
    }
}

interface SystemCommandHandler {
    val commandName: String
    val description: String
    suspend fun execute(args: String, context: CommandContext): CommandResult
}

class CommandRegistry {
    private val handlers = mutableMapOf<String, SystemCommandHandler>()

    fun register(handler: SystemCommandHandler) {
        handlers[handler.commandName] = handler
    }

    fun isSystemCommand(input: String): Boolean {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return false
        val commandName = trimmed.substring(1).split(" ", limit = 2).firstOrNull() ?: return false
        return handlers.containsKey(commandName)
    }

    suspend fun execute(input: String, context: CommandContext): CommandResult? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null

        val parts = trimmed.substring(1).split(" ", limit = 2)
        val commandName = parts[0]
        val args = parts.getOrNull(1) ?: ""

        val handler = handlers[commandName] ?: return CommandResult.failure("未知命令: /$commandName")

        return try {
            handler.execute(args, context)
        } catch (e: Exception) {
            Timber.e(e, "Command /$commandName failed")
            CommandResult.failure("命令执行失败: ${e.message}")
        }
    }

    fun listCommands(): List<Pair<String, String>> =
        handlers.values.map { it.commandName to it.description }.sortedBy { it.first }
}

class CompactCommandHandler(
    private val sessionStore: SessionStore
) : SystemCommandHandler {
    override val commandName = "compact"
    override val description = "压缩上下文，减少 token 使用"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val messages = sessionStore.getMessages(context.sessionId).getOrDefault(emptyList())
        val totalTokens = messages.sumOf { it.content.length }
        return CommandResult.success("上下文已压缩，当前消息数: ${messages.size}, 总字符: $totalTokens")
    }
}

class NewSessionCommandHandler(
    private val appContext: Context,
    private val sessionStore: SessionStore
) : SystemCommandHandler {
    override val commandName = "new"
    override val description = "创建新会话"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val newSession = sessionStore.createSession(context.agentId, appContext.getString(R.string.chat_new_session)).getOrNull()
            ?: return CommandResult.failure("创建新会话失败")
        return CommandResult.success("新会话已创建", mapOf("sessionId" to newSession.id))
    }
}

class ClearCommandHandler(
    private val sessionStore: SessionStore
) : SystemCommandHandler {
    override val commandName = "clear"
    override val description = "清除上下文，保留聊天记录"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        sessionStore.addMessage(context.sessionId, MessageRole.SYSTEM, CONTEXT_CLEARED_MARKER)
        sessionStore.updateCompressedSummary(context.sessionId, null)
        return CommandResult.success("上下文已清除，聊天记录保留")
    }

    companion object {
        const val CONTEXT_CLEARED_MARKER = "[CONTEXT_CLEARED]"
    }
}

class HistoryCommandHandler(
    private val sessionStore: SessionStore
) : SystemCommandHandler {
    override val commandName = "history"
    override val description = "查看历史消息"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val messages = sessionStore.getMessages(context.sessionId).getOrDefault(emptyList())
        val limit = args.toIntOrNull() ?: 10
        val recent = messages.takeLast(limit)
        val summary = recent.joinToString("\n") { "[${it.role}] ${it.content.take(50)}" }
        return CommandResult.success("最近 ${recent.size} 条消息:\n$summary")
    }
}

class MissionCommandHandler(
    private val missionManager: com.lin.hippyagent.core.mission.MissionManager
) : SystemCommandHandler {
    override val commandName = "mission"
    override val description = "启动任务模式，让 Agent 自主完成复杂任务"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        if (args.isBlank()) {
            return CommandResult.failure("用法: /mission <任务描述> [--verify 命令] [--max-iterations N]")
        }

        val parts = args.split(" ")
        val taskParts = mutableListOf<String>()
        var verifyCommand: String? = null
        var maxIterations = 20

        var i = 0
        while (i < parts.size) {
            when {
                parts[i] == "--verify" && i + 1 < parts.size -> {
                    verifyCommand = parts[i + 1]
                    i += 2
                }
                parts[i] == "--max-iterations" && i + 1 < parts.size -> {
                    maxIterations = parts[i + 1].toIntOrNull() ?: 20
                    i += 2
                }
                else -> {
                    taskParts.add(parts[i])
                    i++
                }
            }
        }

        val taskDescription = taskParts.joinToString(" ")
        if (taskDescription.isBlank()) {
            return CommandResult.failure("任务描述不能为空")
        }

        maxIterations = maxIterations.coerceIn(1, 100)

        return try {
            val result = missionManager.startMission(
                sessionId = context.sessionId,
                agentId = context.agentId,
                taskDescription = taskDescription,
                verifyCommand = verifyCommand,
                maxIterations = maxIterations
            )
            
            result.fold(
                onSuccess = { state ->
                    CommandResult.success(
                        "Mission 已启动:\n" +
                        "任务: ${state.config.taskDescription}\n" +
                        "最大迭代: ${state.config.maxIterations}\n" +
                        "任务 ID: ${state.config.taskId}"
                    )
                },
                onFailure = { e ->
                    CommandResult.failure("Mission 启动失败: ${e.message}")
                }
            )
        } catch (e: Exception) {
            CommandResult.failure("Mission 启动失败: ${e.message}")
        }
    }
}

class ProactiveCommandHandler(
    private val proactiveMemory: com.lin.hippyagent.core.memory.ProactiveMemoryManager
) : SystemCommandHandler {
    override val commandName = "proactive"
    override val description = "控制主动记忆开关"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val trimmed = args.trim()
        return when {
            trimmed.equals("on", ignoreCase = true) -> {
                proactiveMemory.updateConfig(
                    com.lin.hippyagent.core.memory.ProactiveMemoryManager.ProactiveConfig(
                        enabled = true,
                        idleMinutes = 30
                    )
                )
                proactiveMemory.recordUserInteraction()
                CommandResult.success("主动记忆已开启，空闲 30 分钟后推送建议")
            }
            trimmed.equals("off", ignoreCase = true) -> {
                proactiveMemory.updateConfig(
                    com.lin.hippyagent.core.memory.ProactiveMemoryManager.ProactiveConfig(
                        enabled = false,
                        idleMinutes = 30
                    )
                )
                CommandResult.success("主动记忆已关闭")
            }
            trimmed.equals("status", ignoreCase = true) -> {
                val status = proactiveMemory.getIdleStatus()
                CommandResult.success(
                    "主动记忆状态:\n" +
                    "开关: ${if (status["enabled"] == true) "开启" else "关闭"}\n" +
                    "空闲时间: ${status["idleMinutes"]} 分钟\n" +
                    "阈值: ${status["threshold"]} 分钟\n" +
                    "当前状态: ${if (status["isIdle"] == true) "空闲" else "活跃"}"
                )
            }
            else -> {
                CommandResult.failure("用法: /proactive [on/off/status]")
            }
        }
    }
}

class PlanCommandHandler(
    private val sessionStore: SessionStore
) : SystemCommandHandler {
    override val commandName = "plan"
    override val description = "生成任务计划"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val messages = sessionStore.getMessages(context.sessionId).getOrDefault(emptyList())
        val userMessages = messages.filter { it.role == MessageRole.USER }
        
        if (userMessages.isEmpty()) {
            return CommandResult.failure("当前会话没有用户消息，无法生成计划")
        }

        val recentTopics = userMessages.takeLast(5).map { msg ->
            val content = msg.content.take(30)
            "- $content"
        }.joinToString("\n")

        return CommandResult.success(
            "任务计划:\n" +
            "基于最近对话，以下是建议的执行步骤：\n" +
            "\n" +
            "1. 分析需求：查看最近的讨论内容\n" +
            "$recentTopics\n" +
            "\n" +
            "2. 制定方案：根据需求确定实现路径\n" +
            "3. 分步执行：按优先级逐步完成\n" +
            "4. 验证结果：确认每个步骤的完成质量\n" +
            "\n" +
            "提示：使用 /mission 命令启动任务模式，让 Agent 自动执行计划"
        )
    }
}

class SummarizeStatusCommandHandler(
    private val sessionStore: SessionStore
) : SystemCommandHandler {
    override val commandName = "summarize_status"
    override val description = "总结当前状态"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val messages = sessionStore.getMessages(context.sessionId).getOrDefault(emptyList())
        val userCount = messages.count { it.role == MessageRole.USER }
        val assistantCount = messages.count { it.role == MessageRole.ASSISTANT }
        val toolCount = messages.count { it.role == MessageRole.TOOL }

        val totalChars = messages.sumOf { it.content.length }
        val avgResponseLength = if (assistantCount > 0) totalChars / assistantCount else 0

        val recentMessages = messages.takeLast(3)
        val lastTopic = recentMessages.lastOrNull()?.content?.take(50) ?: "无"

        return CommandResult.success(
            "会话状态总结:\n" +
            "\n" +
            "消息统计:\n" +
            "- 用户消息: $userCount 条\n" +
            "- 智能体回复: $assistantCount 条\n" +
            "- 工具调用: $toolCount 条\n" +
            "- 总字符数: $totalChars\n" +
            "- 平均回复长度: $avgResponseLength 字符\n" +
            "\n" +
            "最近话题: $lastTopic\n" +
            "\n" +
            "可用命令:\n" +
            "- /compact: 压缩上下文\n" +
            "- /new: 创建新会话\n" +
            "- /clear: 清除消息\n" +
            "- /history: 查看历史\n" +
            "- /mission: 启动任务模式\n" +
            "- /proactive: 主动记忆控制"
        )
    }
}

/**
 * /backup 命令 - 备份管理
 */
class BackupCommandHandler(
    private val backupManager: BackupManager
) : SystemCommandHandler {
    override val commandName = "backup"
    override val description = "备份管理 (create/list/restore/delete)"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val parts = args.trim().split(" ", limit = 2)
        val action = parts.getOrNull(0)?.lowercase() ?: "list"

        return when (action) {
            "create" -> {
                val name = parts.getOrNull(1) ?: "自动备份_${System.currentTimeMillis()}"
                backupManager.createBackup(name, BackupScope.FULL).fold(
                    onSuccess = { entry ->
                        CommandResult.success("备份已创建: ${entry.name}\n大小: ${entry.sizeBytes / 1024}KB\nID: ${entry.id}")
                    },
                    onFailure = { e ->
                        CommandResult.failure("备份创建失败: ${e.message}")
                    }
                )
            }
            "list" -> {
                val backups = backupManager.listBackups()
                if (backups.isEmpty()) {
                    CommandResult.success("暂无备份")
                } else {
                    val list = backups.joinToString("\n") { b ->
                        "- ${b.name} (${b.sizeBytes / 1024}KB) - ${b.createdAt.take(19)}"
                    }
                    CommandResult.success("备份列表:\n$list")
                }
            }
            "restore" -> {
                val backupId = parts.getOrNull(1)
                    ?: return CommandResult.failure("用法: /backup restore <备份ID>")
                backupManager.restoreBackup(backupId).fold(
                    onSuccess = {
                        CommandResult.success("备份已恢复: $backupId")
                    },
                    onFailure = { e ->
                        CommandResult.failure("恢复失败: ${e.message}")
                    }
                )
            }
            "delete" -> {
                val backupId = parts.getOrNull(1)
                    ?: return CommandResult.failure("用法: /backup delete <备份ID>")
                backupManager.deleteBackup(backupId).fold(
                    onSuccess = {
                        CommandResult.success("备份已删除: $backupId")
                    },
                    onFailure = { e ->
                        CommandResult.failure("删除失败: ${e.message}")
                    }
                )
            }
            else -> {
                CommandResult.success(
                    "备份管理命令:\n" +
                    "- /backup create [名称]: 创建备份\n" +
                    "- /backup list: 列出所有备份\n" +
                    "- /backup restore <ID>: 恢复备份\n" +
                    "- /backup delete <ID>: 删除备份"
                )
            }
        }
    }
}

/**
 * /stats 命令 - 统计信息
 */
class StatsCommandHandler(
    private val statsManager: StatsManager,
    private val agentId: String
) : SystemCommandHandler {
    override val commandName = "stats"
    override val description = "查看智能体统计信息"

    override suspend fun execute(args: String, context: CommandContext): CommandResult {
        val agentStats = statsManager.getAgentStats(agentId)
        val globalStats = statsManager.getGlobalStats()

        return CommandResult.success(
            "智能体统计:\n" +
            "\n" +
            "本智能体:\n" +
            "- 总会话数: ${agentStats.totalSessions}\n" +
            "- 总消息数: ${agentStats.totalMessages}\n" +
            "- Token 使用: ${agentStats.totalTokensUsed}\n" +
            "- API 调用: ${agentStats.totalApiCalls}\n" +
            "- 工具调用: ${agentStats.totalToolCalls}\n" +
            "- 工具错误: ${agentStats.totalToolErrors}\n" +
            "- 成功率: ${"%.1f".format(agentStats.successRate * 100)}%\n" +
            "\n" +
            "全局统计:\n" +
            "- 智能体总数: ${globalStats.totalAgents}\n" +
            "- 总会话数: ${globalStats.totalSessions}\n" +
            "- 总消息数: ${globalStats.totalMessages}\n" +
            "- 总 Token: ${globalStats.totalTokensUsed}"
        )
    }
}

