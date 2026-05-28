package com.lin.hippyagent.core.agent.session

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "dream_history",
    indices = [
        Index("triggeredAt")
    ]
)
data class DreamHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggeredAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val status: String,
    val message: String,
    val backupPath: String? = null,
    val sizeBefore: Int = 0,
    val sizeAfter: Int = 0,
    val tokensUsed: Int? = null,
    val elapsedMs: Long? = null
)

@Dao
interface DreamHistoryDao {
    @Insert
    suspend fun insert(record: DreamHistoryEntity)

    @Query("SELECT * FROM dream_history ORDER BY triggeredAt DESC LIMIT :limit")
    fun getRecentHistory(limit: Int = 20): Flow<List<DreamHistoryEntity>>

    @Query("SELECT * FROM dream_history ORDER BY triggeredAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<DreamHistoryEntity>

    @Query("DELETE FROM dream_history WHERE triggeredAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
