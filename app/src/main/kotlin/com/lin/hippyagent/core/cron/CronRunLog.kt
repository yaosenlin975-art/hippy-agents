package com.lin.hippyagent.core.cron

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

class CronRunLog(context: Context) {

    private val dir: File = File(context.filesDir, "cron_run_log").apply { mkdirs() }
    private val recentFile: File = File(dir, "recent.jsonl")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val recordsInternal = ArrayDeque<CronRunRecord>()
    private val _records = MutableStateFlow<List<CronRunRecord>>(emptyList())
    val records: StateFlow<List<CronRunRecord>> = _records.asStateFlow()

    init {
        loadRecent()
    }

    suspend fun insert(record: CronRunRecord) = mutex.withLock {
        recordsInternal.addLast(record)
        while (recordsInternal.size > MAX_IN_MEMORY) {
            recordsInternal.removeFirst()
        }
        _records.value = recordsInternal.toList()
        appendLine(record)
    }

    fun observeByTask(taskId: String): StateFlow<List<CronRunRecord>> {
        val source = records
        val filtered = MutableStateFlow(source.value.filter { it.taskId == taskId })
        return filtered.asStateFlow()
    }

    fun observeRecent(limit: Int = 100): StateFlow<List<CronRunRecord>> {
        val source = records
        val sliced = MutableStateFlow(source.value.takeLast(limit))
        return sliced.asStateFlow()
    }

    suspend fun deleteOlderThan(thresholdMs: Long) = mutex.withLock {
        recordsInternal.removeAll { it.scheduledTime < thresholdMs }
        _records.value = recordsInternal.toList()
        rewriteFile()
    }

    fun getByTask(taskId: String, limit: Int = 50): List<CronRunRecord> =
        recordsInternal.toList().filter { it.taskId == taskId }
            .sortedByDescending { it.scheduledTime }
            .take(limit)

    fun getRecent(limit: Int = 100): List<CronRunRecord> =
        recordsInternal.toList().sortedByDescending { it.scheduledTime }.take(limit)

    private suspend fun appendLine(record: CronRunRecord) = withContext(Dispatchers.IO) {
        runCatching {
            recentFile.appendText(json.encodeToString(CronRunRecord.serializer(), record) + "\n")
        }.onFailure { e -> Timber.e(e, "CronRunLog: append failed") }
    }

    private suspend fun rewriteFile() = withContext(Dispatchers.IO) {
        runCatching {
            val data = recordsInternal.joinToString("\n") {
                json.encodeToString(CronRunRecord.serializer(), it)
            }
            recentFile.writeText(data)
        }.onFailure { e -> Timber.e(e, "CronRunLog: rewrite failed") }
    }

    private fun loadRecent() {
        runCatching {
            if (!recentFile.exists()) return@runCatching
            recentFile.readLines().takeLast(MAX_IN_MEMORY).forEach { line ->
                if (line.isBlank()) return@forEach
                runCatching {
                    recordsInternal.addLast(json.decodeFromString(CronRunRecord.serializer(), line))
                }
            }
            _records.value = recordsInternal.toList()
        }.onFailure { e -> Timber.w(e, "CronRunLog: load failed") }
    }

    companion object {
        private const val MAX_IN_MEMORY = 500
    }
}
