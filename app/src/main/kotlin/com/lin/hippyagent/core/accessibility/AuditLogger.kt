package com.lin.hippyagent.core.accessibility

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AuditEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String? = null,
    val agentId: String? = null,
    val tool: String,
    val action: String,
    val target: String? = null,
    val value: String? = null,
    val riskLevel: String,
    val approvalResult: String? = null,
    val executionResult: String,
    val packageName: String? = null
)

class AuditLogger(private val context: Context) {

    companion object {
        private const val TAG = "AuditLogger"
        private const val MAX_LOG_SIZE = 500
        private const val LOG_DIR = "accessibility_audit"
    }

    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val logDir by lazy { File(context.filesDir, LOG_DIR).also { it.mkdirs() } }

    fun log(
        tool: String,
        action: String,
        target: String?,
        value: String?,
        riskLevel: RiskLevel,
        approved: Boolean?,
        success: Boolean,
        packageName: String? = null
    ) {
        val entry = AuditEntry(
            tool = tool,
            action = action,
            target = target,
            value = value,
            riskLevel = riskLevel.name,
            approvalResult = approved?.let { if (it) "APPROVED" else "DENIED" },
            executionResult = if (success) "SUCCESS" else "FAILED",
            packageName = packageName
        )

        Log.d(TAG, json.encodeToString(entry))
        persistEntry(entry)
    }

    private fun persistEntry(entry: AuditEntry) {
        try {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(entry.timestamp)
            val logFile = File(logDir, "audit_$today.jsonl")
            logFile.appendText(json.encodeToString(entry) + "\n")
            trimOldLogs()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist audit entry", e)
        }
    }

    private fun trimOldLogs() {
        val files = logDir.listFiles()?.sortedByDescending { it.name } ?: return
        if (files.size > 7) {
            files.drop(7).forEach { it.delete() }
        }
    }

    fun getRecentLogs(limit: Int = 50): List<AuditEntry> {
        val files = logDir.listFiles()?.sortedByDescending { it.name } ?: return emptyList()
        val entries = mutableListOf<AuditEntry>()
        for (file in files) {
            if (entries.size >= limit) break
            file.readLines().reversed().forEach { line ->
                if (entries.size >= limit) return@forEach
                try {
                    entries.add(json.decodeFromString<AuditEntry>(line))
                } catch (_: Exception) {}
            }
        }
        return entries
    }
}

