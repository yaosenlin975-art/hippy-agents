package com.lin.hippyagent.core.chat

import androidx.compose.runtime.Immutable
import com.lin.hippyagent.core.agent.session.SessionMessage
import com.lin.hippyagent.core.agent.session.SessionToolCall
import java.time.Instant

@Immutable
sealed class ChatTurn {
    abstract val id: String

    @Immutable
    data class UserTurn(
        override val id: String,
        val message: SessionMessage,
        val originalImageUri: String? = null,
        /** 群组聊天中此消息目标智能体 ID 列表，null 表示目标为全部成员（或非群聊场景） */
        val targetedAgentIds: List<String>? = null,
        /** 引用消息的 ID */
        val quotedMessageId: String? = null,
        /** 引用消息的摘要文本 */
        val quotedContent: String? = null,
        /** 引用消息的发送者名称 */
        val quotedSenderName: String? = null
    ) : ChatTurn()

    @Immutable
    data class AgentTurn(
        override val id: String,
        val thinking: ThinkingBlock? = null,
        val toolCalls: List<ToolCallBlock> = emptyList(),
        val response: SessionMessage? = null,
        val status: TurnStatus = TurnStatus.COMPLETED,
        val metadata: TurnMetadata? = null,
        /**
         * 按时间排序的交错元素列表，用于正确的渲染顺序。
         * 包含 TextSegment 和 ToolCallSegment，按 timestamp 升序排列。
         * 如果为空，则回退到 toolCalls + response 的传统渲染方式。
         */
        val elements: List<TurnElement> = emptyList(),
        /** 群组聊天中发送此消息的智能体 ID，null 表示单智能体会话 */
        val senderAgentId: String? = null,
        /** 引用消息的 ID */
        val quotedMessageId: String? = null,
        /** 引用消息的摘要文本 */
        val quotedContent: String? = null,
        /** 引用消息的发送者名称 */
        val quotedSenderName: String? = null
    ) : ChatTurn()

    /**
     * 系统状态消息 — 用于显示上下文压缩等系统级状态信息
     */
    @Immutable
    data class SystemTurn(
        override val id: String,
        val content: String,
        val type: SystemTurnType = SystemTurnType.INFO
    ) : ChatTurn()

    @Immutable
    data class PermissionTurn(
        override val id: String,
        val title: String,
        val description: String,
        val permissionType: PermissionType,
        val pendingCommand: String? = null,
        val isResolved: Boolean = false,
        val requestId: String? = null,
        val findings: List<com.lin.hippyagent.core.security.GuardFinding> = emptyList()
    ) : ChatTurn()

    @Immutable
    data class ClarificationTurn(
        override val id: String,
        val question: String,
        val clarificationType: String,
        val context: String = "",
        val options: List<String> = emptyList(),
        val isResolved: Boolean = false,
        val selectedOption: String? = null
    ) : ChatTurn()

    @Immutable
    data class PrivateTurn(
        override val id: String,
        val content: String,
        val senderId: String?
    ) : ChatTurn()
}

@Immutable
enum class SystemTurnType {
    INFO,
    SUCCESS,
    WARNING
}

@Immutable
enum class PermissionType {
    SHELL_COMMAND,
    CUSTOM_TOOL,
    ACCESSIBILITY,
    ANDROID_RUNTIME
}

/**
 * 按时间排序的交错渲染元素
 */
@Immutable
sealed class TurnElement {
    abstract val timestamp: Instant

    @Immutable
    data class ThinkingSegment(
        val block: ThinkingBlock,
        override val timestamp: Instant
    ) : TurnElement()

    @Immutable
    data class TextSegment(
        val content: String,
        override val timestamp: Instant
    ) : TurnElement()

    @Immutable
    data class ToolCallSegment(
        val block: ToolCallBlock,
        override val timestamp: Instant
    ) : TurnElement()
}

@Immutable
data class ThinkingBlock(
    val content: String,
    val isExpanded: Boolean = false,
    val children: List<ThinkingBlock> = emptyList(),
    val durationMs: Long = 0
)

@Immutable
data class ToolCallBlock(
    val toolCall: SessionToolCall,
    val result: SessionMessage? = null,
    val isExpanded: Boolean = false,
    val durationMs: Long = 0
)

@Immutable
enum class TurnStatus {
    STREAMING,
    COMPLETED,
    ERROR
}

/**
 * 每条 Agent 回复的运行时元数据
 */
@Immutable
@kotlinx.serialization.Serializable
data class TurnMetadata(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val model: String = "",
    val latencyMs: Long = 0,
    val apiCalls: Int = 0,
    val isFallback: Boolean = false,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val contextTokens: Long = 0,
    val maxContextTokens: Long = 0
)

