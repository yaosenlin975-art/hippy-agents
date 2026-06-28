package com.lin.hippyagent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TraceSpanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(span: TraceSpanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(spans: List<TraceSpanEntity>)

    @Query("SELECT * FROM trace_span WHERE traceId = :traceId ORDER BY startedAt ASC")
    suspend fun getByTraceId(traceId: String): List<TraceSpanEntity>

    @Query(
        """
        SELECT traceId,
               MIN(startedAt) as startedAt,
               SUM(durationMs) as totalDurationMs,
               COUNT(*) as spanCount,
               SUM(CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END) as errorCount
        FROM trace_span
        GROUP BY traceId
        ORDER BY startedAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getTraceList(limit: Int, offset: Int): List<TraceSummaryRow>

    @Query(
        """
        SELECT type,
               COUNT(*) as count,
               AVG(durationMs) as avgDurationMs,
               SUM(CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END) as errorCount
        FROM trace_span
        WHERE startedAt > :since
        GROUP BY type
        """
    )
    suspend fun getAggregateStats(since: Long): List<SpanTypeStatsRow>

    @Query("DELETE FROM trace_span WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /**
     * 清理 LLM_CALL Span 的 requestMessages/responseText 字段（保留 Span 本身）。
     * 用 json_replace 把这两个 key 设为 null（Room 不支持 json_set 直接删除 key，
     * 改为置 null 由 toDomain 解析时跳过）。
     */
    @Query(
        """
        UPDATE trace_span
        SET propsJson = json_remove(propsJson, '$.requestMessages', '$.responseText')
        WHERE type = 'LLM_CALL' AND createdAt < :before
        """
    )
    suspend fun clearLlmContentOlderThan(before: Long): Int

    @Query("DELETE FROM trace_span")
    suspend fun deleteAll(): Int
}

data class TraceSummaryRow(
    val traceId: String,
    val startedAt: Long,
    val totalDurationMs: Long,
    val spanCount: Int,
    val errorCount: Int
)

data class SpanTypeStatsRow(
    val type: String,
    val count: Int,
    val avgDurationMs: Float,
    val errorCount: Int
)
