package com.lin.hippyagent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lin.hippyagent.core.trace.SpanType
import com.lin.hippyagent.core.trace.TraceSpan
import org.json.JSONObject

@Entity(
    tableName = "trace_span",
    indices = [
        Index(value = ["traceId", "startedAt"]),  // 按 traceId 查时间序列
        Index(value = ["type", "startedAt"]),     // 按类型聚合统计
        Index(value = ["startedAt"])              // 按时间倒序分页
    ]
)
data class TraceSpanEntity(
    @PrimaryKey val id: String,
    val traceId: String,
    val parentSpanId: String?,
    val type: String,           // SpanType.name
    val startedAt: Long,
    val durationMs: Long,
    val propsJson: String,      // JSON 序列化
    val error: String?,
    val createdAt: Long = System.currentTimeMillis()  // 用于保留期清理
) {
    fun toDomain(): TraceSpan {
        val props = if (propsJson.isBlank()) {
            emptyMap()
        } else {
            val obj = JSONObject(propsJson)
            obj.keys().asSequence().associateWith { obj.get(it) }
        }
        return TraceSpan(
            id = id,
            traceId = traceId,
            parentSpanId = parentSpanId,
            type = runCatching { SpanType.valueOf(type) }.getOrDefault(SpanType.AGENT_LOOP),
            startedAt = startedAt,
            durationMs = durationMs,
            props = props,
            error = error
        )
    }

    companion object {
        fun fromSpan(
            spanId: String,
            traceId: String,
            parentSpanId: String?,
            type: SpanType,
            startedAt: Long,
            durationMs: Long,
            props: Map<String, Any>,
            error: String?
        ): TraceSpanEntity {
            val jsonObj = JSONObject()
            props.forEach { (k, v) -> jsonObj.put(k, v) }
            return TraceSpanEntity(
                id = spanId,
                traceId = traceId,
                parentSpanId = parentSpanId,
                type = type.name,
                startedAt = startedAt,
                durationMs = durationMs,
                propsJson = jsonObj.toString(),
                error = error,
                createdAt = System.currentTimeMillis()
            )
        }
    }
}
