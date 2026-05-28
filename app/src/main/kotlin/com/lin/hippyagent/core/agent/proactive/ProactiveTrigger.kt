package com.lin.hippyagent.core.agent.proactive

import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.channel.ChannelManager
import com.lin.hippyagent.core.channel.ChannelMessage
import com.lin.hippyagent.core.memory.MemoryManager
import com.lin.hippyagent.core.memory.MemoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit

data class ProactiveTriggerConfig(
    val enabled: Boolean = false,
    val idleThresholdMinutes: Int = 30,
    val maxProactivePerHour: Int = 3,
    val checkIntervalMinutes: Int = 5
)

data class ProactiveMessage(
    val agentId: String,
    val content: String,
    val triggerReason: String,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class TriggerSource {
    data class SessionMemory(val sessionId: String, val relevance: Float) : TriggerSource()
    data class ScreenContext(val contextType: String, val details: String) : TriggerSource()
    data class ScheduledReminder(val reminderId: String) : TriggerSource()
}

class ProactiveTrigger(
    private val sessionStore: SessionStore,
    private val memoryManager: MemoryManager,
    private val channelManager: ChannelManager
) {
    private val _config = MutableStateFlow(ProactiveTriggerConfig())
    val config: StateFlow<ProactiveTriggerConfig> = _config.asStateFlow()

    private val _recentTriggers = MutableStateFlow<List<ProactiveMessage>>(emptyList())
    val recentTriggers: StateFlow<List<ProactiveMessage>> = _recentTriggers.asStateFlow()

    private var lastUserInteraction: Instant = Instant.now()
    private var isRunning = false
    private var proactiveCountThisHour = 0
    private var lastHourReset: Instant = Instant.now()

    fun updateConfig(newConfig: ProactiveTriggerConfig) {
        _config.value = newConfig
    }

    fun recordUserInteraction() {
        lastUserInteraction = Instant.now()
    }

    suspend fun start() {
        if (isRunning) return
        isRunning = true
        Timber.i("ProactiveTrigger started")

        while (isRunning) {
            try {
                val cfg = _config.value
                if (cfg.enabled) {
                    checkAndTrigger()
                }
                delay(cfg.checkIntervalMinutes * 60_000L)
            } catch (e: Exception) {
                Timber.e(e, "ProactiveTrigger error")
                delay(60_000L)
            }
        }
    }

    fun stop() {
        isRunning = false
        Timber.i("ProactiveTrigger stopped")
    }

    private suspend fun checkAndTrigger() {
        val cfg = _config.value
        if (!cfg.enabled) return

        resetHourlyCountIfNeeded()

        val idleMinutes = ChronoUnit.MINUTES.between(lastUserInteraction, Instant.now())
        if (idleMinutes < cfg.idleThresholdMinutes) return

        if (proactiveCountThisHour >= cfg.maxProactivePerHour) return

        val triggerSource = detectTriggerSource() ?: return

        val message = generateProactiveMessage(triggerSource) ?: return

        deliverProactiveMessage(message)
        proactiveCountThisHour++
        _recentTriggers.value = (_recentTriggers.value + message).takeLast(20)
    }

    private suspend fun detectTriggerSource(): TriggerSource? {
        val sessionTrigger = detectSessionMemoryTrigger()
        if (sessionTrigger != null) return sessionTrigger

        val screenTrigger = detectScreenContextTrigger()
        if (screenTrigger != null) return screenTrigger

        return null
    }

    private suspend fun detectSessionMemoryTrigger(): TriggerSource.SessionMemory? {
        return try {
            val recentSessions = sessionStore.getAllSessions()
                .getOrNull().orEmpty()
                .sortedByDescending { it.lastUpdatedAt }
                .take(5)

            if (recentSessions.isEmpty()) return null

            val latestSession = recentSessions.first()
            val timeSinceActivity = System.currentTimeMillis() - latestSession.lastUpdatedAt.toEpochMilli()
            if (timeSinceActivity < _config.value.idleThresholdMinutes * 60_000L) return null

            val memoryResults = memoryManager.recall(
                query = latestSession.title,
                maxResults = 3,
                minScore = 0.5f
            ).getOrNull().orEmpty()

            if (memoryResults.isNotEmpty() && memoryResults.any { it.score > 0.6f }) {
                TriggerSource.SessionMemory(
                    sessionId = latestSession.id,
                    relevance = memoryResults.maxOf { it.score }
                )
            } else null
        } catch (e: Exception) {
            Timber.w(e, "Session memory trigger detection failed")
            null
        }
    }

    private suspend fun detectScreenContextTrigger(): TriggerSource.ScreenContext? {
        return try {
            val memories = memoryManager.recall(
                query = "用户偏好 提醒 待办",
                maxResults = 5,
                minScore = 0.4f
            ).getOrDefault(emptyList())

            if (memories.any { it.score > 0.5f }) {
                val topMemory = memories.maxBy { it.score }
                TriggerSource.ScreenContext(
                    contextType = "memory_reminder",
                    details = topMemory.entry.content.take(100)
                )
            } else null
        } catch (e: Exception) {
            Timber.w(e, "Screen context trigger detection failed")
            null
        }
    }

    private suspend fun generateProactiveMessage(source: TriggerSource): ProactiveMessage? {
        return when (source) {
            is TriggerSource.SessionMemory -> {
                val session = sessionStore.getSession(source.sessionId).getOrNull() ?: return null
                ProactiveMessage(
                    agentId = session.agentId,
                    content = "您之前在「${session.title ?: "会话"}」中有未完成的话题，需要继续吗？",
                    triggerReason = "session_memory_idle_${source.relevance}"
                )
            }
            is TriggerSource.ScreenContext -> {
                ProactiveMessage(
                    agentId = com.lin.hippyagent.data.repository.AgentRepository.DEFAULT_AGENT_ID,
                    content = "根据您的偏好，我有新的建议：${source.details}",
                    triggerReason = "screen_context_${source.contextType}"
                )
            }
            is TriggerSource.ScheduledReminder -> {
                ProactiveMessage(
                    agentId = com.lin.hippyagent.data.repository.AgentRepository.DEFAULT_AGENT_ID,
                    content = "提醒时间到了",
                    triggerReason = "scheduled_${source.reminderId}"
                )
            }
        }
    }

    private suspend fun deliverProactiveMessage(message: ProactiveMessage) {
        try {
            val channelMessage = ChannelMessage(
                content = message.content,
                senderId = message.agentId,
                metadata = mapOf(
                    "proactive" to "true",
                    "trigger_reason" to message.triggerReason
                )
            )
            channelManager.broadcast(channelMessage)
            Timber.i("Proactive message delivered: ${message.triggerReason}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to deliver proactive message")
        }
    }

    private fun resetHourlyCountIfNeeded() {
        val now = Instant.now()
        if (ChronoUnit.HOURS.between(lastHourReset, now) >= 1) {
            proactiveCountThisHour = 0
            lastHourReset = now
        }
    }
}

