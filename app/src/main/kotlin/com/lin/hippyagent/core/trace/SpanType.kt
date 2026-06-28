package com.lin.hippyagent.core.trace

/**
 * Span 类型枚举。新增类型时同步更新 [TraceSpanEntity] 转换逻辑和注入点。
 */
enum class SpanType {
    AGENT_LOOP,         // Agent 一轮
    LLM_CALL,           // LLM 调用
    TOOL_CALL,          // 工具调用
    SKILL_MATCH,        // Skill 匹配
    MEMORY_RETRIEVAL,   // 记忆检索
    CONTEXT_COMPACTION, // 上下文压缩
    SECURITY_EVENT      // 安全事件（由方向 C 运行时安全 spec 引入）
}
