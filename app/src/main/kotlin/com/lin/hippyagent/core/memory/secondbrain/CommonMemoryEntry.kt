package com.lin.hippyagent.core.memory.commonmemory

import androidx.room.*

/**
 * CommonMemory 记忆领域模型
 * 与 MemoryManager.kt 中的 MemoryEntry 独立，避免命名冲突
 */
data class CommonMemoryEntry(
    val id: String,
    val agentId: String = "",
    val type: BrainMemoryType,
    val summary: String,
    val detail: String? = null,
    val scope: BrainMemoryScope = BrainMemoryScope.ACTIVE,
    val evidenceKind: EvidenceKind = EvidenceKind.INFERRED,
    val confidence: Float = 0.5f,
    val importance: Float = 0.5f,
    val durability: Float = 0.5f,
    val evidenceCount: Int = 1,
    val dismissed: Boolean = false,
    val isUploadRelated: Boolean = false,
    val expiresAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null
)

