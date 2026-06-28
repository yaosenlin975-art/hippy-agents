package com.lin.hippyagent.core.trace

/**
 * 单个 Span 的领域模型（与 Room entity 解耦）。
 *
 * @param props 类型相关属性，见 spec 附录 A 各 SpanType 的 props 字段表。
 *              LLM_CALL 的 requestMessages/responseText 在脱敏模式下不存储。
 */
data class TraceSpan(
    val id: String,              // UUID
    val traceId: String,         // 一轮 AgentLoop 一个 traceId
    val parentSpanId: String?,   // 树形结构
    val type: SpanType,
    val startedAt: Long,
    val durationMs: Long,
    val props: Map<String, Any>,
    val error: String? = null
)
