package com.lin.hippyagent.core.skill.store

import android.content.Context
import com.lin.hippyagent.core.linux.rootfsDir
import com.lin.hippyagent.core.skill.SkillManager
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
import java.io.File
import java.util.UUID

private val skillIdCleanupRegex = Regex("[^a-z0-9_-]")

class InstallQueue(
    private val scope: CoroutineScope,
    private val storeService: SkillStoreService,
    private val context: Context,
    private val skillManager: SkillManager,
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

    interface Listener {
        fun onInstallCompleted(skill: StoreSkillItem)
        fun onInstallFailed(skill: StoreSkillItem, error: String?)
    }

    private val _items = MutableStateFlow<List<QueueItem>>(emptyList())
    val items: StateFlow<List<QueueItem>> = _items.asStateFlow()

    var listener: Listener? = null

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
            val next = mutex.withLock {
                _items.value.find { it.status == QueueItem.Status.QUEUED } ?: return@launch
            }
            currentJob = scope.launch {
                try {
                    installOne(next)
                } finally {
                    currentJob = null
                    runNext()
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
                SkillSource.SKILLHUB -> "clawhub"
            }
            val result = storeService.install(providerKey, item.skill.identifier)
            result.onSuccess {
                val copied = copyFromRootfsToPool(item.skill)
                if (copied) {
                    skillManager.indexManager.rebuildIndex()
                    Timber.i("Skill copied from rootfs to pool and index rebuilt: %s", item.skill.name)
                } else {
                    Timber.w("Skill not found in rootfs after install: %s", item.skill.name)
                }
                updateItem(item.id, status = QueueItem.Status.COMPLETED)
                Timber.i("Skill installed: %s", item.skill.name)
                listener?.onInstallCompleted(item.skill)
                if (copied && item.target == InstallTarget.Workspace) {
                    syncToWorkspace(item.skill)
                }
            }.onFailure { e ->
                updateItem(item.id, status = QueueItem.Status.FAILED, error = e.message)
                Timber.e(e, "Failed to install skill: %s", item.skill.name)
                listener?.onInstallFailed(item.skill, e.message)
            }
        } catch (e: Exception) {
            updateItem(item.id, status = QueueItem.Status.FAILED, error = e.message)
            Timber.e(e, "Failed to install skill: %s", item.skill.name)
        }
    }

    /**
     * Scan rootfs for the installed skill directory and copy it to app skill pool.
     * CLI tools install to various paths inside PRoot:
     *   - ClawHub: /root/.clawhub/skills/
     *   - LobeHub: /root/.lobehub/skills/
     *   - Skills.sh: /root/skills/ or /root/.skills/
     */
    private fun copyFromRootfsToPool(skill: StoreSkillItem): Boolean {
        val rootfs = context.rootfsDir
        val normalizedId = SkillIdNormalizer.normalize(skill.identifier)
        val skillPoolDir = File(context.filesDir, "skills")

        val searchRoots = listOf(
            File(rootfs, "root/.clawhub/skills"),
            File(rootfs, "root/.lobehub/skills"),
            File(rootfs, "root/.skills"),
            File(rootfs, "root/skills")
        )

        for (searchRoot in searchRoots) {
            if (!searchRoot.exists() || !searchRoot.isDirectory) continue
            val matchDir = searchRoot.listFiles()?.find { dir ->
                dir.isDirectory && SkillIdNormalizer.normalize(dir.name) == normalizedId
            }
            if (matchDir != null) {
                val targetDir = File(skillPoolDir, matchDir.name)
                if (targetDir.exists()) targetDir.deleteRecursively()
                matchDir.copyRecursively(targetDir, overwrite = true)
                Timber.i("Copied skill from %s to %s", matchDir.absolutePath, targetDir.absolutePath)
                return true
            }
        }

        val lastSegment = skill.identifier.substringAfterLast("/").substringAfterLast("@")
        val normalizedLast = SkillIdNormalizer.normalize(lastSegment)
        if (normalizedLast == normalizedId) return false

        for (searchRoot in searchRoots) {
            if (!searchRoot.exists() || !searchRoot.isDirectory) continue
            val matchDir = searchRoot.listFiles()?.find { dir ->
                dir.isDirectory && SkillIdNormalizer.normalize(dir.name) == normalizedLast
            }
            if (matchDir != null) {
                val targetDir = File(skillPoolDir, matchDir.name)
                if (targetDir.exists()) targetDir.deleteRecursively()
                matchDir.copyRecursively(targetDir, overwrite = true)
                Timber.i("Copied skill from %s to %s", matchDir.absolutePath, targetDir.absolutePath)
                return true
            }
        }
        return false
    }

    private fun syncToWorkspace(skill: StoreSkillItem) {
        if (sourceAgentId.isNullOrBlank() || workspaceManager == null) return
        try {
            val skillId = skill.identifier.substringAfterLast("@")
                .substringAfterLast("/").replace(skillIdCleanupRegex, "_")
            workspaceManager.syncAgentSkillJson(sourceAgentId, skillId, skill.name, skill.description)
            Timber.i("Synced skill '%s' to agent '%s'", skillId, sourceAgentId)
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