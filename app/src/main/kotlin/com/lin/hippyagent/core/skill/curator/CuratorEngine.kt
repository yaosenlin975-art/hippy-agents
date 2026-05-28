package com.lin.hippyagent.core.skill.curator

import android.content.Context
import com.lin.hippyagent.core.memory.DreamPhase
import com.lin.hippyagent.core.skill.SkillManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Curator 引擎 — 后台自动技能管理
 *
 * 三阶段 Pipeline 与 DreamWorker 对齐：
 * - LIGHT: 清理过期执行历史 + 技能使用计数更新
 * - DEEP: 技能提取 + 技能合并 + 技能优化（核心）
 * - REM: 工具偏好分析 + 错误模式学习 + 低频归档
 *
 * 参考: Hermes curator/__init__.py — CuratorEngine
 */
class CuratorEngine(
    private val context: Context,
    private val historyStore: ExecutionHistoryStore,
    private val extractor: SkillExtractor,
    private val merger: SkillMerger,
    private val optimizer: SkillOptimizer,
    private val skillManager: SkillManager?
) {
    private val curatorDir: File by lazy {
        File(context.filesDir, "curator/skills").apply { mkdirs() }
    }
    private val json = kotlinx.serialization.json.Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 运行 Curator 阶段 */
    suspend fun runPhase(phase: DreamPhase): CuratorReport = withContext(Dispatchers.IO) {
        try {
            when (phase) {
                DreamPhase.LIGHT -> runLight()
                DreamPhase.DEEP -> runDeep()
                DreamPhase.REM -> runRem()
            }
        } catch (e: Exception) {
            Timber.e(e, "CuratorEngine: $phase failed")
            CuratorReport(phase = phase, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    /** LIGHT: 清理 + 计数更新 */
    private suspend fun runLight(): CuratorReport {
        val report = CuratorReport(phase = DreamPhase.LIGHT)
        val pruned = historyStore.pruneOldRecords(daysOld = 7)

        // 更新技能使用计数
        val skills = loadAutoSkills()
        for (skill in skills) {
            val recentUsage = historyStore.countRecentUsage(skill.id, days = 7)
            if (recentUsage != skill.usageCount) {
                saveAutoSkill(skill.copy(usageCount = recentUsage))
            }
        }

        return report.copy(optimized = pruned)
    }

    /** DEEP: 提取 + 合并 + 优化 */
    private suspend fun runDeep(): CuratorReport {
        val report = CuratorReport(phase = DreamPhase.DEEP)
        val errors = mutableListOf<String>()
        val details = mutableListOf<String>()

        // 1. 提取新技能
        val histories = historyStore.getRecentSuccessful(limit = 50)
        var extracted = 0
        for (history in histories) {
            try {
                val skill = extractor.extract(history)
                if (skill != null) {
                    val existing = findSimilar(skill)
                    if (existing == null) {
                        saveAutoSkill(skill)
                        extracted++
                        details.add("提取技能: ${skill.name}")
                    }
                }
            } catch (e: Exception) {
                errors.add("提取失败: ${e.message}")
            }
        }

        // 2. 合并相似技能
        val allSkills = loadAutoSkills()
        val mergeResults = merger.mergeSimilar(allSkills)
        var merged = 0
        for (result in mergeResults) {
            result.sourceIds.forEach { deleteAutoSkill(it) }
            saveAutoSkill(result.merged)
            merged++
            details.add("合并技能: ${result.sourceIds.joinToString(", ")} → ${result.merged.name}")
        }

        // 3. 优化高频技能
        val optimized = optimizer.optimizeHighUsage(loadAutoSkills())
        if (optimized > 0) details.add("优化 $optimized 个技能")

        return report.copy(
            extracted = extracted,
            merged = merged,
            optimized = optimized,
            details = details,
            errors = errors
        )
    }

    /** REM: 偏好分析 + 错误学习 + 归档 */
    private suspend fun runRem(): CuratorReport {
        val report = CuratorReport(phase = DreamPhase.REM)
        val errors = mutableListOf<String>()

        // 工具使用偏好
        val toolUsage = historyStore.getToolUsageStats()
        if (toolUsage.isNotEmpty()) {
            val topTool = toolUsage.maxByOrNull { it.value }
            Timber.d("Curator REM: top tool=${topTool?.key} (${topTool?.value} uses)")
        }

        // 错误模式
        val errorPatterns = historyStore.getErrorPatterns()

        // 低频技能归档
        val archived = archiveLowFrequency()

        return report.copy(archived = archived, errors = errors)
    }

    /** 归档低频技能（30天未使用且使用次数<3） */
    private suspend fun archiveLowFrequency(): Int {
        var count = 0
        val skills = loadAutoSkills()
        val now = System.currentTimeMillis()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

        for (skill in skills) {
            val lastUsed = skill.lastUsedAt ?: skill.createdAt
            if (now - lastUsed > thirtyDaysMs && skill.usageCount < 3) {
                archiveSkill(skill.id)
                count++
            }
        }
        return count
    }

    // ---- 技能存储 (File-based, 独立于 SkillManager) ----

    /** 保存自动生成的技能 */
    private fun saveAutoSkill(skill: CuratorSkillManifest) {
        val file = File(curatorDir, "${skill.id}.json")
        file.writeText(json.encodeToString(skill))
    }

    /** 获取所有自动生成的技能（供 UI 展示） */
    fun getAutoSkills(): List<CuratorSkillManifest> = loadAutoSkills()

    /** 获取自动技能数量统计 */
    fun getAutoSkillStats(): Triple<Int, Int, Int> {
        val skills = loadAutoSkills()
        val active = skills.count { it.usageCount > 0 }
        val archived = curatorDir.listFiles()
            ?.count { it.name.startsWith("_archived_") } ?: 0
        return Triple(skills.size, active, archived)
    }

    /** 加载所有自动生成的技能 */
    private fun loadAutoSkills(): List<CuratorSkillManifest> {
        return curatorDir.listFiles()
            ?.filter { it.extension == "json" && !it.name.startsWith("_") }
            ?.mapNotNull { file ->
                runCatching {
                    json.decodeFromString<CuratorSkillManifest>(file.readText())
                }.getOrNull()
            }
            ?: emptyList()
    }

    /** 查找相似技能 */
    private fun findSimilar(target: CuratorSkillManifest): CuratorSkillManifest? {
        return loadAutoSkills().firstOrNull { existing ->
            merger.calculateSimilarity(existing, target) >= 0.8f
        }
    }

    /** 删除技能 */
    private fun deleteAutoSkill(id: String) {
        File(curatorDir, "$id.json").delete()
    }

    /** 归档技能 */
    private fun archiveSkill(id: String) {
        val file = File(curatorDir, "$id.json")
        if (file.exists()) {
            val archivedFile = File(curatorDir, "_archived_$id.json")
            file.renameTo(archivedFile)
        }
    }
}
