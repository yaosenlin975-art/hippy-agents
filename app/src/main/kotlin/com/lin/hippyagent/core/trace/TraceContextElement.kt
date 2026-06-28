package com.lin.hippyagent.core.trace

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 用于在协程间传播 traceId 和 parentSpanId，避免函数签名污染。
 *
 * 使用：
 * ```
 * withContext(TraceContextElement(traceId, null)) {
 *     // 子调用通过 coroutineContext[TraceContextElement] 获取
 * }
 * ```
 */
data class TraceContextElement(
    val traceId: String,
    val parentSpanId: String?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TraceContextElement>
}
