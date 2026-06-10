package com.lin.hippyagent.core.agent.task

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class ToolCallAuditEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val stepId: String,
    val toolName: String,
    val arguments: String,
    val output: String,
    val durationMs: Long,
    val success: Boolean,
    val sessionId: String? = null,
    val agentId: String? = null,
    val taskId: String? = null
)

class AuditLogger(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val writeMutex = Mutex()

    private val logDir: File by lazy {
        File(appContext.filesDir, LOG_DIR).also { it.mkdirs() }
    }

    private val logFile: File by lazy {
        File(logDir, LOG_FILE)
    }

    suspend fun logToolCall(
        stepId: String,
        toolName: String,
        arguments: String,
        output: String,
        durationMs: Long,
        success: Boolean,
        sessionId: String? = null,
        agentId: String? = null,
        taskId: String? = null
    ) {
        val entry = ToolCallAuditEntry(
            stepId = stepId,
            toolName = toolName,
            arguments = arguments,
            output = output,
            durationMs = durationMs,
            success = success,
            sessionId = sessionId,
            agentId = agentId,
            taskId = taskId
        )
        writeMutex.withLock {
            runCatching {
                logFile.appendText(json.encodeToString(entry) + "\n")
            }.onFailure { e ->
                Timber.e(e, "AuditLogger: failed to append tool call audit entry")
            }
        }
    }

    suspend fun getRecentEntries(limit: Int = 100): List<ToolCallAuditEntry> = writeMutex.withLock {
        if (!logFile.exists() || limit <= 0) return@withLock emptyList()
        runCatching {
            logFile.readLines()
                .asReversed()
                .take(limit)
                .mapNotNull {
                    runCatching { json.decodeFromString<ToolCallAuditEntry>(it) }.getOrNull()
                }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val LOG_DIR = "task_audit"
        private const val LOG_FILE = "audit.jsonl"
    }
}
