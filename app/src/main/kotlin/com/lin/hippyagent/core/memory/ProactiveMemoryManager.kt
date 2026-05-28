package com.lin.hippyagent.core.memory

import android.content.Context
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.notification.HippyAgentNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 主动记忆管理器
 * 在用户空闲时主动生成建议查询
 */
class ProactiveMemoryManager(
    private val context: Context,
    private val sessionStore: SessionStore,
    private val notificationService: HippyAgentNotificationService? = null
) {
    private var lastUserInteraction: Instant = Instant.now()
    private var isMonitoring = false
    private var idleThresholdMinutes = 30

    /**
     * 配置
     */
    data class ProactiveConfig(
        val enabled: Boolean = false,
        val idleMinutes: Int = 30
    )

    private var config = ProactiveConfig()

    /**
     * 更新配置
     */
    fun updateConfig(newConfig: ProactiveConfig) {
        config = newConfig
        idleThresholdMinutes = newConfig.idleMinutes
    }

    /**
     * 记录用户交互
     */
    fun recordUserInteraction() {
        lastUserInteraction = Instant.now()
    }

    /**
     * 启动空闲监控
     */
    suspend fun startMonitoring() {
        if (isMonitoring || !config.enabled) return
        
        isMonitoring = true
        Timber.i("ProactiveMemory monitoring started")

        while (currentCoroutineContext().isActive) {
            delay(60_000) // 每分钟检查一次

            if (!config.enabled) continue

            val idleMinutes = ChronoUnit.MINUTES.between(lastUserInteraction, Instant.now())
            
            if (idleMinutes >= idleThresholdMinutes) {
                Timber.d("User idle for $idleMinutes minutes, generating suggestions")
                generateSuggestions()
            }
        }
    }

    /**
     * 停止监控
     */
    fun stopMonitoring() {
        isMonitoring = false
        Timber.i("ProactiveMemory monitoring stopped")
    }

    /**
     * 生成建议查询
     */
    private suspend fun generateSuggestions() = withContext(Dispatchers.Default) {
        runCatching {
            // 获取最近的会话消息
            val sessions = sessionStore.getAllSessions().getOrNull().orEmpty()
            if (sessions.isEmpty()) return@runCatching

            val latestSession = sessions.maxByOrNull { it.lastUpdatedAt } ?: return@runCatching
            val messages = sessionStore.getMessages(latestSession.id).getOrNull().orEmpty()
            
            if (messages.isEmpty()) return@runCatching

            // 提取用户目标
            val userGoals = extractUserGoals(messages)
            
            if (userGoals.isEmpty()) return@runCatching

            // 生成建议查询
            val suggestions = buildSuggestions(userGoals)
            
            // 推送通知
            if (suggestions.isNotEmpty()) {
                notificationService?.sendAgentMessageNotification(
                    agentName = "智能建议",
                    sessionName = "",
                    message = suggestions.first(),
                    sessionId = latestSession.id
                )
                
                Timber.i("Proactive suggestions generated: ${suggestions.size}")
            }
        }
    }

    /**
     * 从会话中提取用户目标
     */
    private fun extractUserGoals(messages: List<com.lin.hippyagent.core.agent.session.SessionMessage>): List<String> {
        val goals = mutableListOf<String>()
        
        // 提取最近的用户消息
        val recentUserMessages = messages
            .filter { it.role == MessageRole.USER }
            .takeLast(5)
        
        for (msg in recentUserMessages) {
            val content = msg.content.trim()
            
            // 简单关键词匹配提取目标
            when {
                content.contains("帮我") || content.contains("请") -> {
                    goals.add(content.take(50))
                }
                content.contains("实现") || content.contains("开发") -> {
                    goals.add(content.take(50))
                }
                content.contains("修复") || content.contains("解决") -> {
                    goals.add(content.take(50))
                }
            }
        }
        
        return goals.distinct()
    }

    /**
     * 构建建议查询
     */
    private fun buildSuggestions(goals: List<String>): List<String> {
        val suggestions = mutableListOf<String>()
        
        for (goal in goals) {
            when {
                goal.contains("实现") || goal.contains("开发") -> {
                    suggestions.add("是否需要继续完成：${goal.take(30)}...？")
                }
                goal.contains("修复") || goal.contains("解决") -> {
                    suggestions.add("之前的问题「${goal.take(30)}...」是否已解决？")
                }
                else -> {
                    suggestions.add("关于「${goal.take(30)}...」，需要我继续吗？")
                }
            }
        }
        
        return suggestions.take(3) // 最多 3 个建议
    }

    /**
     * 获取空闲状态
     */
    fun getIdleStatus(): Map<String, Any> {
        val idleMinutes = ChronoUnit.MINUTES.between(lastUserInteraction, Instant.now())
        return mapOf(
            "enabled" to config.enabled,
            "idleMinutes" to idleMinutes,
            "threshold" to idleThresholdMinutes,
            "isIdle" to (idleMinutes >= idleThresholdMinutes)
        )
    }
}

