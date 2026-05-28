package com.lin.hippyagent.core.heartbeat

import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.config.HeartbeatConfig
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.storage.StorageManager
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HeartbeatScheduler(
    private val agentFactory: AgentFactory,
    private val sessionStore: SessionStore,
    private val storageManager: StorageManager
) {
    private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val heartbeatJobs = ConcurrentHashMap<String, Job>()
    private val activeHeartbeats = ConcurrentHashMap<String, HeartbeatTask>()

    suspend fun scheduleHeartbeat(agentId: String, config: HeartbeatConfig): Result<Unit> {
        return runCatching {
            cancelHeartbeat(agentId)

            if (!config.enabled) {
                Timber.d("Heartbeat disabled for agent: $agentId")
                return@runCatching
            }

            val intervalMillis = parseInterval(config.every)
            val task = HeartbeatTask(
                agentId = agentId,
                config = config,
                intervalMillis = intervalMillis
            )

            val job = schedulerScope.launch {
                while (coroutineContext.isActive) {
                    if (isWithinActiveHours(config)) {
                        executeHeartbeat(agentId, config)
                    }
                    delay(intervalMillis)
                }
            }

            heartbeatJobs[agentId] = job
            activeHeartbeats[agentId] = task

            Timber.i("Heartbeat scheduled for agent: $agentId (every ${config.every})")
        }.onFailure { e ->
            Timber.e(e, "Failed to schedule heartbeat for agent: $agentId")
        }
    }

    fun cancelHeartbeat(agentId: String) {
        heartbeatJobs[agentId]?.cancel()
        heartbeatJobs.remove(agentId)
        activeHeartbeats.remove(agentId)
        Timber.d("Heartbeat cancelled for agent: $agentId")
    }

    fun cancelAllHeartbeats() {
        heartbeatJobs.forEach { (_, job) -> job.cancel() }
        heartbeatJobs.clear()
        activeHeartbeats.clear()
        Timber.i("All heartbeats cancelled")
    }

    fun getActiveHeartbeats(): Map<String, HeartbeatTask> = activeHeartbeats.toMap()

    fun isHeartbeatActive(agentId: String): Boolean = heartbeatJobs.containsKey(agentId)

    private suspend fun executeHeartbeat(agentId: String, config: HeartbeatConfig) {
        Timber.d("Executing heartbeat for agent: $agentId")

        try {
            val agent = agentFactory.getAgent(agentId)
            if (agent == null) {
                Timber.w("Agent not found for heartbeat: $agentId")
                return
            }

            val heartbeatContent = readHeartbeatMd(agentId)

            if (heartbeatContent.isBlank()) {
                Timber.d("HEARTBEAT.md is empty for agent: $agentId, skipping heartbeat")
                return
            }

            val targetSessionId = when (config.target) {
                "main" -> getMainSessionId(agentId)
                "last" -> getLastSessionId(agentId)
                else -> config.target
            }

            val message = "[HEARTBEAT]\n$heartbeatContent"

            if (targetSessionId == null) {
                Timber.d("No target session found for heartbeat: $agentId, creating ad-hoc session")
                val createResult = sessionStore.createSession(
                    agentId = agentId,
                    title = "Heartbeat Session"
                )
                val heartbeatSessionId = createResult.getOrNull()?.id
                if (heartbeatSessionId == null) {
                    Timber.e("Failed to create heartbeat session for agent: $agentId")
                    return
                }
                agent.processMessage(
                    sessionId = heartbeatSessionId,
                    channelId = "heartbeat",
                    content = message
                )
                return
            }

            agent.processMessage(
                sessionId = targetSessionId,
                channelId = "heartbeat",
                content = message
            )

            Timber.i("Heartbeat executed for agent: $agentId")
        } catch (e: Exception) {
            Timber.e(e, "Heartbeat execution failed for agent: $agentId")
        }
    }

    private fun readHeartbeatMd(agentId: String): String {
        val workspaceDir = File(storageManager.getWorkingDir(), "workspaces/$agentId")
        val heartbeatFile = File(workspaceDir, "HEARTBEAT.md")
        if (!heartbeatFile.exists()) {
            runCatching { heartbeatFile.writeText("") }
            return ""
        }
        return runCatching {
            heartbeatFile.readLines()
                .filter { !it.trim().startsWith("#") }
                .joinToString("\n")
                .trim()
                .takeIf { it.isNotBlank() } ?: ""
        }.getOrDefault("")
    }

    private fun isWithinActiveHours(config: HeartbeatConfig): Boolean {
        val activeHours = config.activeHours ?: return true

        val now = LocalTime.now()
        val startTime = LocalTime.parse(activeHours.start, timeFormatter)
        val endTime = LocalTime.parse(activeHours.end, timeFormatter)

        return if (startTime <= endTime) {
            now in startTime..endTime
        } else {
            now >= startTime || now <= endTime
        }
    }

    /**
     * Get the "main" session for an agent — the most recently active non-heartbeat session.
     * Falls back to the most recent session of any type, then null.
     */
    private suspend fun getMainSessionId(agentId: String): String? {
        return try {
            val sessions = sessionStore.getSessionsForAgent(agentId).getOrNull()
            // Prefer the most recently updated non-heartbeat session
            val mainSession = sessions
                ?.filter { !it.id.startsWith("heartbeat_") }
                ?.maxByOrNull { it.lastUpdatedAt }
                ?: sessions?.maxByOrNull { it.lastUpdatedAt }
            mainSession?.id
        } catch (e: Exception) {
            Timber.w(e, "Failed to get main session for agent: $agentId")
            null
        }
    }

    /**
     * Get the last session that was dispatched to any channel (most recently updated).
     */
    private suspend fun getLastSessionId(agentId: String): String? {
        return try {
            val sessions = sessionStore.getSessionsForAgent(agentId).getOrNull()
            sessions?.maxByOrNull { it.lastUpdatedAt }?.id
        } catch (e: Exception) {
            Timber.w(e, "Failed to get last session for agent: $agentId")
            null
        }
    }

    private fun parseInterval(every: String): Long {
        return when {
            every.endsWith("m") -> every.dropLast(1).toLongOrNull()?.let {
                TimeUnit.MINUTES.toMillis(it)
            } ?: TimeUnit.MINUTES.toMillis(60)
            every.endsWith("h") -> every.dropLast(1).toLongOrNull()?.let {
                TimeUnit.HOURS.toMillis(it)
            } ?: TimeUnit.HOURS.toMillis(6)
            else -> TimeUnit.HOURS.toMillis(6)
        }
    }

    fun shutdown() {
        schedulerScope.cancel()
        heartbeatJobs.clear()
        activeHeartbeats.clear()
    }
    companion object {
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

data class HeartbeatTask(
    val agentId: String,
    val config: HeartbeatConfig,
    val intervalMillis: Long,
    val lastExecuted: Long = 0,
    val executionCount: Int = 0
)

