package com.lin.hippyagent.core.trace

import com.lin.hippyagent.data.TraceSpanDao
import com.lin.hippyagent.data.TraceSpanEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Span 收集器单例。
 *
 * - [enabled] = false 时 [startSpan] 返回 [SpanContext.NoOp]，[end] 静默无操作，零开销。
 * - [end] 用 [applicationScope].launch(Dispatchers.IO) 异步写 Room，不阻塞主链路。
 * - [sensitiveMasking] = true 时对 LLM_CALL/TOOL_CALL 的敏感字段脱敏（仅存长度）。
 *
 * 初始化：在 Application.onCreate 通过 [SpanCollector.init] 注入 scope 和 dao。
 */
object SpanCollector {

    @Volatile var enabled: Boolean = false
        private set

    @Volatile var sensitiveMasking: Boolean = true
        private set

    @Volatile var fullLlmContent: Boolean = false
        private set

    private lateinit var applicationScope: CoroutineScope
    private lateinit var dao: TraceSpanDao

    fun init(scope: CoroutineScope, dao: TraceSpanDao) {
        this.applicationScope = scope
        this.dao = dao
    }

    fun updateSettings(enabled: Boolean, sensitiveMasking: Boolean, fullLlmContent: Boolean) {
        this.enabled = enabled
        this.sensitiveMasking = sensitiveMasking
        this.fullLlmContent = fullLlmContent
    }

    /** 创建 Span 并返回 SpanContext，用于后续 end() */
    fun startSpan(
        type: SpanType,
        traceId: String,
        parentSpanId: String? = null,
        props: Map<String, Any> = emptyMap()
    ): SpanContext {
        if (!enabled) return SpanContext.NoOp
        val spanId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        return SpanContext.Real(spanId, traceId, parentSpanId, type, startedAt, props)
    }

    /** 结束 Span，异步写 Room */
    fun end(context: SpanContext, error: String? = null, extraProps: Map<String, Any> = emptyMap()) {
        if (context !is SpanContext.Real) return
        val durationMs = System.currentTimeMillis() - context.startedAt
        val finalProps = context.props + extraProps
        val maskedProps = if (sensitiveMasking) maskSensitive(context.type, finalProps) else finalProps
        // fullLlmContent=false 时即使关闭脱敏也要从 LLM_CALL 移除 messages 内容（避免误开导致膨胀）
        val effectiveProps = if (context.type == SpanType.LLM_CALL && !fullLlmContent) {
            maskedProps.toMutableMap().apply {
                remove("requestMessages")
                remove("responseText")
            }
        } else {
            maskedProps
        }
        val entity = TraceSpanEntity.fromSpan(
            spanId = context.spanId,
            traceId = context.traceId,
            parentSpanId = context.parentSpanId,
            type = context.type,
            startedAt = context.startedAt,
            durationMs = durationMs,
            props = effectiveProps,
            error = error
        )
        // applicationScope 用 SupervisorJob 保证单次 insert 失败不影响后续
        applicationScope.launch(Dispatchers.IO) {
            runCatching { dao.insert(entity) }
        }
    }

    /** 脱敏逻辑 */
    private fun maskSensitive(type: SpanType, props: Map<String, Any>): Map<String, Any> {
        return when (type) {
            SpanType.LLM_CALL -> props.toMutableMap().apply {
                (props["requestMessages"] as? List<*>)?.let { put("messageCount", it.size) }
                (props["responseText"] as? String)?.let { put("responseChars", it.length) }
                remove("requestMessages")
                remove("responseText")
            }
            SpanType.TOOL_CALL -> props.toMutableMap().apply {
                (props["args"] as? String)?.let { put("argsLength", it.length) }
                (props["result"] as? String)?.let { put("resultLength", it.length) }
                remove("args")
                remove("result")
            }
            else -> props
        }
    }
}
