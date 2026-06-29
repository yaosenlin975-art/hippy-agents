package com.lin.hippyagent.core.memory.volunteer

/**
 * Volunteer 主动注入的记忆条目。
 */
data class VolunteeredMemory(
    val memoryId: String,
    val summary: String,
    val confidence: Double,
    val triggerEntity: String
)
