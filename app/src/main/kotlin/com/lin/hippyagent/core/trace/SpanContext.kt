package com.lin.hippyagent.core.trace

/**
 * Span 上下文，由 [SpanCollector.startSpan] 返回。
 * - [NoOp]：追踪关闭时返回，[SpanCollector.end] 静默无操作。
 * - [Real]：追踪开启时返回，携带 span 元数据供 end() 异步写 Room。
 */
sealed class SpanContext {
    object NoOp : SpanContext()

    data class Real(
        val spanId: String,
        val traceId: String,
        val parentSpanId: String?,
        val type: SpanType,
        val startedAt: Long,
        val props: Map<String, Any>
    ) : SpanContext()
}
