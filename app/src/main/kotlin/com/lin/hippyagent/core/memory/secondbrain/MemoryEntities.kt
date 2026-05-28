package com.lin.hippyagent.core.memory.commonmemory

import androidx.room.*

/**
 * Memory Entity（主表）
 * 参考 Mercury Agent CommonMemory 设计
 */
@Entity(
    tableName = "memories",
    indices = [
        Index("type", "dismissed"),
        Index("dismissed", "updated_at"),
        Index("scope", "evidence_kind", "dismissed", "last_seen_at"),
        Index("last_seen_at"),
        Index("updated_at"),
        Index("is_upload_related", "dismissed", "expires_at"),
        Index("agent_id", "dismissed")
    ]
)
data class MemoryEntity(
    @PrimaryKey val id: String,

    @ColumnInfo(name = "user_key") val userKey: String,

    @ColumnInfo(name = "agent_id") val agentId: String = "",

    @ColumnInfo(name = "type") val type: String,

    @ColumnInfo(name = "summary") val summary: String,    // ≤220 字符

    @ColumnInfo(name = "detail") val detail: String?,    // 详细描述

    @ColumnInfo(name = "scope") val scope: String,      // "durable", "active"

    @ColumnInfo(name = "evidence_kind") val evidenceKind: String,  // "direct", "inferred"

    @ColumnInfo(name = "confidence") val confidence: Float,   // 0-1

    @ColumnInfo(name = "importance") val importance: Float,   // 0-1

    @ColumnInfo(name = "durability") val durability: Float,   // 0-1

    @ColumnInfo(name = "evidence_count") val evidenceCount: Int,  // 被强化次数

    @ColumnInfo(name = "dismissed") val dismissed: Boolean,   // true = 软删除

    @ColumnInfo(name = "is_upload_related") val isUploadRelated: Boolean = false,

    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long,

    @ColumnInfo(name = "updated_at") val updatedAt: Long,

    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,

    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long?
)

/**
 * FTS4 虚拟表（Room 支持）
 * 用于全文搜索
 */
@Entity(tableName = "memories_fts")
@Fts4(notIndexed = ["memory_id"])
data class MemoryFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    @ColumnInfo(name = "memory_id") val memoryId: String,
    val summary: String,
    val detail: String?
)

/**
 * Memory Type（10 种类型，从 Mercury 移植）
 */
enum class BrainMemoryType(val value: String) {
    IDENTITY("identity"),
    PREFERENCE("preference"),
    GOAL("goal"),
    PROJECT("project"),
    HABIT("habit"),
    DECISION("decision"),
    CONSTRAINT("constraint"),
    RELATIONSHIP("relationship"),
    EPISODE("episode"),
    REFLECTION("reflection");

    companion object {
        fun fromString(value: String): BrainMemoryType {
            return entries.find { it.value == value } ?: PREFERENCE
        }
    }
}

/**
 * Evidence Kind（证据类型）
 */
enum class EvidenceKind(val value: String) {
    DIRECT("direct"),         // 用户直接告知
    INFERRED("inferred"),   // LLM 从对话推断
    MANUAL("manual"),         // 用户手动创建
    SYSTEM("system");        // 系统生成（如 reflection）

    companion object {
        fun fromString(value: String): EvidenceKind {
            return entries.find { it.value == value } ?: INFERRED
        }
    }
}

/**
 * Scope（记忆范围）
 */
enum class BrainMemoryScope(val value: String) {
    DURABLE("durable"),   // 持久记忆（长期保留）
    ACTIVE("active");    // 活跃记忆（有时效性，可过期）

    companion object {
        fun fromString(value: String): BrainMemoryScope {
            return entries.find { it.value == value } ?: DURABLE
        }
    }
}

