package com.lin.hippyagent.core.memory.volunteer

import androidx.room.*

@Entity(
    tableName = "volunteer_events",
    indices = [
        Index(value = ["trigger_entity", "created_at"]),  // 按触发实体查时间序列
        Index(value = ["memory_id"])                       // 按记忆反查
    ]
)
data class VolunteerEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "trigger_entity") val triggerEntity: String,
    @ColumnInfo(name = "memory_id") val memoryId: String,
    val confidence: Double,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "session_id") val sessionId: String
)

@Dao
interface VolunteerEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: VolunteerEventEntity)

    @Query("SELECT * FROM volunteer_events WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getBySession(sessionId: String, limit: Int): List<VolunteerEventEntity>

    @Query("DELETE FROM volunteer_events WHERE created_at < :cutoff")
    suspend fun prune(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM volunteer_events WHERE session_id = :sessionId")
    suspend fun countBySession(sessionId: String): Int
}
