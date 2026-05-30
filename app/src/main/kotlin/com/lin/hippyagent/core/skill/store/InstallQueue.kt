package com.lin.hippyagent.core.skill.store

import com.lin.hippyagent.core.storage.WorkspaceManager
import com.lin.hippyagent.ui.store.InstallTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.UUID

private val skillIdCleanupRegex = Regex("[^a-z0-9_-]")

class InstallQueue(
    private val scope: CoroutineScope,
    private val storeService: SkillStoreService,
    private val sourceAgentId: String? = null,
    private val workspaceManager: WorkspaceManager? = null
) {
    data class QueueItem(
        val id: String,
        val skill: StoreSkillItem,
        val target: InstallTarget,
        val status: Status,
        val error: String? = null
    ) {
        enum class Status { QUEUED, INSTALLING, COMPLETED, FAILED, CANCELLED }
    }

    private val _items = MutableStateFlow<List<QueueItem>>(emptyList())
    val items: StateFlow<List<QueueItem>> = _items.asStateFlow()

    private val mutex = Mutex()
    private var currentJob: Job? = null

    fun enqueue(skills: List<StoreSkillItem>, target: InstallTarget) {
        val newItems = skills.map { skill ->
            QueueItem(
                id = UUID.randomUUID().toString(),
                skill = skill,
                target = target,
                status = QueueItem.Status.QUEUED
            )
        }
        _items.value = _items.value + newItems
        runNext()
    }

    fun cancel(id: String) {
        _items.value = _items.value.map { item ->
            if (item.id == id && item.status == QueueItem.Status.INSTALLING) {
                currentJob?.cancel()
                item.copy(status = QueueItem.Status.CANCELLED)
            } else if (item.id == id && item.status == QueueItem.Status.QUEUED) {
                item.copy(status = QueueItem.Status.CANCELLED)
            } else item
        }
    }

    fun retry(id: String) {
        _items.value = _items.value.map { item ->
            if (item.id == id && (item.status == QueueItem.Status.FAILED || item.status == QueueItem.Status.CANCELLED)) {
                item.copy(status = QueueItem.Status.QUEUED, error = null)
            } else item
        }
        runNext()
    }

    fun clearCompleted() {
        _items.value = _items.value.filter {
            it.status != QueueItem.Status.COMPLETED
        }
    }

    private fun runNext() {
        scope.launch {
            mutex.withLock {
                val next = _items.value.find { it.status == QueueItem.Status.QUEUED } ?: return@withLock
                currentJob = scope.launch {
                    try {
                        installOne(next)
                    } finally {
                        mutex.withLock {
                            currentJob = null
                        }
                        runNext()
                    }
                }
            }
        }
    }

    private suspend fun installOne(item: QueueItem) {
        updateItem(item.id, status = QueueItem.Status.INSTALLING)
        try {
            val providerKey = when (item.skill.source) {
                SkillSource.LOBEHUB -> "lobehub"
                SkillSource.SKILLS_SH -> "skills_sh"
                SkillSource.CLAWHUB -> "clawhub"
            }
            val result = storeService.install(providerKey, item.skill.identifier)
            result.onSuccess {
                updateItem(item.id, status = QueueItem.Status.COMPLETED)
                Timber.i("Skill installed: ${item.skill.name}")
                if (item.target == InstallTarget.Workspace) {
                    syncToWorkspace(item.skill)
                }
            }.onFailure { e ->
                updateItem(item.id, status = QueueItem.Status.FAILED, error = e.message)
                Timber.e(e, "Failed to install skill: ${item.skill.name}")
            }
        } catch (e: Exception) {
            updateItem(item.id, status = QueueItem.Status.FAILED, error = e.message)
            Timber.e(e, "Failed to install skill: ${item.skill.name}")
        }
    }

    private fun syncToWorkspace(skill: StoreSkillItem) {
        if (sourceAgentId.isNullOrBlank() || workspaceManager == null) return
        try {
            val skillId = skill.identifier.substringAfterLast("@")
                .substringAfterLast("/").replace(skillIdCleanupRegex, "_")
            workspaceManager.syncAgentSkillJson(sourceAgentId, skillId, skill.name, skill.description)
            Timber.i("Synced skill '$skillId' to agent '$sourceAgentId'")
        } catch (e: Exception) {
            Timber.w(e, "Failed to sync skill to agent workspace")
        }
    }

    private fun updateItem(id: String, status: QueueItem.Status, error: String? = null) {
        _items.value = _items.value.map { item ->
            if (item.id == id) item.copy(status = status, error = error) else item
        }
    }
}
