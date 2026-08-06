package com.lin.hippyagent.core.agent

import com.lin.hippyagent.core.model.TokenUsage

/**
 * 流式输出块 — 区分普通内容和思考过程
 * 使用对象池复用，减少 GC 压力
 */
sealed class StreamChunk {
    data class Content(val text: String) : StreamChunk()
    data class Thinking(val text: String) : StreamChunk()
    data class Compaction(val compressedCount: Int, val summaryLength: Int) : StreamChunk()
    data class CompactionStarted(
        val totalTokens: Int,
        val maxTokens: Int,
        val messagesToCompress: Int,
        val messagesToKeep: Int
    ) : StreamChunk()
    data class CompactionCompleted(
        val compressedCount: Int,
        val newTokenEstimate: Int,
        val maxTokens: Int,
        val beforeTokens: Int = 0
    ) : StreamChunk()
    data class TaskCompleted(val inputTokens: Long, val outputTokens: Long, val apiCalls: Int) : StreamChunk()
    data object NewIteration : StreamChunk()
}



/**
 * 单个会话的运行时状态 — 支持同一 Agent 多会话并行
 */
data class SessionState(
    val status: AgentStatus = AgentStatus.IDLE,
    val isThinking: Boolean = false,
    val lastError: String? = null,
    val messageCount: Int = 0,
    val toolCallCount: Int = 0,
    /** 待授权的 Shell 命令（非 null 时 UI 层需显示授权对话框） */
    val pendingPermissionCommand: String? = null,
    /** 缺失的 Android 运行时权限列表 */
    val missingAndroidPermissions: List<String> = emptyList(),
    /** 最近一次 LLM 调用是否使用了 fallback 模型，非 null 时为实际使用的 fallback 模型名 */
    val usedFallbackModel: String? = null,
    /** mid-turn 智能体声明的模式覆盖；null 时由 ChatViewModel 调用 orchestrator 解析 (AUTO) */
    val modeOverride: String? = null
)



data class AgentState(
    val agentId: String,
    val tokenUsage: TokenUsage = TokenUsage(),
    /** per-session 状态映射，key = sessionId */
    val sessionStates: Map<String, SessionState> = emptyMap()
) {
    /** 获取指定会话的状态，不存在则返回 IDLE */
    fun getSessionState(sessionId: String): SessionState =
        sessionStates[sessionId] ?: SessionState()

    /** 获取当前所有活跃（非 IDLE）会话的 sessionId 列表 */
    internal fun activeSessionIds(): List<String> =
        sessionStates.filter { it.value.status != AgentStatus.IDLE }.keys.toList()
}



enum class AgentStatus {
    IDLE,
    THINKING,
    EXECUTING_TOOL,
    ERROR,
    STOPPED;

    /** 合法的状态转换映射 — 防止非法状态跳转 */
    fun canTransitionTo(target: AgentStatus): Boolean = when (this) {
        IDLE -> target in setOf(THINKING, ERROR, STOPPED)
        THINKING -> target in setOf(EXECUTING_TOOL, IDLE, ERROR, STOPPED)
        EXECUTING_TOOL -> target in setOf(THINKING, IDLE, ERROR, STOPPED)
        ERROR -> target in setOf(IDLE, THINKING, STOPPED)
        STOPPED -> target in setOf(IDLE, THINKING)
    }
}



/**
 * 网络不可用异常
 */
internal class NetworkUnavailableException(message: String) : Exception(message)



data class ContextTokenInfo(val currentTokens: Long = 0, val maxTokens: Long = 0)
