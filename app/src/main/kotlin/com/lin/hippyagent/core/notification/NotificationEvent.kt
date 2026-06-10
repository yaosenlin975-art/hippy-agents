package com.lin.hippyagent.core.notification

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverter
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import java.util.UUID

enum class NotificationType {
    TASK_COMPLETED,
    AGENT_ERROR,
    APPROVAL_REQUEST,
    STATUS_CHANGE,
    SYSTEM_MESSAGE
}

enum class NotificationPriority {
    HIGH,
    NORMAL,
    LOW,
    SILENT
}

@Entity(
    tableName = "notification_events",
    indices = [
        Index("type"),
        Index("created_at"),
        Index("read_at"),
        Index(value = ["type", "created_at"])
    ]
)
data class NotificationEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: NotificationType,
    val priority: NotificationPriority,
    val title: String,
    val body: String,
    val source: String,
    val sourceType: String,
    val actions: List<String> = emptyList(),
    val payload: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "read_at") val readAt: Long? = null,
    @ColumnInfo(name = "acked_at") val ackedAt: Long? = null,
    @ColumnInfo(name = "aggregate_key") val aggregateKey: String? = null
)

class NotificationTypeConverters {

    @TypeConverter
    fun fromType(value: NotificationType?): String? = value?.name

    @TypeConverter
    fun toType(value: String?): NotificationType? =
        value?.let { runCatching { NotificationType.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromPriority(value: NotificationPriority?): String? = value?.name

    @TypeConverter
    fun toPriority(value: String?): NotificationPriority? =
        value?.let { runCatching { NotificationPriority.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        if (value.isNullOrEmpty()) return "[]"
        val arr = JSONArray()
        value.forEach { arr.put(it) }
        return arr.toString()
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(value)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }
}

@Dao
interface NotificationEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: NotificationEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<NotificationEvent>)

    @Update
    suspend fun update(event: NotificationEvent)

    @Query("DELETE FROM notification_events WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM notification_events ORDER BY created_at DESC LIMIT :limit")
    fun observeAll(limit: Int = 200): Flow<List<NotificationEvent>>

    @Query("SELECT * FROM notification_events WHERE read_at IS NULL ORDER BY created_at DESC LIMIT :limit")
    fun observeUnread(limit: Int = 200): Flow<List<NotificationEvent>>

    @Query("SELECT * FROM notification_events WHERE type = :type ORDER BY created_at DESC LIMIT :limit")
    fun observeByType(type: NotificationType, limit: Int = 200): Flow<List<NotificationEvent>>

    @Query("UPDATE notification_events SET read_at = :readAt WHERE id = :id")
    suspend fun markRead(id: String, readAt: Long)

    @Query("UPDATE notification_events SET acked_at = :ackedAt WHERE id = :id")
    suspend fun markAcked(id: String, ackedAt: Long)

    @Query("SELECT * FROM notification_events WHERE aggregate_key = :key ORDER BY created_at DESC LIMIT :limit")
    suspend fun searchByAggregateKey(key: String, limit: Int = 100): List<NotificationEvent>

    @Query("DELETE FROM notification_events WHERE created_at < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long): Int
}
