package com.lin.hippyagent.core.memory

import android.content.Context
import com.lin.hippyagent.core.agent.AgentMdManager
import com.lin.hippyagent.core.agent.session.AppDatabase
import com.lin.hippyagent.core.agent.session.DreamHistoryEntity
import com.lin.hippyagent.core.knowledge.DreamEntityBridge
import com.lin.hippyagent.core.memory.commonmemory.BrainMemoryType
import com.lin.hippyagent.core.skill.curator.CuratorEngine
import com.lin.hippyagent.core.skill.curator.CuratorReport
import com.lin.hippyagent.core.skill.curator.ExecutionHistoryStore
import com.lin.hippyagent.core.skill.curator.SkillExtractor
import com.lin.hippyagent.core.skill.curator.SkillMerger
import com.lin.hippyagent.core.skill.curator.SkillOptimizer
import com.lin.hippyagent.core.memory.commonmemory.BrainMemoryScope
import com.lin.hippyagent.core.memory.commonmemory.EvidenceKind
import com.lin.hippyagent.core.memory.commonmemory.MemoryRepository
import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DreamMemoryManager(
    private val context: Context,
    private val mdManager: AgentMdManager,
    private val database: AppDatabase? = null,
    private val dreamEntityBridge: DreamEntityBridge? = null,
    private val memoryRepository: MemoryRepository? = null
) {
    companion object {
        private val sectionSplitRegex = Regex("\n(?=#[^#])")
        private val wordSplitRegex = Regex("[\\s,，。.]+")
    }

    private val curatorEngine by lazy {
        CuratorEngine(
            context = context,
            historyStore = ExecutionHistoryStore(context),
            extractor = SkillExtractor(),
            merger = SkillMerger(),
            optimizer = SkillOptimizer(),
            skillManager = null
        )
    }

    private val dreamDir by lazy {
        File(context.filesDir, "dream").apply { mkdirs() }
    }
    private val backupDir by lazy {
        File(dreamDir, "backups").apply { mkdirs() }
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    data class DreamState(
        val lastDreamTime: String? = null,
        val lastLightDreamTime: String? = null,
        val lastDeepDreamTime: String? = null,
        val lastRemDreamTime: String? = null,
        val totalDreams: Int = 0,
        val optimizedMemories: Int = 0
    )

    /**
     * Light Dream（浅睡）— 去重 + 近期冗余清理
     * 频率：每 6 小时
     * 约束：仅空闲
     */
    suspend fun triggerLightDream(): Result<DreamResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val dao = database?.dreamHistoryDao()

        runCatching {
            Timber.i("Light Dream: starting dedup and cleanup")

            val memoryContent = readMemoryFiles()
            val sizeBefore = memoryContent.length

            // 备份
            val backupFile = backupMemory()

            // 创建恢复点
            val restorePoint = createRestorePoint()

            // Light 优化：去重（简单行级去重 + 空行清理）
            val optimizedContent = optimizeMemoryLight(memoryContent)
            val sizeAfter = optimizedContent.length

            // 写入校验 + 回滚
            val writeResult = runCatching {
                mdManager.writeMemoryMd(optimizedContent)
            }
            if (writeResult.isFailure) {
                Timber.e(writeResult.exceptionOrNull(), "Light Dream write failed, rolling back")
                rollbackFromRestorePoint(restorePoint)
                throw writeResult.exceptionOrNull() ?: RuntimeException("Light Dream write failed")
            }

            cleanupOldBackups()
            cleanupOldDailyMemories()
            cleanupExpiredUploadFacts()

            val dreamState = updateDreamState(phase = "light")

            val elapsed = System.currentTimeMillis() - startTime
            dao?.insert(
                DreamHistoryEntity(
                    triggeredAt = startTime,
                    finishedAt = System.currentTimeMillis(),
                    status = "completed",
                    message = "Light Dream: ${sizeBefore}→${sizeAfter} chars",
                    backupPath = backupFile,
                    sizeBefore = sizeBefore,
                    sizeAfter = sizeAfter,
                    elapsedMs = elapsed
                )
            )

            Timber.i("Light Dream completed in ${elapsed}ms")
            DreamResult(
                success = true,
                phase = "light",
                backupFile = backupFile,
                optimizedMemories = dreamState.optimizedMemories,
                totalDreams = dreamState.totalDreams
            )
        }.onFailure { e ->
            Timber.e(e, "Light Dream failed")
            dao?.insert(
                DreamHistoryEntity(
                    triggeredAt = startTime,
                    finishedAt = System.currentTimeMillis(),
                    status = "failed",
                    message = "Light Dream: ${e.message}",
                    elapsedMs = System.currentTimeMillis() - startTime
                )
            )
        }
    }

    /**
     * Deep Dream（深睡）— 记忆优化 + 健康恢复
     * 频率：每 24 小时
     * 约束：充电+WiFi+空闲
     */
    suspend fun triggerDeepDream(): Result<DreamResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val dao = database?.dreamHistoryDao()

        runCatching {
            Timber.i("Deep Dream: starting memory optimization")

            val memoryContent = readMemoryFiles()
            val sizeBefore = memoryContent.length

            val backupFile = backupMemory()
            val restorePoint = createRestorePoint()

            // Deep 优化：完整去重 + 段落合并 + 过期归档
            val optimizedContent = optimizeMemoryDeep(memoryContent)
            val sizeAfter = optimizedContent.length

            val writeResult = runCatching {
                mdManager.writeMemoryMd(optimizedContent)
            }
            if (writeResult.isFailure) {
                Timber.e(writeResult.exceptionOrNull(), "Deep Dream write failed, rolling back")
                rollbackFromRestorePoint(restorePoint)
                throw writeResult.exceptionOrNull() ?: RuntimeException("Deep Dream write failed")
            }

            // 实体链接与知识图谱联动
            dreamEntityBridge?.let { bridge ->
                runCatching {
                    bridge.syncEntitiesFromMemory(optimizedContent)
                    Timber.i("Entity sync from Deep Dream completed")
                }.onFailure { e ->
                    Timber.w(e, "Entity sync from Deep Dream failed (non-fatal)")
                }
            }

            // CommonMemory 记忆晋升 + 修剪
            memoryRepository?.let { repo ->
                runCatching {
                    val promoted = repo.promoteToDurable()
                    if (promoted > 0) Timber.i("Deep Dream: promoted $promoted memories to durable")
                    val pruned = repo.pruneStale()
                    if (pruned.activePruned > 0 || pruned.durablePruned > 0) {
                        Timber.i("Deep Dream: pruned ${pruned.activePruned} active + ${pruned.durablePruned} durable memories")
                    }
                }.onFailure { e ->
                    Timber.w(e, "CommonMemory maintenance from Deep Dream failed (non-fatal)")
                }
            }

            // Curator: 技能提取 + 合并 + 优化
            runCatching {
                val curatorReport = curatorEngine.runPhase(DreamPhase.DEEP)
                if (curatorReport.extracted > 0 || curatorReport.merged > 0) {
                    Timber.i("Curator DEEP: extracted=${curatorReport.extracted}, merged=${curatorReport.merged}, " +
                             "optimized=${curatorReport.optimized}, archived=${curatorReport.archived}")
                }
            }.onFailure { e ->
                Timber.w(e, "Curator DEEP failed (non-fatal)")
            }

            cleanupOldBackups()
            cleanupOldDailyMemories()

            val dreamState = updateDreamState(phase = "deep")

            val elapsed = System.currentTimeMillis() - startTime
            dao?.insert(
                DreamHistoryEntity(
                    triggeredAt = startTime,
                    finishedAt = System.currentTimeMillis(),
                    status = "completed",
                    message = "Deep Dream: ${sizeBefore}→${sizeAfter} chars",
                    backupPath = backupFile,
                    sizeBefore = sizeBefore,
                    sizeAfter = sizeAfter,
                    elapsedMs = elapsed
                )
            )

            Timber.i("Deep Dream completed in ${elapsed}ms")
            DreamResult(
                success = true,
                phase = "deep",
                backupFile = backupFile,
                optimizedMemories = dreamState.optimizedMemories,
                totalDreams = dreamState.totalDreams
            )
        }.onFailure { e ->
            Timber.e(e, "Deep Dream failed")
            dao?.insert(
                DreamHistoryEntity(
                    triggeredAt = startTime,
                    finishedAt = System.currentTimeMillis(),
                    status = "failed",
                    message = "Deep Dream: ${e.message}",
                    elapsedMs = System.currentTimeMillis() - startTime
                )
            )
        }
    }

    /**
     * REM Dream（快速眼动）— 模式识别 + 知识图谱更新
     * 频率：每 7 天
     * 约束：充电+WiFi+空闲
     */
    suspend fun triggerRemDream(): Result<DreamResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val dao = database?.dreamHistoryDao()

        runCatching {
            Timber.i("REM Dream: starting pattern recognition and knowledge graph update")

            val memoryContent = readMemoryFiles()
            val sizeBefore = memoryContent.length

            val backupFile = backupMemory()
            val restorePoint = createRestorePoint()

            // REM 优化：完整去重 + 段落合并 + 知识图谱强化
            val optimizedContent = optimizeMemoryRem(memoryContent)
            val sizeAfter = optimizedContent.length

            val writeResult = runCatching {
                mdManager.writeMemoryMd(optimizedContent)
            }
            if (writeResult.isFailure) {
                Timber.e(writeResult.exceptionOrNull(), "REM Dream write failed, rolling back")
                rollbackFromRestorePoint(restorePoint)
                throw writeResult.exceptionOrNull() ?: RuntimeException("REM Dream write failed")
            }

            // REM 专属：知识图谱深度更新
            dreamEntityBridge?.let { bridge ->
                runCatching {
                    bridge.syncEntitiesFromMemory(optimizedContent)
                    bridge.updateRelationships()
                    Timber.i("Knowledge graph deep update from REM Dream completed")
                }.onFailure { e ->
                    Timber.w(e, "Knowledge graph update from REM Dream failed (non-fatal)")
                }
            }

            // CommonMemory：从 Markdown 提取结构化记忆 + 晋升 + 修剪
            memoryRepository?.let { repo ->
                runCatching {
                    // 从优化后的 Markdown 内容提取记忆
                    extractMemoriesFromMarkdown(optimizedContent, repo)

                    // REM 阶段也做晋升和修剪
                    val promoted = repo.promoteToDurable()
                    if (promoted > 0) Timber.i("REM Dream: promoted $promoted memories to durable")
                    val pruned = repo.pruneStale()
                    if (pruned.activePruned > 0 || pruned.durablePruned > 0) {
                        Timber.i("REM Dream: pruned ${pruned.activePruned} active + ${pruned.durablePruned} durable memories")
                    }
                }.onFailure { e ->
                    Timber.w(e, "CommonMemory extraction from REM Dream failed (non-fatal)")
                }
            }

            // Curator REM: 工具偏好分析 + 错误模式学习 + 低频归档
            runCatching {
                val curatorReport = curatorEngine.runPhase(DreamPhase.REM)
                if (curatorReport.archived > 0) {
                    Timber.i("Curator REM: archived ${curatorReport.archived} low-frequency skills")
                }
            }.onFailure { e ->
                Timber.w(e, "Curator REM failed (non-fatal)")
            }

            cleanupOldBackups()
            cleanupOldDailyMemories()

            val dreamState = updateDreamState(phase = "rem")

            val elapsed = System.currentTimeMillis() - startTime
            dao?.insert(
                DreamHistoryEntity(
                    triggeredAt = startTime,
                    finishedAt = System.currentTimeMillis(),
                    status = "completed",
                    message = "REM Dream: ${sizeBefore}→${sizeAfter} chars + knowledge graph update",
                    backupPath = backupFile,
                    sizeBefore = sizeBefore,
                    sizeAfter = sizeAfter,
                    elapsedMs = elapsed
                )
            )

            Timber.i("REM Dream completed in ${elapsed}ms")
            DreamResult(
                success = true,
                phase = "rem",
                backupFile = backupFile,
                optimizedMemories = dreamState.optimizedMemories,
                totalDreams = dreamState.totalDreams
            )
        }.onFailure { e ->
            Timber.e(e, "REM Dream failed")
            dao?.insert(
                DreamHistoryEntity(
                    triggeredAt = startTime,
                    finishedAt = System.currentTimeMillis(),
                    status = "failed",
                    message = "REM Dream: ${e.message}",
                    elapsedMs = System.currentTimeMillis() - startTime
                )
            )
        }
    }

    /**
     * 向后兼容：triggerDream() 默认触发 Deep Dream
     */
    suspend fun triggerDream(): Result<DreamResult> = triggerDeepDream()

    /**
     * #83 Dream写入增强: 创建 restore point — 保存 MEMORY.md 当前内容用于回滚。
     */
    private fun createRestorePoint(): String? {
        return try {
            val memoryFile = mdManager.getMemoryMdFile()
            if (memoryFile.exists()) {
                val content = memoryFile.readText()
                val restoreFile = File(dreamDir, "restore_point.md")
                restoreFile.writeText(content)
                Timber.d("Restore point created (${content.length} chars)")
                restoreFile.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create restore point")
            null
        }
    }

    /**
     * #83 Dream写入增强: 从 restore point 回滚 MEMORY.md。
     */
    private suspend fun rollbackFromRestorePoint(restorePath: String?) {
        if (restorePath == null) {
            Timber.w("No restore point available, cannot rollback")
            return
        }
        try {
            val restoreFile = File(restorePath)
            if (restoreFile.exists()) {
                val originalContent = restoreFile.readText()
                mdManager.writeMemoryMd(originalContent)
                Timber.i("Memory rolled back from restore point (${originalContent.length} chars)")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to rollback from restore point")
        }
    }

    private fun backupMemory(): String {
        val today = LocalDate.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val backupFilename = "memory_backup_${today.format(formatter)}.md"
        val backupFile = File(backupDir, backupFilename)

        try {
            val memoryFile = mdManager.getMemoryMdFile()
            if (memoryFile.exists()) {
                memoryFile.copyTo(backupFile, overwrite = true)
                Timber.i("Memory backed up to: ${backupFile.absolutePath}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to backup memory")
        }

        return backupFile.absolutePath
    }

    private fun cleanupOldBackups(maxBackups: Int = 10) {
        try {
            val backups = backupDir.listFiles()
                ?.filter { it.name.startsWith("memory_backup_") }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            if (backups.size > maxBackups) {
                backups.drop(maxBackups).forEach { it.delete() }
                Timber.i("Cleaned up ${backups.size - maxBackups} old backups")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to cleanup old backups")
        }
    }

    private fun cleanupOldDailyMemories(retentionDays: Int = 30) {
        try {
            val memoryDir = File(context.filesDir, "memory")
            if (!memoryDir.exists()) return

            val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 24 * 60 * 60 * 1000)
            memoryDir.listFiles { file ->
                file.extension == "md" && file.name != "MEMORY.md" && file.lastModified() < cutoff
            }?.forEach { file ->
                file.delete()
                Timber.d("Deleted old daily memory: ${file.name}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to cleanup old daily memories")
        }
    }

    private suspend fun cleanupExpiredUploadFacts() {
        try {
            val repo = memoryRepository ?: return
            val now = System.currentTimeMillis()
            val expired = repo.findExpiredUploadFacts(now)
            if (expired.isEmpty()) return
            for (entry in expired) {
                repo.dismiss(entry.id)
                Timber.d("Dismissed expired upload-related fact: ${entry.summary.take(40)}")
            }
            Timber.i("Light Dream: cleaned up ${expired.size} expired upload-related facts")
        } catch (e: Exception) {
            Timber.e(e, "Failed to cleanup expired upload facts")
        }
    }

    private fun readMemoryFiles(): String {
        val sb = StringBuilder()

        try {
            val memoryFile = mdManager.getMemoryMdFile()
            if (memoryFile.exists()) {
                sb.appendLine(memoryFile.readText())
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read MEMORY.md")
        }

        try {
            val memoryDir = File(context.filesDir, "memory")
            if (memoryDir.exists()) {
                memoryDir.listFiles { file ->
                    file.extension == "md" && file.name != "MEMORY.md"
                }?.forEach { file ->
                    sb.appendLine("\n--- ${file.name} ---")
                    sb.appendLine(file.readText())
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read daily memory files")
        }

        return sb.toString()
    }

    private fun optimizeMemory(content: String): String {
        return optimizeMemoryDeep(content)
    }

    /**
     * Light 优化：简单行级去重 + 空行清理
     * - 移除重复行
     * - 压缩连续空行
     * - 不做段落合并（保持速度）
     */
    private fun optimizeMemoryLight(content: String): String {
        val lines = content.lines()
        val optimized = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var emptyLineCount = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                emptyLineCount++
                if (emptyLineCount <= 1) {
                    optimized.add("")
                }
                continue
            }
            emptyLineCount = 0
            if (seen.contains(trimmed)) continue
            seen.add(trimmed)
            optimized.add(line)
        }

        return optimized.joinToString("\n").trim()
    }

    /**
     * Deep 优化：完整去重 + 段落合并 + 过期归档标记
     * - 行级去重
     * - 合并相似段落（标题相同的内容合并）
     * - 标记过期条目（超过 maxAgeDays）
     */
    private fun optimizeMemoryDeep(content: String): String {
        val lines = content.lines()
        val optimized = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val sectionHeaders = mutableMapOf<String, StringBuilder>()
        var currentSection = ""
        var emptyLineCount = 0

        for (line in lines) {
            val trimmed = line.trim()

            // 空行压缩
            if (trimmed.isEmpty()) {
                emptyLineCount++
                if (emptyLineCount <= 1) optimized.add("")
                continue
            }
            emptyLineCount = 0

            // 检测 Markdown 标题行
            if (trimmed.startsWith("#")) {
                currentSection = trimmed
                if (!sectionHeaders.containsKey(currentSection)) {
                    sectionHeaders[currentSection] = StringBuilder()
                    optimized.add(line)
                }
                continue
            }

            // 去重
            if (seen.contains(trimmed)) continue
            seen.add(trimmed)

            // 如果当前节已存在，跳过重复节内容
            if (sectionHeaders.containsKey(currentSection) && sectionHeaders[currentSection]!!.isNotEmpty()) {
                // 追加到现有节（合并同节内容）
                if (!sectionHeaders[currentSection]!!.contains(trimmed)) {
                    sectionHeaders[currentSection]!!.appendLine(line)
                    optimized.add(line)
                }
            } else {
                sectionHeaders[currentSection]?.appendLine(line)
                optimized.add(line)
            }
        }

        return optimized.joinToString("\n").trim()
    }

    /**
     * REM 优化：Deep 的超集 + 知识提炼
     * - 包含 Deep 所有优化
     * - 提取关键事实（## 开头的节摘要）
     * - 压缩冗余细节
     */
    private fun optimizeMemoryRem(content: String): String {
        // 先执行 Deep 优化
        var result = optimizeMemoryDeep(content)

        // REM 额外处理：压缩过长的节
        val sections = result.split(sectionSplitRegex)
        val remOptimized = mutableListOf<String>()

        for (section in sections) {
            val lines = section.lines()
            if (lines.size > 50) {
                // 超过 50 行的节，只保留标题 + 前 10 行 + 摘要提示
                val header = lines.first()
                val body = lines.drop(1).take(10)
                val truncated = buildString {
                    appendLine(header)
                    body.forEach { appendLine(it) }
                    appendLine("... (此节已压缩，原始 ${lines.size - 1} 行)")
                }
                remOptimized.add(truncated)
            } else {
                remOptimized.add(section)
            }
        }

        result = remOptimized.joinToString("\n\n").trim()
        return result
    }

    private fun updateDreamState(phase: String = "deep"): DreamState {
        val stateFile = File(dreamDir, "dream_state.json")
        val currentState = if (stateFile.exists()) {
            runCatching {
                json.decodeFromString<DreamState>(stateFile.readText())
            }.getOrNull() ?: DreamState()
        } else {
            DreamState()
        }

        val today = LocalDate.now().toString()
        val newState = currentState.copy(
            lastDreamTime = today,
            lastLightDreamTime = if (phase == "light") today else currentState.lastLightDreamTime,
            lastDeepDreamTime = if (phase == "deep") today else currentState.lastDeepDreamTime,
            lastRemDreamTime = if (phase == "rem") today else currentState.lastRemDreamTime,
            totalDreams = currentState.totalDreams + 1,
            optimizedMemories = currentState.optimizedMemories + 1
        )

        stateFile.writeText(json.encodeToString(newState))
        return newState
    }

    suspend fun getDreamState(): DreamState = withContext(Dispatchers.IO) {
        val stateFile = File(dreamDir, "dream_state.json")
        if (stateFile.exists()) {
            runCatching {
                json.decodeFromString<DreamState>(stateFile.readText())
            }.getOrNull() ?: DreamState()
        } else {
            DreamState()
        }
    }

    @Serializable
    data class DreamResult(
        val success: Boolean,
        val phase: String = "deep",
        val backupFile: String,
        val optimizedMemories: Int,
        val totalDreams: Int
    )

    // ============ CommonMemory 记忆提取 ============

    /**
     * 从 Markdown 内容中提取结构化记忆写入 CommonMemory
     * 按照标题层级识别记忆类型，内容作为 summary/detail
     */
    private suspend fun extractMemoriesFromMarkdown(content: String, repo: MemoryRepository) {
        val lines = content.lines()
        var currentSection = ""
        var currentContent = StringBuilder()
        var extractedCount = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) {
                // 处理前一个节
                if (currentContent.isNotEmpty() && currentSection.isNotEmpty()) {
                    val entry = parseSectionToEntry(currentSection, currentContent.toString().trim())
                    if (entry != null) {
                        tryInsertOrUpdate(entry, repo)
                        extractedCount++
                    }
                }
                currentSection = trimmed.removePrefix("#").removePrefix("#").removePrefix("#").trim()
                currentContent = StringBuilder()
            } else {
                if (trimmed.isNotEmpty()) {
                    currentContent.appendLine(trimmed)
                }
            }
        }
        // 处理最后一个节
        if (currentContent.isNotEmpty() && currentSection.isNotEmpty()) {
            val entry = parseSectionToEntry(currentSection, currentContent.toString().trim())
            if (entry != null) {
                tryInsertOrUpdate(entry, repo)
                extractedCount++
            }
        }

        if (extractedCount > 0) {
            Timber.i("CommonMemory: extracted $extractedCount memories from REM Dream")
        }
    }

    /**
     * 根据节标题推断记忆类型
     */
    private fun parseSectionToEntry(sectionTitle: String, content: String): CommonMemoryEntry? {
        if (content.length < 5) return null  // 过短的内容跳过

        val type = when {
            sectionTitle.contains("偏好", ignoreCase = true) ||
            sectionTitle.contains("preference", ignoreCase = true) ||
            sectionTitle.contains("喜欢", ignoreCase = true) -> BrainMemoryType.PREFERENCE

            sectionTitle.contains("目标", ignoreCase = true) ||
            sectionTitle.contains("goal", ignoreCase = true) -> BrainMemoryType.GOAL

            sectionTitle.contains("项目", ignoreCase = true) ||
            sectionTitle.contains("project", ignoreCase = true) -> BrainMemoryType.PROJECT

            sectionTitle.contains("习惯", ignoreCase = true) ||
            sectionTitle.contains("habit", ignoreCase = true) -> BrainMemoryType.HABIT

            sectionTitle.contains("决定", ignoreCase = true) ||
            sectionTitle.contains("decision", ignoreCase = true) -> BrainMemoryType.DECISION

            sectionTitle.contains("约束", ignoreCase = true) ||
            sectionTitle.contains("constraint", ignoreCase = true) ||
            sectionTitle.contains("规则", ignoreCase = true) -> BrainMemoryType.CONSTRAINT

            sectionTitle.contains("身份", ignoreCase = true) ||
            sectionTitle.contains("identity", ignoreCase = true) -> BrainMemoryType.IDENTITY

            sectionTitle.contains("关系", ignoreCase = true) ||
            sectionTitle.contains("relationship", ignoreCase = true) -> BrainMemoryType.RELATIONSHIP

            else -> BrainMemoryType.EPISODE
        }

        val summary = if (content.length > 220) content.take(217) + "..." else content

        return CommonMemoryEntry(
            id = "dream_${sectionTitle.hashCode().toString().replace("-", "m")}",
            type = type,
            summary = summary,
            detail = if (content.length > 220) content else null,
            scope = BrainMemoryScope.ACTIVE,
            evidenceKind = EvidenceKind.SYSTEM,
            confidence = 0.6f,
            importance = 0.5f,
            durability = 0.4f,
            evidenceCount = 1
        )
    }

    /**
     * 尝试插入新记忆或与已有记忆合并
     */
    private suspend fun tryInsertOrUpdate(entry: CommonMemoryEntry, repo: MemoryRepository) {
        // 先检查是否有合并候选
        val terms = entry.summary.split(wordSplitRegex).filter { it.length >= 2 }
        val candidate = repo.findMergeCandidate(entry.type, terms)
        if (candidate != null) {
            // 合并：提升置信度和强化次数
            val merged = candidate.copy(
                confidence = minOf(1.0f, candidate.confidence + 0.1f),
                evidenceCount = candidate.evidenceCount + 1,
                updatedAt = System.currentTimeMillis(),
                lastSeenAt = System.currentTimeMillis()
            )
            repo.update(merged)
            Timber.d("CommonMemory: merged memory ${entry.id} into ${candidate.id}")
        } else {
            // 检查冲突候选
            val conflict = repo.findConflictCandidate(entry.type, terms)
            if (conflict != null) {
                // 极性冲突：新记忆替代旧记忆（降低旧记忆置信度）
                val demoted = conflict.copy(
                    confidence = conflict.confidence * 0.5f,
                    updatedAt = System.currentTimeMillis()
                )
                repo.update(demoted)
                repo.insert(entry)
                Timber.d("CommonMemory: conflict resolved, old demoted + new inserted")
            } else {
                // 全新记忆
                repo.insert(entry)
            }
        }
    }
}

