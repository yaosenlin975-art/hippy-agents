package com.lin.hippyagent.core.memory.volunteer

import com.lin.hippyagent.core.memory.commonmemory.MemoryRepository
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Volunteer 主动记忆注入器。
 *
 * 置信度门控：
 * - alias 命中（summary == entity）→ 0.9
 * - title 命中（summary 包含 entity）→ 0.8
 * - slug-suffix 命中（summary 后缀）→ 0.6
 * - salience_boost → +0.05
 *
 * 限制：maxPages=3/cap=5（单轮最多注入 3 条，跨轮累计上限 5 条）
 * 超时：1.5s fail-open（超时返回空列表，不阻塞主链路）
 */
class VolunteerContextInjector(
    private val memoryRepository: MemoryRepository,
    private val entitySalienceExtractor: EntitySalienceExtractor,
    private val candidateScorer: CandidateScorer,
    private val volunteerEventDao: VolunteerEventDao? = null,
    private val sessionIdProvider: () -> String = { "" },
    private val minConfidence: Double = 0.7,
    private val maxPages: Int = 3,
    private val cap: Int = 5,
    private val timeoutMs: Long = 1500L
) {
    suspend fun volunteer(window: List<WindowTurn>): List<VolunteeredMemory> {
        // 1.5s 超时 fail-open
        return withTimeoutOrNull(timeoutMs) {
            doVolunteer(window)
        } ?: run {
            Timber.w("VolunteerContextInjector: timeout after ${timeoutMs}ms, fail-open")
            emptyList()
        }
    }

    private suspend fun doVolunteer(window: List<WindowTurn>): List<VolunteeredMemory> {
        if (window.isEmpty()) return emptyList()

        // 1. 抽取实体候选
        val allText = window.joinToString("\n") { it.text }
        val candidates = entitySalienceExtractor.extract(allText)
        if (candidates.isEmpty()) return emptyList()

        // 2. 多轮窗口评分
        val scored = candidateScorer.score(candidates, window)
        if (scored.isEmpty()) return emptyList()

        // 3. 逐候选查记忆并置信度门控
        val volunteered = mutableListOf<VolunteeredMemory>()
        for (scoredCandidate in scored) {
            if (volunteered.size >= maxPages) break

            val name = scoredCandidate.candidate.name
            val memories = memoryRepository.searchBySummary(name, maxPages)

            for (memory in memories) {
                if (volunteered.size >= maxPages) break
                val confidence = computeConfidence(name, memory.summary, scoredCandidate.score)
                if (confidence >= minConfidence) {
                    volunteered.add(
                        VolunteeredMemory(
                            memoryId = memory.id,
                            summary = memory.summary,
                            confidence = confidence,
                            triggerEntity = name
                        )
                    )
                }
            }
        }

        // 写入事件日志（可选，runCatching 兜底）
        val dao = volunteerEventDao
        if (dao != null && volunteered.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val sid = sessionIdProvider()
            volunteered.forEach { v ->
                runCatching {
                    dao.insert(
                        VolunteerEventEntity(
                            id = com.lin.hippyagent.core.pool.FastId.next(),
                            triggerEntity = v.triggerEntity,
                            memoryId = v.memoryId,
                            confidence = v.confidence,
                            createdAt = now,
                            sessionId = sid
                        )
                    )
                }
            }
        }

        return volunteered.take(cap)
    }

    private fun computeConfidence(entity: String, summary: String, salienceScore: Double): Double {
        var confidence = salienceScore
        val summaryLower = summary.lowercase()
        val entityLower = entity.lowercase()

        // alias 命中（完整匹配）→ 0.9
        if (summaryLower == entityLower) return 0.9

        // title 命中（summary 包含 entity）→ 0.8
        if (summaryLower.contains(entityLower)) {
            confidence = maxOf(confidence, 0.8)
        }

        // slug-suffix 命中（entity 是 summary 的后缀词）→ 0.6
        if (summaryLower.endsWith(entityLower)) {
            confidence = maxOf(confidence, 0.6)
        }

        // salience_boost
        confidence += SALIENCE_BOOST

        return confidence.coerceIn(0.0, 1.0)
    }

    companion object {
        private const val SALIENCE_BOOST = 0.05
    }
}
