package com.lin.hippyagent.core.agent.session

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "sessions",
    indices = [
        Index("agentId"),
        Index("lastUpdatedAt"),
        Index("isPinned"),
        Index(value = ["agentId", "isPinned", "lastUpdatedAt"])
    ]
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val title: String,
    val createdAt: Long,
    val lastUpdatedAt: Long,
    val messageCount: Int = 0,
    val isPinned: Boolean = false,
    val tags: String = "",
    val lastMessage: String? = null,
    val model: String = "",
    val status: String = "active",
    val unreadCount: Int = 0,
    val isMuted: Boolean = false,
    val groupId: String? = null,
    val interrupted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false
)

@Entity(
    tableName = "session_stats",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class SessionStatsEntity(
    @PrimaryKey val sessionId: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val estimatedCostUsd: Double? = null,
    val finishedAt: Long? = null
)

@Entity(
    tableName = "session_compression",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class SessionCompressionEntity(
    @PrimaryKey val sessionId: String,
    val compressedSummary: String? = null
)

data class SessionFullRow(
    val id: String,
    val agentId: String,
    val title: String,
    val createdAt: Long,
    val lastUpdatedAt: Long,
    val messageCount: Int,
    val isPinned: Boolean,
    val tags: String,
    val lastMessage: String?,
    val model: String,
    val status: String,
    val unreadCount: Int,
    val isMuted: Boolean,
    val groupId: String?,
    val interrupted: Boolean,
    val hidden: Boolean,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val estimatedCostUsd: Double?,
    val finishedAt: Long?,
    val compressedSummary: String?
)

data class UnreadDataRow(val id: String, val unreadCount: Int, val isMuted: Boolean)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(entity: SessionEntity)

    @Update
    suspend fun update(entity: SessionEntity)

    @Delete
    suspend fun delete(entity: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: String): SessionEntity?

    @Query("""
        SELECT s.*, COALESCE(st.inputTokens, 0) as inputTokens, COALESCE(st.outputTokens, 0) as outputTokens,
               COALESCE(st.cacheReadTokens, 0) as cacheReadTokens, COALESCE(st.cacheWriteTokens, 0) as cacheWriteTokens,
               st.estimatedCostUsd, st.finishedAt, sc.compressedSummary
        FROM sessions s
        LEFT JOIN session_stats st ON s.id = st.sessionId
        LEFT JOIN session_compression sc ON s.id = sc.sessionId
        WHERE s.id = :sessionId
    """)
    suspend fun getFullById(sessionId: String): SessionFullRow?

    @Query("""
        SELECT s.*, COALESCE(st.inputTokens, 0) as inputTokens, COALESCE(st.outputTokens, 0) as outputTokens,
               COALESCE(st.cacheReadTokens, 0) as cacheReadTokens, COALESCE(st.cacheWriteTokens, 0) as cacheWriteTokens,
               st.estimatedCostUsd, st.finishedAt, sc.compressedSummary
        FROM sessions s
        LEFT JOIN session_stats st ON s.id = st.sessionId
        LEFT JOIN session_compression sc ON s.id = sc.sessionId
        WHERE s.agentId = :agentId AND s.hidden = 0
        ORDER BY s.isPinned DESC, s.lastUpdatedAt DESC LIMIT :limit
    """)
    suspend fun getByAgentId(agentId: String, limit: Int = 100): List<SessionFullRow>

    @Query("""
        SELECT s.*, COALESCE(st.inputTokens, 0) as inputTokens, COALESCE(st.outputTokens, 0) as outputTokens,
               COALESCE(st.cacheReadTokens, 0) as cacheReadTokens, COALESCE(st.cacheWriteTokens, 0) as cacheWriteTokens,
               st.estimatedCostUsd, st.finishedAt, sc.compressedSummary
        FROM sessions s
        LEFT JOIN session_stats st ON s.id = st.sessionId
        LEFT JOIN session_compression sc ON s.id = sc.sessionId
        WHERE s.hidden = 0
        ORDER BY s.isPinned DESC, s.lastUpdatedAt DESC LIMIT :limit OFFSET :offset
    """)
    suspend fun getAll(limit: Int = 100, offset: Int = 0): List<SessionFullRow>

    @Query("""
        SELECT s.*, COALESCE(st.inputTokens, 0) as inputTokens, COALESCE(st.outputTokens, 0) as outputTokens,
               COALESCE(st.cacheReadTokens, 0) as cacheReadTokens, COALESCE(st.cacheWriteTokens, 0) as cacheWriteTokens,
               st.estimatedCostUsd, st.finishedAt, sc.compressedSummary
        FROM sessions s
        LEFT JOIN session_stats st ON s.id = st.sessionId
        LEFT JOIN session_compression sc ON s.id = sc.sessionId
        WHERE s.title LIKE :query AND s.hidden = 0 LIMIT :limit OFFSET :offset
    """)
    suspend fun searchByTitle(query: String, limit: Int = 100, offset: Int = 0): List<SessionFullRow>

    @Query("UPDATE sessions SET model = :model WHERE id = :sessionId")
    suspend fun updateModel(sessionId: String, model: String)

    @Query("UPDATE sessions SET status = :status WHERE id = :sessionId")
    suspend fun updateStatus(sessionId: String, status: String)

    @Query("""
        SELECT s.*, COALESCE(st.inputTokens, 0) as inputTokens, COALESCE(st.outputTokens, 0) as outputTokens,
               COALESCE(st.cacheReadTokens, 0) as cacheReadTokens, COALESCE(st.cacheWriteTokens, 0) as cacheWriteTokens,
               st.estimatedCostUsd, st.finishedAt, sc.compressedSummary
        FROM sessions s
        LEFT JOIN session_stats st ON s.id = st.sessionId
        LEFT JOIN session_compression sc ON s.id = sc.sessionId
        WHERE s.hidden = 0
        ORDER BY s.isPinned DESC, s.lastUpdatedAt DESC
    """)
    fun observeAll(): Flow<List<SessionFullRow>>

    @Query("SELECT id, unreadCount, isMuted FROM sessions WHERE hidden = 0")
    fun observeUnreadData(): Flow<List<UnreadDataRow>>

    @Query("UPDATE sessions SET unreadCount = :count WHERE id = :sessionId")
    suspend fun updateUnread(sessionId: String, count: Int)

    @Query("UPDATE sessions SET isMuted = :muted WHERE id = :sessionId")
    suspend fun updateMuted(sessionId: String, muted: Boolean)

    @Query("UPDATE sessions SET messageCount = messageCount + 1, lastUpdatedAt = :now, lastMessage = COALESCE(:preview, lastMessage), unreadCount = unreadCount + :unreadDelta WHERE id = :sessionId")
    suspend fun incrementMessageCountAndPreview(sessionId: String, now: Long, preview: String?, unreadDelta: Int)

    @Query("""
        SELECT s.*, COALESCE(st.inputTokens, 0) as inputTokens, COALESCE(st.outputTokens, 0) as outputTokens,
               COALESCE(st.cacheReadTokens, 0) as cacheReadTokens, COALESCE(st.cacheWriteTokens, 0) as cacheWriteTokens,
               st.estimatedCostUsd, st.finishedAt, sc.compressedSummary
        FROM sessions s
        LEFT JOIN session_stats st ON s.id = st.sessionId
        LEFT JOIN session_compression sc ON s.id = sc.sessionId
        WHERE s.status = 'active' AND s.interrupted = 1
        ORDER BY s.lastUpdatedAt DESC LIMIT :limit OFFSET :offset
    """)
    suspend fun getInterrupted(limit: Int = 100, offset: Int = 0): List<SessionFullRow>

    @Query("UPDATE sessions SET interrupted = :interrupted WHERE id = :sessionId")
    suspend fun updateInterrupted(sessionId: String, interrupted: Boolean)

    @Query("UPDATE sessions SET hidden = :hidden WHERE id = :sessionId")
    suspend fun updateHidden(sessionId: String, hidden: Boolean)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: String)

    @Query("SELECT id FROM sessions WHERE id LIKE 'private_%' || :suffix")
    suspend fun findPrivateSessionIdsBySuffix(suffix: String): List<String>
}

@Dao
interface SessionStatsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SessionStatsEntity)

    @Update
    suspend fun update(entity: SessionStatsEntity)

    @Query("SELECT * FROM session_stats WHERE sessionId = :sessionId")
    suspend fun getById(sessionId: String): SessionStatsEntity?

    @Query("UPDATE session_stats SET inputTokens = :inputTokens, outputTokens = :outputTokens, cacheReadTokens = :cacheReadTokens, cacheWriteTokens = :cacheWriteTokens, estimatedCostUsd = :costUsd WHERE sessionId = :sessionId")
    suspend fun updateTokenUsage(sessionId: String, inputTokens: Int, outputTokens: Int, cacheReadTokens: Int, cacheWriteTokens: Int, costUsd: Double?)

    @Query("UPDATE session_stats SET finishedAt = :finishedAt WHERE sessionId = :sessionId")
    suspend fun updateFinishedAt(sessionId: String, finishedAt: Long?)

    @Query("DELETE FROM session_stats WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: String)
}

@Dao
interface SessionCompressionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SessionCompressionEntity)

    @Update
    suspend fun update(entity: SessionCompressionEntity)

    @Query("SELECT compressedSummary FROM session_compression WHERE sessionId = :sessionId")
    suspend fun getSummaryById(sessionId: String): String?

    @Query("INSERT OR REPLACE INTO session_compression (sessionId, compressedSummary) VALUES (:sessionId, :summary)")
    suspend fun upsertSummary(sessionId: String, summary: String?)

    @Query("DELETE FROM session_compression WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: String)
}
