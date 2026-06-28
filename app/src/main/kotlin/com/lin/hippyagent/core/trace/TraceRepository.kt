package com.lin.hippyagent.core.trace

import com.lin.hippyagent.data.TraceSpanDao
import com.lin.hippyagent.data.TraceSpanEntity
import com.lin.hippyagent.data.TraceSummaryRow
import com.lin.hippyagent.data.SpanTypeStatsRow

class TraceRepository(private val dao: TraceSpanDao) {

    suspend fun getSpansByTraceId(traceId: String): List<TraceSpanEntity> =
        dao.getByTraceId(traceId)

    suspend fun getTraceList(limit: Int = 50, offset: Int = 0): List<TraceSummaryRow> =
        dao.getTraceList(limit, offset)

    suspend fun getAggregateStats(since: Long): List<SpanTypeStatsRow> =
        dao.getAggregateStats(since)

    suspend fun deleteOlderThan(before: Long): Int =
        dao.deleteOlderThan(before)

    suspend fun clearLlmContentOlderThan(before: Long): Int =
        dao.clearLlmContentOlderThan(before)

    suspend fun deleteAll(): Int = dao.deleteAll()
}
