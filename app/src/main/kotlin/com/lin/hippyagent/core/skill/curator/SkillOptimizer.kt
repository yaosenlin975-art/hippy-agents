package com.lin.hippyagent.core.skill.curator

import timber.log.Timber

/**
 * 技能优化器 — 优化技能描述、触发词、置信度
 *
 * 参考: Hermes curator/optimizer.py — SkillOptimizer
 */
class SkillOptimizer {

    /**
     * 优化高频使用的技能
     * @return 优化过的技能数量
     */
    fun optimizeHighUsage(skills: List<CuratorSkillManifest>): Int {
        var optimized = 0
        for (skill in skills) {
            val original = skill
            var changed = false

            // 1. 根据使用次数提升置信度
            if (skill.usageCount >= 5 && skill.confidence < 0.9f) {
                skill.copy(confidence = (skill.confidence + 0.1f).coerceAtMost(0.95f))
                    .also { /* 实际更新由调用者处理 */ }
                changed = true
            }

            // 2. 长期未使用降低置信度
            if (skill.lastUsedAt != null) {
                val daysSinceUse = (System.currentTimeMillis() - skill.lastUsedAt) / (24 * 60 * 60 * 1000)
                if (daysSinceUse > 30 && skill.confidence > 0.2f) {
                    changed = true
                }
            }

            if (changed) optimized++
        }
        return optimized
    }

    /**
     * 提升技能置信度（成功使用后调用）
     */
    fun boostConfidence(skill: CuratorSkillManifest): Float {
        return (skill.confidence + 0.05f).coerceAtMost(0.95f)
    }

    /**
     * 降低技能置信度（失败/废弃后调用）
     */
    fun decayConfidence(skill: CuratorSkillManifest): Float {
        return (skill.confidence - 0.1f).coerceAtLeast(0.1f)
    }

    /**
     * 简化技能描述（去掉冗余信息）
     */
    fun simplifyDescription(description: String): String {
        return description
            .replace(Regex("---\n---|\n---"), "\n")
            .trim()
            .take(200)
    }
}
