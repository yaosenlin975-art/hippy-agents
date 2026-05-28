package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import timber.log.Timber

/**
 * QA 知识库索引技能 - 将关键词映射到本地源码路径和文档
 * 用于快速定位 HippyAgent 相关问题的答案
 */
class QASourceIndexSkill(private val context: Context) {

    /**
     * 源码索引：关键词 → 文件路径映射
     */
    private val sourceIndex = mapOf(
        // Agent 系统
        "agent" to listOf("core/agent/Agent.kt", "core/agent/AgentFactory.kt", "core/agent/AgentProfile.kt"),
        "agent创建" to listOf("core/agent/AgentFactory.kt", "ui/agent/AgentListScreen.kt"),
        "agent配置" to listOf("ui/agent/AgentConfigScreen.kt", "core/agent/AgentProfile.kt"),
        "agent切换" to listOf("ui/conversation/ConversationListScreen.kt"),
        "agent群组" to listOf("core/agent/collaboration/AgentGroup.kt"),

        // 会话系统
        "session" to listOf("core/agent/session/SessionStore.kt", "core/agent/session/Session.kt"),
        "会话" to listOf("core/agent/session/SessionStore.kt", "ui/conversation/ConversationListScreen.kt"),
        "消息" to listOf("core/agent/session/SessionMessage.kt", "core/agent/MessageQueueManager.kt"),

        // 工具系统
        "tool" to listOf("core/tool/ToolRegistry.kt", "core/security/ToolGuardian.kt"),
        "工具" to listOf("core/tool/ToolRegistry.kt", "core/security/ToolGuardian.kt"),
        "工具审批" to listOf("core/security/ToolGuardian.kt", "ui/settings/ToolApprovalsScreen.kt"),

        // 技能系统
        "skill" to listOf("core/skill/SkillManager.kt", "core/skill/builtin/BuiltinSkillRegistry.kt"),
        "技能" to listOf("core/skill/SkillManager.kt", "ui/settings/SkillPoolScreen.kt"),

        // 模型系统
        "model" to listOf("core/model/ModelClient.kt", "core/model/ModelProviderStore.kt"),
        "模型" to listOf("core/model/ModelClient.kt", "ui/settings/ModelProviderScreen.kt"),
        "provider" to listOf("core/model/ModelProviderStore.kt", "data/model/ModelProvider.kt"),

        // 记忆系统
        "memory" to listOf("core/agent/memory/MemoryManager.kt"),
        "记忆" to listOf("core/agent/memory/MemoryManager.kt"),

        // 存储系统
        "storage" to listOf("core/storage/StorageManager.kt", "core/storage/SecureStorage.kt"),
        "存储" to listOf("core/storage/StorageManager.kt", "core/storage/ConfigStorage.kt"),
        "备份" to listOf("core/storage/BackupManager.kt"),

        // 通知系统
        "notification" to listOf("core/notification/HippyAgentNotificationService.kt"),
        "通知" to listOf("core/notification/HippyAgentNotificationService.kt", "core/notification/NotificationSettings.kt"),

        // 安全系统
        "security" to listOf("core/security/ToolGuardian.kt", "core/plugin/SkillScanner.kt"),
        "安全" to listOf("core/security/ToolGuardian.kt", "core/plugin/SkillScanner.kt"),

        // 插件系统
        "plugin" to listOf("core/plugin/PluginManager.kt"),
        "插件" to listOf("core/plugin/PluginManager.kt"),

        // 渠道系统
        "channel" to listOf("core/channel/TelegramChannel.kt"),
        "渠道" to listOf("core/channel/TelegramChannel.kt"),

        // 定时任务
        "heartbeat" to listOf("core/agent/HeartbeatConfig.kt"),
        "心跳" to listOf("core/agent/HeartbeatConfig.kt"),

        // UI 页面
        "设置" to listOf("ui/settings/SettingsScreen.kt"),
        "聊天" to listOf("ui/chat/ChatScreen.kt", "ui/chat/ChatViewModel.kt"),
        "登录" to listOf("ui/auth/LoginScreen.kt"),

        // Android 特定
        "权限" to listOf("AndroidManifest.xml"),
        "后台" to listOf("core/service/AgentForegroundService.kt", "core/service/BootReceiver.kt"),
        "前台服务" to listOf("core/service/AgentForegroundService.kt"),

        // 配置文件
        "rules" to listOf("assets/templates/RULES.md"),
        "soul" to listOf("assets/templates/SOUL.md"),
        "profile" to listOf("assets/templates/PROFILE.md"),
        "bootstrap" to listOf("assets/templates/BOOTSTRAP.md"),
    )

    /**
     * 搜索关键词，返回相关源码路径
     */
    fun searchSource(keyword: String): Result<String> {
        return try {
            val query = keyword.lowercase().trim()
            val matches = mutableListOf<Pair<String, List<String>>>()

            sourceIndex.forEach { (key, paths) ->
                if (key.contains(query) || query.contains(key)) {
                    matches.add(key to paths)
                }
            }

            if (matches.isEmpty()) {
                Result.success("未找到与「$keyword」相关的源码索引。\n\n可用关键词: ${sourceIndex.keys.joinToString(", ")}")
            } else {
                val sb = StringBuilder("# 「$keyword」相关源码\n\n")
                matches.forEach { (key, paths) ->
                    sb.appendLine("## $key")
                    paths.forEach { path ->
                        sb.appendLine("- `$path`")
                    }
                    sb.appendLine()
                }
                Result.success(sb.toString())
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to search source index")
            Result.failure(e)
        }
    }

    /**
     * 获取文档路径
     */
    fun getDocPath(topic: String): Result<String> {
        val docIndex = mapOf(
            "heartbeat" to "心跳与定时任务配置",
            "心跳" to "心跳与定时任务配置",
            "memory" to "记忆管理（MEMORY.md 长期记忆）",
            "记忆" to "记忆管理（MEMORY.md 长期记忆）",
            "dream" to "自动成长与梦模式",
            "mcp" to "MCP 工具协议设计",
            "acp" to "多智能体协作协议",
            "skill" to "技能管理与安全工具守卫",
            "技能" to "技能管理与安全工具守卫",
            "plugin" to "插件系统设计",
            "插件" to "插件系统设计",
            "backup" to "备份与恢复",
            "备份" to "备份与恢复",
            "auth" to "认证与本地 API",
            "认证" to "认证与本地 API",
            "alinux" to "ALinux 容器环境设计",
            "linux" to "ALinux 容器环境设计",
            "storage" to "持久化存储",
            "存储" to "持久化存储",
            "background" to "后台服务保活",
            "后台" to "后台服务保活",
            "running" to "运行配置设计",
            "运行配置" to "运行配置设计",
        )

        val query = topic.lowercase().trim()
        val entry = docIndex.entries.firstOrNull { (key, _) ->
            key.contains(query) || query.contains(key)
        }

        return if (entry != null) {
            val guidance = GuidanceSkill(context)
            val guideContent = when (entry.key) {
                "heartbeat", "心跳" -> guidance.getConfigGuide("heartbeat").getOrDefault("")
                "memory", "记忆" -> guidance.getConfigGuide("memory").getOrDefault("")
                "skill", "技能" -> guidance.getConfigGuide("skill").getOrDefault("")
                "alinux", "linux" -> guidance.getConfigGuide("environment").getOrDefault("")
                "storage", "存储" -> guidance.getConfigGuide("storage").getOrDefault("")
                else -> "${entry.value}\n\n使用 guidance 工具获取详细指南。"
            }
            Result.success(guideContent.ifBlank { "📄 ${entry.value}\n\n使用 guidance 工具获取详细指南。" })
        } else {
            Result.success("未找到「$topic」相关文档。\n可用主题: ${docIndex.keys.joinToString(", ")}")
        }
    }

    /**
     * 列出所有索引条目
     */
    fun listAll(): Result<String> {
        val sb = StringBuilder("# QA 源码索引\n\n")
        sourceIndex.entries.groupBy { it.key.first() }.forEach { (letter, entries) ->
            sb.appendLine("## $letter")
            entries.forEach { (key, paths) ->
                sb.appendLine("- **$key**: ${paths.joinToString(", ")}")
            }
            sb.appendLine()
        }
        return Result.success(sb.toString())
    }
}

