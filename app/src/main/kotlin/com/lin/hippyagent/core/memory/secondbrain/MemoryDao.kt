package com.lin.hippyagent.core.memory.commonmemory

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lin.hippyagent.core.memory.ChineseTokenizer
import org.json.JSONArray

/**
 * Memory DAO（Room Data Access Object）
 * 包含 FTS4 全文搜索能力
 */
@Dao
interface MemoryDao {

    // ========== 基础 CRUD ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MemoryEntity)

    @Update
    suspend fun update(entity: MemoryEntity)

    @Query("SELECT * FROM memories WHERE id = :id AND dismissed = 0")
    suspend fun findById(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE dismissed = 0 ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    suspend fun findActive(limit: Int = 100, offset: Int = 0): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE agent_id = :agentId AND dismissed = 0 ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    suspend fun findActiveByAgentId(agentId: String, limit: Int = 100, offset: Int = 0): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    suspend fun findAll(limit: Int = 100, offset: Int = 0): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE type = :type AND dismissed = 0 ORDER BY updated_at DESC LIMIT :limit")
    suspend fun findByType(type: String, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE agent_id = :agentId AND type = :type AND dismissed = 0 ORDER BY updated_at DESC LIMIT :limit")
    suspend fun findByTypeAndAgentId(type: String, agentId: String, limit: Int): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories WHERE dismissed = 0")
    suspend fun countActive(): Int

    // ========== FTS4 全文搜索 ==========

    @Query("""
        SELECT m.* FROM memories m
        INNER JOIN memories_fts fts ON m.id = fts.memory_id
        WHERE memories_fts MATCH :query
        AND m.dismissed = 0
        ORDER BY m.updated_at DESC
        LIMIT :limit
    """)
    suspend fun searchFts(query: String, limit: Int): List<MemoryEntity>

    @Query("""
        SELECT m.* FROM memories m
        INNER JOIN memories_fts fts ON m.id = fts.memory_id
        WHERE memories_fts MATCH :query
        AND m.agent_id = :agentId
        AND m.dismissed = 0
        ORDER BY m.updated_at DESC
        LIMIT :limit
    """)
    suspend fun searchFtsByAgentId(query: String, agentId: String, limit: Int): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(entry: MemoryFts)

    @Query("DELETE FROM memories_fts WHERE rowid = :rowId")
    suspend fun deleteFtsByRowId(rowId: Long)

    @Query("""
        SELECT m.* FROM memories m
        INNER JOIN memories_fts fts ON m.id = fts.memory_id
        WHERE memories_fts MATCH :query
        AND m.agent_id = :agentId
        AND m.dismissed = 0
        ORDER BY m.updated_at DESC
    """)
    suspend fun searchByExactPhrase(query: String, agentId: String): List<MemoryEntity>

    @Query("""
        SELECT m.* FROM memories m
        INNER JOIN memories_fts fts ON m.id = fts.memory_id
        WHERE memories_fts MATCH :query
        AND m.agent_id = :agentId
        AND m.dismissed = 0
        ORDER BY m.updated_at DESC
    """)
    suspend fun searchByPhrasePrefix(query: String, agentId: String): List<MemoryEntity>

    // ========== 合并/冲突候选 ==========

    @Query("""
        SELECT * FROM memories
        WHERE type = :type AND dismissed = 0
        AND (summary LIKE :term1 OR summary LIKE :term2 OR summary LIKE :term3)
        ORDER BY updated_at DESC
        LIMIT 5
    """)
    suspend fun findMergeCandidate(
        type: String,
        term1: String,
        term2: String,
        term3: String
    ): MemoryEntity?

    @Query("""
        SELECT * FROM memories
        WHERE type = :type AND dismissed = 0
        AND (summary LIKE :term1 OR summary LIKE :term2 OR summary LIKE :term3)
        ORDER BY updated_at DESC
        LIMIT 5
    """)
    suspend fun findConflictCandidate(
        type: String,
        term1: String,
        term2: String,
        term3: String
    ): MemoryEntity?

    // ========== 晋升 ==========

    @Query("""
        UPDATE memories
        SET scope = 'durable', updated_at = :now
        WHERE type IN ('goal', 'project')
        AND scope = 'active'
        AND dismissed = 0
        AND evidence_count >= 3
    """)
    suspend fun promoteToDurable(now: Long): Int

    // ========== 修剪 ==========

    @Query("""
        UPDATE memories
        SET dismissed = 1, updated_at = :now
        WHERE scope = 'active'
        AND evidence_kind = 'inferred'
        AND dismissed = 0
        AND last_seen_at < :cutoffInferred
    """)
    suspend fun pruneInferredActive(now: Long, cutoffInferred: Long): Int

    @Query("""
        UPDATE memories
        SET dismissed = 1, updated_at = :now
        WHERE scope = 'active'
        AND evidence_kind = 'direct'
        AND dismissed = 0
        AND last_seen_at < :cutoffDirect
    """)
    suspend fun pruneDirectActive(now: Long, cutoffDirect: Long): Int

    @Query("""
        UPDATE memories
        SET confidence = MAX(0.15, confidence - 0.15), updated_at = :now
        WHERE scope = 'durable'
        AND evidence_kind = 'inferred'
        AND dismissed = 0
        AND last_seen_at < :cutoffDurable
    """)
    suspend fun decayDurableInferred(now: Long, cutoffDurable: Long): Int

    @Query("""
        UPDATE memories
        SET dismissed = 1, updated_at = :now
        WHERE scope = 'durable'
        AND evidence_kind = 'inferred'
        AND dismissed = 0
        AND confidence < 0.3
        AND last_seen_at < :cutoffDurable
    """)
    suspend fun pruneDurableInferred(now: Long, cutoffDurable: Long): Int

    // ========== 软/硬删除 ==========

    @Query("UPDATE memories SET dismissed = 1, updated_at = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long): Int

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun hardDeleteById(id: String): Int

    @Query("""
        SELECT * FROM memories
        WHERE is_upload_related = 1
        AND dismissed = 0
        AND expires_at IS NOT NULL
        AND expires_at < :nowMs
        LIMIT :limit OFFSET :offset
    """)
    suspend fun findExpiredUploadFacts(nowMs: Long, limit: Int = 100, offset: Int = 0): List<MemoryEntity>

    @Query("""
        SELECT * FROM memories
        WHERE dismissed = 0
        AND summary LIKE :query
        ORDER BY updated_at DESC
        LIMIT :limit
    """)
    suspend fun searchBySummary(query: String, limit: Int): List<MemoryEntity>

    // ========== 统计 ==========

    @Query("SELECT type, COUNT(*) as count FROM memories WHERE dismissed = 0 GROUP BY type")
    suspend fun countByType(): List<TypeCount>

    @Query("SELECT value FROM memory_meta WHERE key = 'profile'")
    suspend fun getProfileSummary(): String?

    @Query("SELECT value FROM memory_meta WHERE key = 'active'")
    suspend fun getActiveSummary(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: MemoryMeta)
}

/**
 * 类型计数（用于统计）
 */
data class TypeCount(
    val type: String,
    val count: Int
)

/**
 * Memory Meta（存储 profile_summary / active_summary）
 */
@Entity(tableName = "memory_meta")
data class MemoryMeta(
    @PrimaryKey val key: String,
    val value: String
)

/**
 * Memory Database（Room 数据库）
 * 独立于 AppDatabase，避免主库 migration 复杂度
 */
@Database(
    entities = [MemoryEntity::class, MemoryFts::class, MemoryMeta::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(MemoryConverters::class)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN is_upload_related INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN expires_at INTEGER")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN agent_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_agent_id_dismissed ON memories(agent_id, dismissed)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS memories_fts")
                db.execSQL("CREATE VIRTUAL TABLE memories_fts USING fts4(memory_id, summary, detail)")
            }
        }

        fun getInstance(context: Context, dbPath: String = "commonmemory.db"): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context,
                    MemoryDatabase::class.java,
                    dbPath
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .enableMultiInstanceInvalidation()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

/**
 * Type Converters（String ↔ List 转换，使用 org.json 避免 Gson 依赖）
 */
class MemoryConverters {

    @TypeConverter
    fun fromString(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(value)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromList(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }
}

