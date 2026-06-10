package com.lin.hippyagent.core.agent.session

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Entity(
    tableName = "inbox_events",
    indices = [
        Index("createdAt"),
        Index("read"),
        Index("agentId")
    ]
)
data class InboxEvent(
    @PrimaryKey val id: String,
    val agentId: String = "default",
    val sourceType: String,
    val sourceId: String = "",
    val eventType: String,
    val status: String,
    val severity: String,
    val title: String,
    val body: String = "",
    val payload: String = "{}",
    val read: Boolean = false,
    val createdAt: Long
)

@Dao
interface InboxDao {
    @Insert
    suspend fun insertEvent(event: InboxEvent)

    @Query("SELECT * FROM inbox_events ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getEvents(limit: Int, offset: Int): List<InboxEvent>

    @Query("SELECT COUNT(*) FROM inbox_events WHERE read = 0")
    suspend fun getUnreadCount(): Int

    @Query("UPDATE inbox_events SET read = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE inbox_events SET read = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM inbox_events WHERE id = :id")
    suspend fun deleteEvent(id: String)
}
