package com.lin.hippyagent.core.agent.session

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant

class RoomSessionStore(
    private val sessionDao: SessionDao,
    private val sessionStatsDao: SessionStatsDao,
    private val sessionCompressionDao: SessionCompressionDao,
    private val messageDao: MessageDao,
    private val database: AppDatabase
) : SessionStore {

    companion object {
        private val THINKING_BLOCK_REGEX = Regex("⋞[^⋟]*⋟\\n?")

        private val TOOL_CALL_JSON = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    override suspend fun createSession(agentId: String, title: String, sessionId: String?): Result<Session> =
        withContext(Dispatchers.IO) {
            runCatching {
                val now = Instant.now()
                val id = sessionId ?: com.lin.hippyagent.core.pool.FastId.next()
                database.withTransaction {
                    sessionDao.insert(SessionEntity(
                        id = id,
                        agentId = agentId,
                        title = title,
                        createdAt = now.toEpochMilli(),
                        lastUpdatedAt = now.toEpochMilli()
                    ))
                    sessionStatsDao.insert(SessionStatsEntity(sessionId = id))
                }
                Session(
                    id = id,
                    agentId = agentId,
                    title = title,
                    createdAt = now,
                    lastUpdatedAt = now
                )
            }
        }

    override suspend fun getSession(sessionId: String): Result<Session?> =
        runCatching { sessionDao.getFullById(sessionId)?.toSession() }

    override suspend fun deleteSession(sessionId: String): Result<Unit> =
        runCatching {
            database.withTransaction {
                sessionStatsDao.deleteById(sessionId)
                sessionCompressionDao.deleteById(sessionId)
                sessionDao.deleteById(sessionId)
                messageDao.deleteBySession(sessionId)
            }
        }

    override suspend fun updateSessionTitle(sessionId: String, title: String): Result<Unit> =
        runCatching {
            val entity = sessionDao.getById(sessionId) ?: throw IllegalStateException("Session not found")
            sessionDao.update(entity.copy(title = title, lastUpdatedAt = Instant.now().toEpochMilli()))
        }

    override suspend fun pinSession(sessionId: String, pinned: Boolean): Result<Unit> =
        runCatching {
            val entity = sessionDao.getById(sessionId) ?: throw IllegalStateException("Session not found")
            sessionDao.update(entity.copy(isPinned = pinned, lastUpdatedAt = Instant.now().toEpochMilli()))
        }

    override suspend fun getSessionsForAgent(agentId: String): Result<List<Session>> =
        runCatching { sessionDao.getByAgentId(agentId).map { it.toSession() } }

    override suspend fun getAllSessions(): Result<List<Session>> =
        runCatching { sessionDao.getAll().map { it.toSession() } }

    override suspend fun addMessage(sessionId: String, role: MessageRole, content: String, toolName: String?, senderId: String?): Result<SessionMessage> =
        runCatching {
            val id = com.lin.hippyagent.core.pool.FastId.next()
            val now = Instant.now()
            val entity = MessageEntity(
                id = id,
                sessionId = sessionId,
                role = role.name,
                content = content,
                timestamp = now.toEpochMilli(),
                toolName = toolName,
                senderId = senderId
            )
            messageDao.insert(entity)

            val cleanContent = if (role == MessageRole.ASSISTANT) {
                content.replace(THINKING_BLOCK_REGEX, "").trim()
            } else content
            val preview = if (cleanContent.length > 50) cleanContent.take(50) + "..." else cleanContent
            val newUnreadCount = if (role == MessageRole.ASSISTANT) 1 else 0
            val newLastMessage = when (role) {
                MessageRole.USER, MessageRole.ASSISTANT -> preview
                MessageRole.TOOL -> toolName?.let { "🔧 $it" } ?: null
                MessageRole.SYSTEM -> preview.ifEmpty { null }
                MessageRole.PRIVATE -> null
            }

            sessionDao.incrementMessageCountAndPreview(
                sessionId = sessionId,
                now = now.toEpochMilli(),
                preview = newLastMessage,
                unreadDelta = newUnreadCount
            )

            SessionMessage(
                id = id,
                sessionId = sessionId,
                role = role,
                content = content,
                timestamp = now,
                toolName = toolName
            )
        }

    override suspend fun updateMessageMetadata(messageId: String, metadataJson: String): Result<Unit> =
        runCatching {
            val entity = messageDao.getById(messageId) ?: return@runCatching
            messageDao.update(entity.copy(metadataJson = metadataJson))
            Timber.d("Metadata persisted for message $messageId")
        }

    override suspend fun updateMessageSenderId(messageId: String, senderId: String): Result<Unit> =
        runCatching {
            val entity = messageDao.getById(messageId) ?: return@runCatching
            messageDao.update(entity.copy(senderId = senderId))
        }

    override suspend fun addToolCall(sessionId: String, message: SessionMessage, toolCall: SessionToolCall): Result<Unit> =
        runCatching {
            val entity = messageDao.getById(message.id) ?: return@runCatching
            val existingCalls = deserializeToolCalls(entity.toolCallsJson)
            val updatedCalls = existingCalls + toolCall
            val updatedJson = TOOL_CALL_JSON.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(SessionToolCall.serializer()),
                updatedCalls
            )
            messageDao.update(entity.copy(toolCallsJson = updatedJson))
            Timber.d("Tool call added: ${toolCall.name} for session $sessionId")
        }

    override suspend fun updateToolCall(sessionId: String, messageId: String, toolCallId: String, status: ToolCallStatus, output: String?): Result<Unit> =
        runCatching {
            val entity = messageDao.getById(messageId) ?: return@runCatching
            val existingCalls = deserializeToolCalls(entity.toolCallsJson)
            val updatedCalls = existingCalls.map { tc ->
                if (tc.id == toolCallId) tc.copy(status = status, output = output) else tc
            }
            val updatedJson = TOOL_CALL_JSON.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(SessionToolCall.serializer()),
                updatedCalls
            )
            messageDao.update(entity.copy(toolCallsJson = updatedJson))
            Timber.d("Tool call updated: $toolCallId status=$status for session $sessionId")
        }

    override suspend fun getMessages(sessionId: String, limit: Int, includeCompressed: Boolean): Result<List<SessionMessage>> =
        runCatching {
            if (includeCompressed) {
                messageDao.getBySession(sessionId, limit).map { it.toSessionMessage() }
            } else {
                messageDao.getBySessionExcludingCompressed(sessionId, limit).map { it.toSessionMessage() }
            }
        }

    override suspend fun markMessagesCompressed(messageIds: List<String>): Result<Unit> =
        runCatching {
            if (messageIds.isNotEmpty()) {
                messageDao.markMessagesCompressed(messageIds)
            }
        }

    override suspend fun getCompressedSummary(sessionId: String): Result<String?> =
        runCatching { sessionCompressionDao.getSummaryById(sessionId) }

    override suspend fun updateCompressedSummary(sessionId: String, summary: String?): Result<Unit> =
        runCatching { sessionCompressionDao.upsertSummary(sessionId, summary) }

    override suspend fun deleteMessage(messageId: String): Result<Unit> =
        runCatching { messageDao.deleteById(messageId) }

    override suspend fun clearSessionMessages(sessionId: String): Result<Unit> =
        runCatching { messageDao.deleteBySession(sessionId) }

    override suspend fun searchSessions(query: String): Result<List<Session>> =
        runCatching {
            sessionDao.searchByTitle("%$query%").map { it.toSession() }
        }

    override suspend fun addTagToSession(sessionId: String, tag: String): Result<Unit> =
        runCatching {
            val entity = sessionDao.getById(sessionId) ?: throw IllegalStateException("Session not found")
            val tags = if (entity.tags.isBlank()) tag else "${entity.tags},$tag"
            sessionDao.update(entity.copy(tags = tags, lastUpdatedAt = Instant.now().toEpochMilli()))
        }

    override suspend fun removeTagFromSession(sessionId: String, tag: String): Result<Unit> =
        runCatching {
            val entity = sessionDao.getById(sessionId) ?: throw IllegalStateException("Session not found")
            val tags = entity.tags.split(",").filter { it != tag }.joinToString(",")
            sessionDao.update(entity.copy(tags = tags, lastUpdatedAt = Instant.now().toEpochMilli()))
        }

    override suspend fun closeSession(sessionId: String, model: String, inputTokens: Int, outputTokens: Int, cacheReadTokens: Int, cacheWriteTokens: Int, estimatedCostUsd: Double?): Result<Unit> =
        runCatching {
            val now = Instant.now().toEpochMilli()
            database.withTransaction {
                sessionDao.updateModel(sessionId, model)
                sessionStatsDao.insert(SessionStatsEntity(sessionId = sessionId))
                sessionStatsDao.updateTokenUsage(sessionId, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, estimatedCostUsd)
                sessionDao.updateStatus(sessionId, "completed")
                sessionStatsDao.updateFinishedAt(sessionId, now)
            }
        }

    override suspend fun failSession(sessionId: String, model: String, inputTokens: Int, outputTokens: Int, cacheReadTokens: Int, cacheWriteTokens: Int, estimatedCostUsd: Double?, error: String?): Result<Unit> =
        runCatching {
            val now = Instant.now().toEpochMilli()
            database.withTransaction {
                sessionDao.updateModel(sessionId, model)
                sessionStatsDao.insert(SessionStatsEntity(sessionId = sessionId))
                sessionStatsDao.updateTokenUsage(sessionId, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, estimatedCostUsd)
                sessionDao.updateStatus(sessionId, "failed")
                sessionStatsDao.updateFinishedAt(sessionId, now)
            }
        }

    override suspend fun updateSessionModel(sessionId: String, model: String): Result<Unit> =
        runCatching { sessionDao.updateModel(sessionId, model) }

    override suspend fun updateSessionTokenUsage(sessionId: String, inputTokens: Int, outputTokens: Int, cacheReadTokens: Int, cacheWriteTokens: Int, estimatedCostUsd: Double?): Result<Unit> =
        runCatching {
            sessionStatsDao.insert(SessionStatsEntity(sessionId = sessionId))
            sessionStatsDao.updateTokenUsage(sessionId, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, estimatedCostUsd)
        }

    private fun SessionFullRow.toSession() = Session(
        id = id,
        agentId = agentId,
        title = title,
        createdAt = Instant.ofEpochMilli(createdAt),
        lastUpdatedAt = Instant.ofEpochMilli(lastUpdatedAt),
        messageCount = messageCount,
        isPinned = isPinned,
        tags = if (tags.isBlank()) emptyList() else tags.split(","),
        lastMessage = lastMessage,
        model = model,
        status = status,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        estimatedCostUsd = estimatedCostUsd,
        finishedAt = finishedAt?.let { Instant.ofEpochMilli(it) },
        unreadCount = unreadCount,
        isMuted = isMuted,
        compressedSummary = compressedSummary,
        groupId = groupId,
        interrupted = interrupted,
        isHidden = hidden
    )

    private fun MessageEntity.toSessionMessage() = SessionMessage(
        id = id,
        sessionId = sessionId,
        role = MessageRole.valueOf(role),
        content = content,
        timestamp = Instant.ofEpochMilli(timestamp),
        isEdited = isEdited,
        isCompressed = isCompressed,
        toolName = toolName,
        toolCalls = deserializeToolCalls(toolCallsJson),
        metadataJson = metadataJson,
        senderId = senderId
    )

    private fun deserializeToolCalls(json: String): List<SessionToolCall> {
        if (json.isBlank()) return emptyList()
        return try {
            TOOL_CALL_JSON.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(SessionToolCall.serializer()),
                json
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to deserialize toolCallsJson")
            emptyList()
        }
    }

    override fun observeMessages(sessionId: String): Flow<List<SessionMessage>> {
        return messageDao.observeBySession(sessionId).map { list ->
            list.map { it.toSessionMessage() }
        }
    }

    override fun observeSessions(): Flow<List<Session>> {
        return sessionDao.observeAll().map { list ->
            list.map { it.toSession() }
        }
    }

    override suspend fun searchAllMessages(query: String, limit: Int): Result<List<MessageSearchResult>> =
        runCatching {
            messageDao.searchAll("%$query%", limit).map { row ->
                MessageSearchResult(
                    sessionId = row.sessionId,
                    sessionName = row.sessionName,
                    messageId = row.id,
                    role = MessageRole.valueOf(row.role),
                    matchedText = row.content,
                    timestamp = Instant.ofEpochMilli(row.timestamp)
                )
            }
        }

    override suspend fun incrementUnread(sessionId: String): Result<Unit> =
        runCatching {
            val entity = sessionDao.getById(sessionId) ?: throw IllegalStateException("Session not found")
            sessionDao.update(entity.copy(unreadCount = entity.unreadCount + 1))
        }

    override suspend fun resetUnread(sessionId: String): Result<Unit> =
        runCatching {
            sessionDao.updateUnread(sessionId, 0)
        }

    override suspend fun setMuted(sessionId: String, muted: Boolean): Result<Unit> =
        runCatching {
            sessionDao.updateMuted(sessionId, muted)
        }

    override fun observeUnreadSummary(): Flow<UnreadSummary> {
        return sessionDao.observeUnreadData().map { rows ->
            val badges = mutableMapOf<String, BadgeLevel>()
            val counts = mutableMapOf<String, Int>()
            var total = 0
            var hasMuted = false
            for (row in rows) {
                if (row.unreadCount > 0) {
                    badges[row.id] = if (row.isMuted) BadgeLevel.DOT else BadgeLevel.COUNT
                    counts[row.id] = row.unreadCount
                    total += row.unreadCount
                    if (row.isMuted) hasMuted = true
                }
            }
            UnreadSummary(total, hasMuted, badges, counts)
        }
    }

    override suspend fun getInterruptedSessions(): Result<List<Session>> =
        runCatching { sessionDao.getInterrupted().map { it.toSession() } }

    override suspend fun markSessionInterrupted(sessionId: String): Result<Unit> =
        runCatching { sessionDao.updateInterrupted(sessionId, true) }

    override suspend fun markSessionResumed(sessionId: String): Result<Unit> =
        runCatching { sessionDao.updateInterrupted(sessionId, false) }

    override suspend fun hideSession(sessionId: String): Result<Unit> =
        runCatching { sessionDao.updateHidden(sessionId, true) }

    override suspend fun deleteSessionWithPrivateChats(sessionId: String): Result<Unit> =
        runCatching {
            val privateChatIds = sessionDao.findPrivateSessionIdsBySuffix("_$sessionId")
            database.withTransaction {
                for (privateId in privateChatIds) {
                    sessionStatsDao.deleteById(privateId)
                    sessionCompressionDao.deleteById(privateId)
                    sessionDao.deleteById(privateId)
                    messageDao.deleteBySession(privateId)
                }
                sessionStatsDao.deleteById(sessionId)
                sessionCompressionDao.deleteById(sessionId)
                sessionDao.deleteById(sessionId)
                messageDao.deleteBySession(sessionId)
            }
        }
}
