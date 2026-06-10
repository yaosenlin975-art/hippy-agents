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
data class SkillLockEntry(
    val skillId: String,
    val until: Long,
    val reason: String = ""
)

class SkillLockStore(context: Context) {
    private val file: File = File(File(context.filesDir, "skillopt").apply { mkdirs() }, "locks.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    suspend fun load(): List<SkillLockEntry> = mutex.withLock {
        readFromDisk()
    }

    suspend fun save(entries: List<SkillLockEntry>) = mutex.withLock {
        runCatching {
            val tmp = File(file.parent, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(entries))
            if (file.exists() && !file.delete()) {
                Timber.w("SkillLockStore: failed to delete original")
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Timber.w(it, "SkillLockStore: save failed") }
    }

    suspend fun lockSkill(skillId: String, until: Long, reason: String = "") = mutex.withLock {
        val now = System.currentTimeMillis()
        val current = readFromDisk().filter { it.skillId != skillId && it.until > now }
        val next = current + SkillLockEntry(skillId = skillId, until = until, reason = reason)
        writeToDisk(next)
    }

    suspend fun unlockSkill(skillId: String) = mutex.withLock {
        val next = readFromDisk().filter { it.skillId != skillId }
        writeToDisk(next)
    }

    suspend fun isLocked(skillId: String): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        readFromDisk().any { it.skillId == skillId && it.until > now }
    }

    suspend fun pruneExpired(): Int = mutex.withLock {
        val now = System.currentTimeMillis()
        val current = readFromDisk()
        val next = current.filter { it.until > now }
        if (next.size != current.size) writeToDisk(next)
        current.size - next.size
    }

    private fun readFromDisk(): List<SkillLockEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SkillLockEntry>>(file.readText())
        }.getOrElse {
            Timber.w(it, "SkillLockStore: read failed, returning empty")
            emptyList()
        }
    }

    private fun writeToDisk(entries: List<SkillLockEntry>) {
        runCatching {
            val tmp = File(file.parent, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(entries))
            if (file.exists() && !file.delete()) {
                Timber.w("SkillLockStore: failed to delete original")
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Timber.w(it, "SkillLockStore: writeToDisk failed") }
    }
}
