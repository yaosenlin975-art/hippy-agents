package com.lin.hippyagent.core.agent.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import java.time.Instant

data class Session(
    val id: String,
    val agentId: String,
    val title: String,
    val createdAt: Instant,
    val lastUpdatedAt: Instant,
    val messageCount: Int = 0,
    val isPinned: Boolean = false,
    val tags: List<String> = emptyList(),
    val lastMessage: String? = null,
    val model: String = "",
    val status: String = "active",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val estimatedCostUsd: Double? = null,
    val finishedAt: Instant? = null,
    val unreadCount: Int = 0,
    val isMuted: Boolean = false,
    val compressedSummary: String? = null,
    val groupId: String? = null,
    val interrupted: Boolean = false,
    val lastError: String? = null,
    val isHidden: Boolean = false
)

data class SessionMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Instant,
    val toolCalls: List<SessionToolCall> = emptyList(),
    val isEdited: Boolean = false,
    val isCompressed: Boolean = false,
    val toolName: String? = null,
    val metadataJson: String? = null,
    val senderId: String? = null,
    val quotedMessageId: String? = null,
    val quotedContent: String? = null,
    val quotedSenderName: String? = null
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
    PRIVATE
}

@Serializable
data class SessionToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val output: String? = null,
    val status: ToolCallStatus = ToolCallStatus.COMPLETED
)

@Serializable
enum class ToolCallStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

enum class BadgeLevel { NONE, DOT, COUNT }

data class UnreadSummary(
    val totalUnreadCount: Int,
    val hasMutedUnread: Boolean,
    val sessionBadges: Map<String, BadgeLevel>,
    val sessionUnreadCounts: Map<String, Int> = emptyMap()
)

data class MessageSearchResult(
    val sessionId: String,
    val sessionName: String,
    val messageId: String,
    val role: MessageRole,
    val matchedText: String,
    val timestamp: Instant
)

interface SessionStore {
    suspend fun createSession(agentId: String, title: String = "新会话", sessionId: String? = null): Result<Session>
    suspend fun getSession(sessionId: String): Result<Session?>
    suspend fun deleteSession(sessionId: String): Result<Unit>
    suspend fun updateSessionTitle(sessionId: String, title: String): Result<Unit>
    suspend fun pinSession(sessionId: String, pinned: Boolean): Result<Unit>
    suspend fun getSessionsForAgent(agentId: String): Result<List<Session>>
    suspend fun getAllSessions(): Result<List<Session>>

    suspend fun addMessage(sessionId: String, role: MessageRole, content: String, toolName: String? = null, senderId: String? = null): Result<SessionMessage>
    suspend fun updateMessageMetadata(messageId: String, metadataJson: String): Result<Unit>
    suspend fun updateMessageSenderId(messageId: String, senderId: String): Result<Unit>
    suspend fun addToolCall(sessionId: String, message: SessionMessage, toolCall: SessionToolCall): Result<Unit>
    suspend fun updateToolCall(sessionId: String, messageId: String, toolCallId: String, status: ToolCallStatus, output: String? = null): Result<Unit>
    suspend fun getMessages(sessionId: String, limit: Int = Int.MAX_VALUE, includeCompressed: Boolean = false): Result<List<SessionMessage>>
    suspend fun deleteMessage(messageId: String): Result<Unit>
    suspend fun clearSessionMessages(sessionId: String): Result<Unit>
    suspend fun markMessagesCompressed(messageIds: List<String>): Result<Unit>
    suspend fun getCompressedSummary(sessionId: String): Result<String?>
    suspend fun updateCompressedSummary(sessionId: String, summary: String?): Result<Unit>

    suspend fun searchSessions(query: String): Result<List<Session>>
    suspend fun addTagToSession(sessionId: String, tag: String): Result<Unit>
    suspend fun removeTagFromSession(sessionId: String, tag: String): Result<Unit>
    suspend fun closeSession(sessionId: String, model: String, inputTokens: Int, outputTokens: Int, cacheReadTokens: Int, cacheWriteTokens: Int, estimatedCostUsd: Double?): Result<Unit>
    suspend fun failSession(sessionId: String, model: String, inputTokens: Int, outputTokens: Int, cacheReadTokens: Int, cacheWriteTokens: Int, estimatedCostUsd: Double?, error: String?): Result<Unit>
    suspend fun updateSessionModel(sessionId: String, model: String): Result<Unit>
    suspend fun updateSessionTokenUsage(sessionId: String, inputTokens: Int, outputTokens: Int, cacheReadTokens: Int, cacheWriteTokens: Int, estimatedCostUsd: Double?): Result<Unit>

    fun observeMessages(sessionId: String): Flow<List<SessionMessage>>
    fun observeSessions(): Flow<List<Session>>

    suspend fun searchAllMessages(query: String, limit: Int = 50): Result<List<MessageSearchResult>>

    suspend fun incrementUnread(sessionId: String): Result<Unit>
    suspend fun resetUnread(sessionId: String): Result<Unit>
    suspend fun setMuted(sessionId: String, muted: Boolean): Result<Unit>
    fun observeUnreadSummary(): Flow<UnreadSummary>

    suspend fun getInterruptedSessions(): Result<List<Session>>
    suspend fun markSessionInterrupted(sessionId: String): Result<Unit>
    suspend fun markSessionResumed(sessionId: String): Result<Unit>
    suspend fun hideSession(sessionId: String): Result<Unit>

    /**
     * 删除会话及其关联的 chat_with_agent 私聊会话。
     * 私聊会话的 sessionId 格式为 private_{callerAgentId}_{targetAgentId}_{timestamp}_{sourceSessionId}
     * 通过 sourceSessionId 后缀匹配关联私聊并级联删除。
     */
    suspend fun deleteSessionWithPrivateChats(sessionId: String): Result<Unit>
}

class LocalSessionStore : SessionStore {
    companion object {
        // 预编译 Regex，避免每次调用都重新编译
        private val THINKING_BLOCK_REGEX = Regex("⋞[^⋟]*⋟\\n?")
    }

    private val sessions = mutableMapOf<String, Session>()
    private val messages = mutableMapOf<String, MutableList<SessionMessage>>()
    private val sessionsFlow = MutableStateFlow<List<Session>>(emptyList())
    private val messagesFlows = mutableMapOf<String, MutableStateFlow<List<SessionMessage>>>()
    private val unreadSummaryFlow = MutableStateFlow(UnreadSummary(0, false, emptyMap()))

    private fun notifySessionsChanged() {
        sessionsFlow.value = sessions.values
            .sortedWith(compareByDescending<Session> { it.isPinned }.thenByDescending { it.lastUpdatedAt })
            .toList()
        notifyUnreadChanged()
    }

    private fun notifyMessagesChanged(sessionId: String) {
        messagesFlows[sessionId]?.let { flow ->
            flow.value = messages[sessionId]?.toList() ?: emptyList()
        }
    }

    private fun notifyUnreadChanged() {
        val badges = mutableMapOf<String, BadgeLevel>()
        val counts = mutableMapOf<String, Int>()
        var total = 0
        var hasMuted = false
        for (session in sessions.values) {
            if (session.unreadCount > 0) {
                badges[session.id] = if (session.isMuted) BadgeLevel.DOT else BadgeLevel.COUNT
                counts[session.id] = session.unreadCount
                total += session.unreadCount
                if (session.isMuted) hasMuted = true
            }
        }
        unreadSummaryFlow.value = UnreadSummary(total, hasMuted, badges, counts)
    }

    override suspend fun createSession(agentId: String, title: String, sessionId: String?): Result<Session> {
        return runCatching {
            val now = Instant.now()
            val id = sessionId ?: com.lin.hippyagent.core.pool.FastId.next()
            val session = Session(
                id = id,
                agentId = agentId,
                title = title,
                createdAt = now,
                lastUpdatedAt = now
            )
            sessions[session.id] = session
            messages[session.id] = mutableListOf()
            notifySessionsChanged()
            session
        }
    }

    override suspend fun getSession(sessionId: String): Result<Session?> {
        return Result.success(sessions[sessionId])
    }

    override suspend fun deleteSession(sessionId: String): Result<Unit> {
        return runCatching {
            sessions.remove(sessionId)
            messages.remove(sessionId)
            messagesFlows.remove(sessionId)
            notifySessionsChanged()
        }
    }

    override suspend fun updateSessionTitle(sessionId: String, title: String): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(
                title = title,
                lastUpdatedAt = Instant.now()
            )
            notifySessionsChanged()
        }
    }

    override suspend fun pinSession(sessionId: String, pinned: Boolean): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(
                isPinned = pinned,
                lastUpdatedAt = Instant.now()
            )
            notifySessionsChanged()
        }
    }

    override suspend fun getSessionsForAgent(agentId: String): Result<List<Session>> {
        return Result.success(
            sessions.values
                .filter { it.agentId == agentId }
                .sortedWith(
                    compareByDescending<Session> { it.isPinned }
                        .thenByDescending { it.lastUpdatedAt }
                )
        )
    }

    override suspend fun getAllSessions(): Result<List<Session>> {
        return Result.success(
            sessions.values
                .sortedWith(
                    compareByDescending<Session> { it.isPinned }
                        .thenByDescending { it.lastUpdatedAt }
                )
        )
    }

    override suspend fun addMessage(sessionId: String, role: MessageRole, content: String, toolName: String?, senderId: String?): Result<SessionMessage> {
        return runCatching {
            val message = SessionMessage(
                id = com.lin.hippyagent.core.pool.FastId.next(),
                sessionId = sessionId,
                role = role,
                content = content,
                timestamp = Instant.now(),
                toolName = toolName,
                senderId = senderId
            )
            messages.getOrPut(sessionId) { mutableListOf() }.add(message)

            val session = sessions[sessionId]
            if (session != null) {
                // 清洗 ASSISTANT 消息：去掉 ⋞...⋟ thinking 块，只保留纯文本
                val cleanContent = if (role == MessageRole.ASSISTANT) {
                    content.replace(THINKING_BLOCK_REGEX, "").trim()
                } else content
                val preview = if (cleanContent.length > 50) cleanContent.take(50) + "..." else cleanContent
                val newUnreadCount = if (role == MessageRole.ASSISTANT) session.unreadCount + 1 else session.unreadCount
                // 所有角色消息都更新 lastMessage：USER/ASSISTANT 显示预览，TOOL 显示工具名，SYSTEM 显示状态摘要
                val newLastMessage = when (role) {
                    MessageRole.USER, MessageRole.ASSISTANT -> preview
                    MessageRole.TOOL -> toolName?.let { "🔧 $it" } ?: session.lastMessage
                    MessageRole.SYSTEM -> preview.ifEmpty { session.lastMessage }
                    MessageRole.PRIVATE -> session.lastMessage
                }
                sessions[sessionId] = session.copy(
                    messageCount = session.messageCount + 1,
                    lastUpdatedAt = Instant.now(),
                    lastMessage = newLastMessage,
                    unreadCount = newUnreadCount
                )
                notifySessionsChanged()
            }

            notifyMessagesChanged(sessionId)

            message
        }
    }

    override suspend fun updateMessageMetadata(messageId: String, metadataJson: String): Result<Unit> {
        return runCatching {
            messages.values.forEach { messageList ->
                val index = messageList.indexOfFirst { it.id == messageId }
                if (index != -1) {
                    messageList[index] = messageList[index].copy(metadataJson = metadataJson)
                    val sid = messageList[index].sessionId
                    notifyMessagesChanged(sid)
                    return@runCatching
                }
            }
        }
    }

    override suspend fun updateMessageSenderId(messageId: String, senderId: String): Result<Unit> {
        return runCatching {
            messages.values.forEach { messageList ->
                val index = messageList.indexOfFirst { it.id == messageId }
                if (index != -1) {
                    messageList[index] = messageList[index].copy(senderId = senderId)
                    val sid = messageList[index].sessionId
                    notifyMessagesChanged(sid)
                    return@runCatching
                }
            }
        }
    }

    override suspend fun addToolCall(sessionId: String, message: SessionMessage, toolCall: SessionToolCall): Result<Unit> {
        return runCatching {
            val messageList = messages[sessionId] ?: return@runCatching
            val index = messageList.indexOfFirst { it.id == message.id }
            if (index != -1) {
                val existingMessage = messageList[index]
                messageList[index] = existingMessage.copy(
                    toolCalls = existingMessage.toolCalls + toolCall
                )
            }
        }
    }

    override suspend fun updateToolCall(sessionId: String, messageId: String, toolCallId: String, status: ToolCallStatus, output: String?): Result<Unit> {
        return runCatching {
            val messageList = messages[sessionId] ?: return@runCatching
            val index = messageList.indexOfFirst { it.id == messageId }
            if (index != -1) {
                val msg = messageList[index]
                val updatedCalls = msg.toolCalls.map { tc ->
                    if (tc.id == toolCallId) tc.copy(status = status, output = output) else tc
                }
                messageList[index] = msg.copy(toolCalls = updatedCalls)
                notifyMessagesChanged(sessionId)
            }
        }
    }

    override suspend fun getMessages(sessionId: String, limit: Int, includeCompressed: Boolean): Result<List<SessionMessage>> {
        return Result.success(
            messages.getOrPut(sessionId) { mutableListOf() }
                .let { list -> if (includeCompressed) list else list.filter { !it.isCompressed } }
                .takeLast(limit)
        )
    }

    override suspend fun deleteMessage(messageId: String): Result<Unit> {
        return runCatching {
            messages.values.forEach { messageList ->
                messageList.removeAll { it.id == messageId }
            }
        }
    }

    override suspend fun clearSessionMessages(sessionId: String): Result<Unit> {
        return runCatching {
            messages[sessionId] = mutableListOf()
            notifyMessagesChanged(sessionId)
        }
    }

    override suspend fun searchSessions(query: String): Result<List<Session>> {
        return runCatching {
            sessions.values.filter { session ->
                session.title.contains(query, ignoreCase = true) ||
                    messages[session.id]?.any { it.content.contains(query, ignoreCase = true) } == true
            }
        }
    }

    override suspend fun addTagToSession(sessionId: String, tag: String): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            if (tag !in session.tags) {
                sessions[sessionId] = session.copy(
                    tags = session.tags + tag,
                    lastUpdatedAt = Instant.now()
                )
            }
        }
    }

    override suspend fun removeTagFromSession(sessionId: String, tag: String): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(
                tags = session.tags - tag,
                lastUpdatedAt = Instant.now()
            )
        }
    }

    override suspend fun closeSession(sessionId: String, model: String, inputTokens: Int, outputTokens: Int, cacheReadTokens: Int, cacheWriteTokens: Int, estimatedCostUsd: Double?): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cacheReadTokens = cacheReadTokens,
                cacheWriteTokens = cacheWriteTokens,
                estimatedCostUsd = estimatedCostUsd,
                status = "completed",
                finishedAt = Instant.now(),
                lastUpdatedAt = Instant.now()
            )
        }
    }

    override suspend fun failSession(sessionId: String, model: String, inputTokens: Int, outputTokens: Int, cacheReadTokens: Int, cacheWriteTokens: Int, estimatedCostUsd: Double?, error: String?): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cacheReadTokens = cacheReadTokens,
                cacheWriteTokens = cacheWriteTokens,
                estimatedCostUsd = estimatedCostUsd,
                status = "failed",
                finishedAt = Instant.now(),
                lastUpdatedAt = Instant.now(),
                lastError = error
            )
        }
    }

    override suspend fun updateSessionModel(sessionId: String, model: String): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(model = model, lastUpdatedAt = Instant.now())
        }
    }

    override suspend fun updateSessionTokenUsage(sessionId: String, inputTokens: Int, outputTokens: Int, cacheReadTokens: Int, cacheWriteTokens: Int, estimatedCostUsd: Double?): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cacheReadTokens = cacheReadTokens,
                cacheWriteTokens = cacheWriteTokens,
                estimatedCostUsd = estimatedCostUsd,
                lastUpdatedAt = Instant.now()
            )
        }
    }

    override fun observeMessages(sessionId: String): Flow<List<SessionMessage>> {
        return messagesFlows.getOrPut(sessionId) {
            MutableStateFlow(messages[sessionId]?.toList() ?: emptyList())
        }
    }

    override fun observeSessions(): Flow<List<Session>> {
        return sessionsFlow
    }

    override suspend fun searchAllMessages(query: String, limit: Int): Result<List<MessageSearchResult>> {
        return runCatching {
            val results = mutableListOf<MessageSearchResult>()
            for (session in sessions.values) {
                if (results.size >= limit) break
                val sessionMessages = messages[session.id] ?: continue
                for (msg in sessionMessages) {
                    if (results.size >= limit) break
                    if (msg.content.contains(query, ignoreCase = true)) {
                        results.add(MessageSearchResult(
                            sessionId = session.id,
                            sessionName = session.title,
                            messageId = msg.id,
                            role = msg.role,
                            matchedText = msg.content,
                            timestamp = msg.timestamp
                        ))
                    }
                }
            }
            results
        }
    }

    override suspend fun incrementUnread(sessionId: String): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(unreadCount = session.unreadCount + 1)
            notifySessionsChanged()
        }
    }

    override suspend fun resetUnread(sessionId: String): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(unreadCount = 0)
            notifySessionsChanged()
        }
    }

    override suspend fun setMuted(sessionId: String, muted: Boolean): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(isMuted = muted)
            notifySessionsChanged()
        }
    }

    override fun observeUnreadSummary(): Flow<UnreadSummary> {
        return unreadSummaryFlow
    }

    override suspend fun markMessagesCompressed(messageIds: List<String>): Result<Unit> {
        return runCatching {
            messages.values.forEach { messageList ->
                messageList.replaceAll { msg ->
                    if (msg.id in messageIds) msg.copy(isCompressed = true) else msg
                }
            }
        }
    }

    override suspend fun getCompressedSummary(sessionId: String): Result<String?> {
        return Result.success(sessions[sessionId]?.compressedSummary)
    }

    override suspend fun updateCompressedSummary(sessionId: String, summary: String?): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(compressedSummary = summary)
            notifySessionsChanged()
        }
    }

    override suspend fun getInterruptedSessions(): Result<List<Session>> {
        return Result.success(
            sessions.values.filter { it.status == "active" && it.interrupted }
        )
    }

    override suspend fun markSessionInterrupted(sessionId: String): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(interrupted = true)
            notifySessionsChanged()
        }
    }

    override suspend fun markSessionResumed(sessionId: String): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(interrupted = false)
            notifySessionsChanged()
        }
    }

    override suspend fun hideSession(sessionId: String): Result<Unit> {
        return runCatching {
            val session = sessions[sessionId] ?: throw IllegalStateException("Session not found")
            sessions[sessionId] = session.copy(isHidden = true)
            notifySessionsChanged()
        }
    }

    override suspend fun deleteSessionWithPrivateChats(sessionId: String): Result<Unit> {
        return runCatching {
            // 精确匹配：私聊 sessionId 格式 private_..._{sourceSessionId}，必须以 _sourceSessionId 结尾
            sessions.keys.toList().filter { it.startsWith("private_") && it.endsWith("_$sessionId") }.forEach { privateId ->
                deleteSession(privateId)
            }
            deleteSession(sessionId)
        }
    }
}

