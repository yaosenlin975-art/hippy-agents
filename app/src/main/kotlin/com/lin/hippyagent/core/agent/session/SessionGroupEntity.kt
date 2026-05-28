package com.lin.hippyagent.core.agent.session

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete

@Entity(
    tableName = "session_groups",
    indices = [
        Index("agentId")
    ]
)
data class SessionGroupEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val name: String,
    val sortOrder: Int = 0,
    val isCollapsed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface SessionGroupDao {
    @Insert
    suspend fun insert(entity: SessionGroupEntity)

    @Update
    suspend fun update(entity: SessionGroupEntity)

    @Delete
    suspend fun delete(entity: SessionGroupEntity)

    @Query("SELECT * FROM session_groups WHERE agentId = :agentId ORDER BY sortOrder ASC LIMIT :limit OFFSET :offset")
    suspend fun getByAgentId(agentId: String, limit: Int = 100, offset: Int = 0): List<SessionGroupEntity>

    @Query("SELECT * FROM session_groups ORDER BY sortOrder ASC LIMIT :limit OFFSET :offset")
    suspend fun getAll(limit: Int = 100, offset: Int = 0): List<SessionGroupEntity>

    @Query("UPDATE session_groups SET isCollapsed = :collapsed WHERE id = :groupId")
    suspend fun updateCollapsed(groupId: String, collapsed: Boolean)

    @Query("UPDATE sessions SET groupId = :groupId WHERE id = :sessionId")
    suspend fun updateSessionGroup(sessionId: String, groupId: String?)

    @Query("UPDATE sessions SET groupId = :groupId WHERE id IN (:sessionIds)")
    suspend fun updateSessionsGroup(sessionIds: List<String>, groupId: String?)

    @Query("DELETE FROM session_groups WHERE id = :groupId")
    suspend fun deleteById(groupId: String)
}
