package com.lin.hippyagent.core.skill.curator

import com.lin.hippyagent.core.agent.middleware.AgentMiddleware
import com.lin.hippyagent.core.agent.middleware.MiddlewareContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Curator 中间件 — 收集 Agent 执行轨迹并写入 ExecutionHistoryStore
 *
 * 在 afterAgent 时从 context.extra 中提取执行数据，异步写入文件存储。
 *
 * Agent 需在调用中间件前在 context.extra 中放入:
 * - "curator_query": String — 用户原始查询
 * - "curator_tools": List<Map> — 工具调用记录
 * - "curator_success": Boolean — 是否成功
 * - "curator_duration_ms": Long — 执行耗时
 * - "curator_token_usage": Long — Token 使用量
 */
class CuratorMiddleware(
    private val historyStore: ExecutionHistoryStore
) : AgentMiddleware {

    override val name: String = "curator"
    override val priority: Int = PRIORITY

    // 独立 scope 用于异步写入文件，避免阻塞 Agent 的中间件链
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun afterAgent(context: MiddlewareContext) {
        val query = context.extra["curator_query"] as? String ?: return
        val toolsRaw = context.extra["curator_tools"] as? List<*> ?: return
        if (toolsRaw.size < 2) return

        @Suppress("UNCHECKED_CAST")
        val tools = toolsRaw.mapNotNull { item ->
            val map = item as? Map<String, Any> ?: return@mapNotNull null
            ToolCallRecord(
                toolName = map["toolName"] as? String ?: "",
                arguments = (map["arguments"] as? Map<String, Any>)?.mapValues {
                    it.value.toString()
                } ?: emptyMap(),
                result = map["result"] as? String,
                order = (map["order"] as? Number)?.toInt() ?: 0,
                durationMs = (map["durationMs"] as? Number)?.toLong() ?: 0L,
                success = map["success"] as? Boolean ?: true
            )
        }

        if (tools.size < 2) return

        val history = ExecutionHistory(
            agentId = context.agentId,
            sessionId = context.sessionId,
            query = query,
            tools = tools,
            success = context.extra["curator_success"] as? Boolean ?: true,
            durationMs = (context.extra["curator_duration_ms"] as? Number)?.toLong() ?: 0L,
            tokenUsage = (context.extra["curator_token_usage"] as? Number)?.toLong() ?: 0L
        )

        // 异步写入文件，不阻塞中间件链
        writeScope.launch {
            try {
                historyStore.save(history)
            } catch (e: Exception) {
                Timber.w(e, "CuratorMiddleware: failed to save execution history")
            }
        }
    }

    companion object {
        const val PRIORITY = 30
    }
}
