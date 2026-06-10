package com.lin.hippyagent.core.skill.curator.skillopt

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class SkillArchiveEntry(
    val skillId: String,
    val archivedAt: Long,
    val lastScore: Double,
    val reason: String = ""
)

@Serializable
data class SkillArchiveFile(
    val version: Int = 1,
    val entries: List<SkillArchiveEntry> = emptyList()
)

class SkillArchiveStore(context: Context) {
    private val file: File = File(File(context.filesDir, "skillopt").apply { mkdirs() }, "archive.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    suspend fun list(): List<SkillArchiveEntry> = mutex.withLock { readFromDisk().entries }

    suspend fun isArchived(skillId: String): Boolean = mutex.withLock {
        readFromDisk().entries.any { it.skillId == skillId }
    }

    suspend fun archive(skillId: String, lastScore: Double, reason: String = "") = mutex.withLock {
        val now = System.currentTimeMillis()
        val current = readFromDisk()
        if (current.entries.any { it.skillId == skillId }) return@withLock
        val next = SkillArchiveFile(
            entries = current.entries + SkillArchiveEntry(
                skillId = skillId, archivedAt = now, lastScore = lastScore, reason = reason
            )
        )
        writeToDisk(next)
    }

    suspend fun restore(skillId: String): Boolean = mutex.withLock {
        val current = readFromDisk()
        val remaining = current.entries.filter { it.skillId != skillId }
        if (remaining.size == current.entries.size) return@withLock false
        writeToDisk(current.copy(entries = remaining))
        true
    }

    private fun readFromDisk(): SkillArchiveFile {
        if (!file.exists()) return SkillArchiveFile()
        return runCatching {
            json.decodeFromString<SkillArchiveFile>(file.readText())
        }.getOrElse {
            Timber.w(it, "SkillArchiveStore: read failed, returning empty")
            SkillArchiveFile()
        }
    }

    private fun writeToDisk(data: SkillArchiveFile) {
        runCatching {
            val tmp = File(file.parent, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(data))
            if (file.exists() && !file.delete()) {
                Timber.w("SkillArchiveStore: failed to delete original")
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Timber.w(it, "SkillArchiveStore: writeToDisk failed") }
    }
}
