package com.lin.hippyagent.core.agent.session

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "messages",
    indices = [
        Index("sessionId"),
        Index("timestamp"),
        Index(value = ["sessionId", "isCompressed", "timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val toolCallsJson: String = "",
    val isEdited: Boolean = false,
    val isCompressed: Boolean = false,
    val toolName: String? = null,
    val metadataJson: String? = null,
    val senderId: String? = null
)

data class MessageSearchRow(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val sessionName: String,
    val isCompressed: Boolean = false
)

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(entity: MessageEntity)

    @Insert
    suspend fun insertAll(entities: List<MessageEntity>)

    @Delete
    suspend fun delete(entity: MessageEntity)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getBySession(sessionId: String, limit: Int = 50): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND isCompressed = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getBySessionExcludingCompressed(sessionId: String, limit: Int = 50): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getAllBySession(sessionId: String, limit: Int = 1000): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestBySession(sessionId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Update
    suspend fun update(entity: MessageEntity)

    @Query("UPDATE messages SET isCompressed = 1 WHERE id IN (:messageIds)")
    suspend fun markMessagesCompressed(messageIds: List<String>)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT m.*, s.title as sessionName FROM messages m INNER JOIN sessions s ON m.sessionId = s.id WHERE m.content LIKE :query ORDER BY m.timestamp DESC LIMIT :limit")
    suspend fun searchAll(query: String, limit: Int = 50): List<MessageSearchRow>
}
