package com.lin.hippyagent.core.skill.store

import androidx.compose.runtime.Immutable

enum class SkillSource(val displayName: String, val color: Long) {
    LOBEHUB("LobeHub", 0xFF8B5CF6),
    SKILLS_SH("Skills.sh", 0xFF171717),
    CLAWHUB("ClawHub", 0xFFF97316),
    SKILLHUB("SkillHub", 0xFF10B981)
}

@Immutable
data class StoreSkillItem(
    val identifier: String,
    val name: String,
    val description: String,
    val author: String,
    val source: SkillSource,
    val category: String,
    val installCount: Long = 0,
    val confidence: Float = 0f,
    val starsCount: Long = 0,
    val rating: Float = -1f,
    val version: String = "",
    val updatedAt: Long = 0,
    val tags: List<String> = emptyList(),
    val installCommand: String = "",
    val detailUrl: String = "",
    val isInstalled: Boolean = false,
    val isValidated: Boolean = false
)

