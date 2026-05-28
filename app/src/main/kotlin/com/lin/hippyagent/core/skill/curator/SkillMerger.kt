package com.lin.hippyagent.core.skill.curator

import timber.log.Timber

/**
 * 技能合并器 — 合并相似技能，减少冗余
 *
 * 参考: Hermes curator/merger.py — SkillMerger.merge_similar()
 */
class SkillMerger {

    /**
     * 合并相似技能
     * @param skills 技能列表
     * @param threshold 相似度阈值 (0.0~1.0)，默认 0.7
     */
    fun mergeSimilar(
        skills: List<CuratorSkillManifest>,
        threshold: Float = 0.7f
    ): List<MergeResult> {
        val results = mutableListOf<MergeResult>()
        if (skills.size < 2) return results

        for (i in skills.indices) {
            for (j in i + 1 until skills.size) {
                val similarity = calculateSimilarity(skills[i], skills[j])
                if (similarity >= threshold) {
                    val merged = performMerge(skills[i], skills[j])
                    results.add(MergeResult(
                        sourceIds = listOf(skills[i].id, skills[j].id),
                        merged = merged.copy(confidence = merged.confidence.coerceIn(0f, 1f)),
                        similarity = similarity
                    ))
                }
            }
        }
        return results
    }

    /** 计算技能相似度 */
    fun calculateSimilarity(a: CuratorSkillManifest, b: CuratorSkillManifest): Float {
        // 1. 工具集重叠率 (权重 0.6)
        val toolsA = a.tools.map { it.name }.toSet()
        val toolsB = b.tools.map { it.name }.toSet()
        val intersection = toolsA.intersect(toolsB).size
        val union = toolsA.union(toolsB).size
        val toolSimilarity = if (union > 0) intersection.toFloat() / union else 0f

        // 2. 关键词重叠率 (权重 0.4)
        val keywordsA = a.triggers.keywords.map { it.lowercase() }.toSet()
        val keywordsB = b.triggers.keywords.map { it.lowercase() }.toSet()
        val kwIntersection = keywordsA.intersect(keywordsB).size
        val kwUnion = keywordsA.union(keywordsB).size
        val kwSimilarity = if (kwUnion > 0) kwIntersection.toFloat() / kwUnion else 0f

        return toolSimilarity * 0.6f + kwSimilarity * 0.4f
    }

    /** 执行合并 */
    private fun performMerge(a: CuratorSkillManifest, b: CuratorSkillManifest): CuratorSkillManifest {
        return CuratorSkillManifest(
            id = "merged_${com.lin.hippyagent.core.pool.FastId.nextShort()}",
            name = "${a.name}_${b.name}".take(48),
            description = "${a.description}\n---\n${b.description}",
            triggers = CuratorTriggerCondition(
                keywords = (a.triggers.keywords + b.triggers.keywords).distinct(),
                toolPattern = (a.triggers.toolPattern + b.triggers.toolPattern).distinct()
            ),
            tools = (a.tools + b.tools).distinctBy { it.name },
            workflow = a.workflow + b.workflow,
            usageCount = a.usageCount + b.usageCount,
            confidence = ((a.confidence + b.confidence) / 2f).coerceIn(0f, 1f),
            version = maxOf(a.version, b.version) + 1
        )
    }
}
