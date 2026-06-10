package com.lin.hippyagent.core.skill.curator.skillopt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class SkillAuditRecord(
    val skillId: String,
    val score: Double,
    val callCount: Int,
    val successRate: Double,
    val status: String,
    val locked: Boolean = false,
    val archived: Boolean = false
)

@Serializable
data class SkillAuditReport(
    val generatedAt: Long = System.currentTimeMillis(),
    val records: List<SkillAuditRecord> = emptyList()
)

class SkillAudit(
    private val context: Context,
    private val eventStore: SkillCallEventStore = SkillCallEventStore(context),
    private val scorer: SkillScorer = SkillScorer(),
    private val archiveStore: SkillArchiveStore = SkillArchiveStore(context),
    private val lockStore: SkillLockStore = SkillLockStore(context)
) {
    private val reportFile: File = File(File(context.filesDir, "skillopt").apply { mkdirs() }, "audit_report.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    suspend fun runAudit(knownSkillIds: Collection<String> = emptyList()): SkillAuditReport = withContext(Dispatchers.IO) {
        lockStore.pruneExpired()
        val events = eventStore.loadAll()
        val grouped = events.groupBy { it.skillId }
        val ids = (grouped.keys + knownSkillIds).distinct()

        val records = ids.map { skillId ->
            val skillEvents = grouped[skillId].orEmpty()
            val score = scorer.score(skillEvents)
            val callCount = skillEvents.size
            val successCount = skillEvents.count { it.success }
            val successRate = if (callCount == 0) 0.0 else successCount.toDouble() / callCount
            val locked = lockStore.isLocked(skillId)
            val archived = archiveStore.isArchived(skillId)
            val status = when {
                locked -> "LOCKED"
                archived -> "ARCHIVED"
                scorer.recommendArchive(skillEvents) -> "WARN"
                callCount == 0 -> "IDLE"
                else -> "OK"
            }
            SkillAuditRecord(
                skillId = skillId,
                score = score,
                callCount = callCount,
                successRate = successRate,
                status = status,
                locked = locked,
                archived = archived
            )
        }.sortedBy { it.score }

        val report = SkillAuditReport(records = records)
        persist(report)

        val archiveCandidates = records.filter { it.status == "WARN" }
        for (candidate in archiveCandidates) {
            if (!archiveStore.isArchived(candidate.skillId) && !lockStore.isLocked(candidate.skillId)) {
                archiveStore.archive(candidate.skillId, candidate.score, reason = "low score")
            }
        }

        Timber.d("SkillAudit: generated ${records.size} records, ${archiveCandidates.size} archived")
        report
    }

    suspend fun loadReport(): SkillAuditReport = withContext(Dispatchers.IO) {
        if (!reportFile.exists()) return@withContext SkillAuditReport()
        runCatching {
            json.decodeFromString<SkillAuditReport>(reportFile.readText())
        }.getOrElse {
            Timber.w(it, "SkillAudit: loadReport failed")
            SkillAuditReport()
        }
    }

    private fun persist(report: SkillAuditReport) {
        runCatching {
            val tmp = File(reportFile.parent, "${reportFile.name}.tmp")
            tmp.writeText(json.encodeToString(report))
            if (reportFile.exists() && !reportFile.delete()) {
                Timber.w("SkillAudit: failed to delete original report")
            }
            if (!tmp.renameTo(reportFile)) {
                tmp.copyTo(reportFile, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Timber.w(it, "SkillAudit: persist failed") }
    }
}
