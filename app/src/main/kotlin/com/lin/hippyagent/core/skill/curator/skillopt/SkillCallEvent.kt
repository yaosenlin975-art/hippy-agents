package com.lin.hippyagent.core.skill.curator.skillopt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class SkillCallEvent(
    val skillId: String,
    val agentId: String,
    val timestamp: Long,
    val success: Boolean,
    val durationMs: Long,
    val userFeedback: Int? = null
)

class SkillCallEventStore(context: Context) {
    private val dir: File = File(context.filesDir, "skillopt").apply { mkdirs() }
    private val eventsFile: File = File(dir, "events.jsonl")
    private val writeMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun append(event: SkillCallEvent) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            runCatching {
                eventsFile.appendText(json.encodeToString(event) + "\n")
            }.onFailure { Timber.w(it, "Failed to append skill call event") }
        }
    }

    suspend fun loadAll(): List<SkillCallEvent> = withContext(Dispatchers.IO) {
        if (!eventsFile.exists()) return@withContext emptyList()
        val events = mutableListOf<SkillCallEvent>()
        runCatching {
            eventsFile.useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        runCatching { json.decodeFromString<SkillCallEvent>(line) }
                            .onSuccess { events.add(it) }
                    }
                }
            }
        }.onFailure { Timber.w(it, "Failed to load skill call events") }
        events
    }
}
