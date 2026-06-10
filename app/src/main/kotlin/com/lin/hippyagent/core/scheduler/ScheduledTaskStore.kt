package com.lin.hippyagent.core.scheduler

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class ScheduledTask(
    val id: String = com.lin.hippyagent.core.pool.FastId.next(),
    val name: String,
    val query: String,
    val cron: String,
    val isoTimestamp: String,
    val originalNlText: String,
    val parseMethod: String = ParseMethod.LLM.name,
    val isOneShot: Boolean = false,
    val nextFireTime: Long = 0L,
    val lastFireTime: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
    val agentId: String = "",
    val sessionId: String = "",
    val silentMode: Boolean = false
)

class ScheduledTaskStore(private val context: Context) {

    private val storeDir: File = File(context.filesDir, "scheduler_store").apply { mkdirs() }
    private val tasksFile: File = File(storeDir, "scheduled_tasks.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val mutex = Mutex()
    private val tasksInternal = mutableListOf<ScheduledTask>()

    private val _tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

    init {
        loadFromDisk()
    }

    suspend fun insert(task: ScheduledTask) = mutex.withLock {
        tasksInternal.add(task)
        persistLocked()
        _tasks.value = tasksInternal.toList()
        Timber.i("ScheduledTaskStore: inserted task ${task.id} cron='${task.cron}'")
    }

    suspend fun update(task: ScheduledTask) = mutex.withLock {
        val idx = tasksInternal.indexOfFirst { it.id == task.id }
        if (idx >= 0) {
            tasksInternal[idx] = task
        } else {
            tasksInternal.add(task)
        }
        persistLocked()
        _tasks.value = tasksInternal.toList()
    }

    suspend fun delete(id: String) = mutex.withLock {
        tasksInternal.removeAll { it.id == id }
        persistLocked()
        _tasks.value = tasksInternal.toList()
    }

    suspend fun getById(id: String): ScheduledTask? = mutex.withLock {
        tasksInternal.find { it.id == id }
    }

    fun getAll(): List<ScheduledTask> = tasksInternal.toList()

    fun getEnabled(): List<ScheduledTask> = tasksInternal.toList().filter { it.enabled }

    fun observeAll(): StateFlow<List<ScheduledTask>> = _tasks

    fun observeEnabled(): StateFlow<List<ScheduledTask>> = _tasks

    private suspend fun persistLocked() = withContext(Dispatchers.IO) {
        runCatching {
            val data = json.encodeToString(ListSerializer(ScheduledTask.serializer()), tasksInternal.toList())
            tasksFile.writeText(data)
        }.onFailure { e ->
            Timber.e(e, "ScheduledTaskStore: persist failed")
        }
    }

    private fun loadFromDisk() {
        runCatching {
            if (!tasksFile.exists()) return@runCatching
            val data = tasksFile.readText()
            if (data.isBlank()) return@runCatching
            val loaded = json.decodeFromString(ListSerializer(ScheduledTask.serializer()), data)
            tasksInternal.clear()
            tasksInternal.addAll(loaded)
            _tasks.value = tasksInternal.toList()
            Timber.i("ScheduledTaskStore: loaded ${loaded.size} tasks")
        }.onFailure { e ->
            Timber.w(e, "ScheduledTaskStore: load failed, starting empty")
        }
    }
}
