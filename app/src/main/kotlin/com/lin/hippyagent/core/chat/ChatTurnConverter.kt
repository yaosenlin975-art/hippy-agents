package com.lin.hippyagent.core.chat

import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.agent.session.SessionMessage
import com.lin.hippyagent.core.agent.session.SessionToolCall
import com.lin.hippyagent.core.agent.session.ToolCallStatus
import com.lin.hippyagent.core.pool.ObjectPool
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class ChatTurnConverter {

    private var lastMessageIds: List<String> = emptyList()
    private var lastConvertedTurns: List<ChatTurn> = emptyList()

    private val turnsPool = ObjectPool(
        factory = { mutableListOf<ChatTurn>() },
        reset = { it.clear() },
        maxSize = 4
    )
    private val agentMsgPool = ObjectPool(
        factory = { mutableListOf<SessionMessage>() },
        reset = { it.clear() },
        maxSize = 4
    )
    private val toolCallPool = ObjectPool(
        factory = { mutableListOf<ToolCallBlock>() },
        reset = { it.clear() },
        maxSize = 4
    )
    private val elementPool = ObjectPool(
        factory = { mutableListOf<TurnElement>() },
        reset = { it.clear() },
        maxSize = 4
    )
    private val idListPool = ObjectPool(
        factory = { mutableListOf<String>() },
        reset = { it.clear() },
        maxSize = 4
    )

    fun invalidateCache() {
        lastMessageIds = emptyList()
        lastConvertedTurns = emptyList()
    }

    fun convertIncremental(messages: List<SessionMessage>): List<ChatTurn> {
        if (messages.isEmpty()) {
            lastMessageIds = emptyList()
            lastConvertedTurns = emptyList()
            return emptyList()
        }

        val currentIds = idListPool.acquire()
        try {
            for (msg in messages) {
                currentIds.add(msg.id)
            }

            if (currentIds.toList() == lastMessageIds && lastConvertedTurns.isNotEmpty()) {
                return lastConvertedTurns
            }

            val commonPrefixLength = findCommonPrefixLength(lastMessageIds, currentIds)

            if (commonPrefixLength == lastMessageIds.size - 1 && currentIds.size == lastMessageIds.size + 1) {
                val incrementalMessages = messages.subList(commonPrefixLength, messages.size)
                val newTurns = convert(incrementalMessages)
                val result = lastConvertedTurns.dropLast(1) + newTurns
                lastMessageIds = currentIds.toList()
                lastConvertedTurns = result
                return result
            }

            val result = convert(messages)
            lastMessageIds = currentIds.toList()
            lastConvertedTurns = result
            return result
        } finally {
            idListPool.release(currentIds)
        }
    }

    private fun findCommonPrefixLength(list1: List<String>, list2: List<String>): Int {
        var i = 0
        while (i < list1.size && i < list2.size && list1[i] == list2[i]) {
            i++
        }
        return i
    }

    fun convert(messages: List<SessionMessage>): List<ChatTurn> {
        val turns = turnsPool.acquire()
        try {
            var i = 0
            while (i < messages.size) {
                if (messages[i].role == MessageRole.USER) {
                    val imageUri = extractImageUriFromContent(messages[i].content)
                    val targetedIds: List<String>? = try {
                        messages[i].metadataJson?.let { jsonStr ->
                            (metadataJson.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonObject)
                                ?.get("targetedAgentIds")
                                ?.let { it as? kotlinx.serialization.json.JsonArray }
                                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        }
                    } catch (_: Exception) { null }
                    val (quotedMsgId, quotedContent, quotedSenderName) = try {
                        messages[i].metadataJson?.let { jsonStr ->
                            val obj = metadataJson.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonObject
                            Triple(
                                obj?.get("quotedMessageId")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                                obj?.get("quotedContent")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                                obj?.get("quotedSenderName")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            )
                        } ?: Triple(null, null, null)
                    } catch (_: Exception) { Triple(null, null, null) }
                    turns.add(ChatTurn.UserTurn(
                        id = messages[i].id,
                        message = messages[i],
                        originalImageUri = imageUri,
                        targetedAgentIds = targetedIds,
                        quotedMessageId = quotedMsgId,
                        quotedContent = quotedContent,
                        quotedSenderName = quotedSenderName
                    ))
                    i++
                } else if (messages[i].role == MessageRole.SYSTEM) {
                    turns.add(ChatTurn.SystemTurn(
                        id = messages[i].id,
                        content = messages[i].content,
                        type = parseSystemTurnType(messages[i].content)
                    ))
                    i++
                } else if (messages[i].role == MessageRole.PRIVATE) {
                    turns.add(ChatTurn.PrivateTurn(
                        id = messages[i].id,
                        content = messages[i].content,
                        senderId = messages[i].senderId
                    ))
                    i++
                } else {
                    val agentMessages = agentMsgPool.acquire()
                    try {
                        var lastSenderId: String? = null
                        while (i < messages.size && messages[i].role != MessageRole.USER && messages[i].role != MessageRole.SYSTEM && messages[i].role != MessageRole.PRIVATE) {
                            val currentSenderId = messages[i].senderId
                            val shouldSplit = if (messages[i].role == MessageRole.TOOL) false
                                    else if (currentSenderId != null && lastSenderId != null && currentSenderId != lastSenderId) true
                                    else false
                            if (shouldSplit && agentMessages.isNotEmpty()) {
                                turns.add(buildAgentTurn(agentMessages))
                                agentMessages.clear()
                            }
                            agentMessages.add(messages[i])
                            if (messages[i].role != MessageRole.TOOL && currentSenderId != null) {
                                lastSenderId = currentSenderId
                            }
                            i++
                        }
                        if (agentMessages.isNotEmpty()) {
                            turns.add(buildAgentTurn(agentMessages))
                        }
                    } finally {
                        agentMsgPool.release(agentMessages)
                    }
                }
            }
            return turns.toList()
        } finally {
            turnsPool.release(turns)
        }
    }

    private fun parseSystemTurnType(content: String): SystemTurnType {
        return when {
            content.contains("✅") || content.contains("completed") || content.contains("完成") -> SystemTurnType.SUCCESS
            content.contains("⚠") || content.contains("WARNING") || content.contains("警告") -> SystemTurnType.WARNING
            else -> SystemTurnType.INFO
        }
    }

    private fun extractImageUriFromContent(content: String): String? {
        val match = ATTACHMENT_REGEX.find(content) ?: return null
        val path = match.groupValues[1]
        val ext = path.substringAfterLast(".", "").lowercase()
        return if (ext in IMAGE_EXTENSIONS) path else null
    }

    private fun buildAgentTurn(messages: List<SessionMessage>): ChatTurn.AgentTurn {
        var thinking: ThinkingBlock? = null
        val toolCalls = toolCallPool.acquire()
        val elements = elementPool.acquire()
        val senderAgentId = messages.firstNotNullOfOrNull { it.senderId }
        var quotedMsgId = messages.firstNotNullOfOrNull { it.quotedMessageId }
        var quotedContent = messages.firstNotNullOfOrNull { it.quotedContent }
        var quotedSenderName = messages.firstNotNullOfOrNull { it.quotedSenderName }

        if (quotedContent == null) {
            val assistantContent = messages.firstOrNull { it.role == MessageRole.ASSISTANT }?.content
            if (assistantContent != null) {
                val parsed = parseBlockquoteQuote(assistantContent)
                if (parsed != null) {
                    quotedContent = parsed.first
                    quotedSenderName = parsed.second
                }
            }
        }

        try {
            var response: SessionMessage? = null
            var lastAssistantTimestamp = 0L

            for (msg in messages) {
                when (msg.role) {
                    MessageRole.ASSISTANT -> {
                        lastAssistantTimestamp = msg.timestamp.toEpochMilli()
                        val (thinkingContent, replyContent) = parseThinkingAndReply(msg.content)

                        if (thinkingContent != null) {
                            val thinkingDurationMs = msg.metadataJson?.let { json ->
                                try {
                                    (metadataJson.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject)
                                        ?.get("thinkingDurationMs")?.jsonPrimitive?.longOrNull
                                } catch (_: Exception) { null }
                            } ?: 0L
                            val thinkingBlock = parseThinkingTree(thinkingContent).copy(durationMs = thinkingDurationMs)
                            if (thinking == null) thinking = thinkingBlock
                            elements.add(TurnElement.ThinkingSegment(
                                block = thinkingBlock,
                                timestamp = msg.timestamp
                            ))
                        }

                        for (tc in msg.toolCalls) {
                            val block = ToolCallBlock(toolCall = tc)
                            toolCalls.add(block)
                            elements.add(TurnElement.ToolCallSegment(
                                block = block,
                                timestamp = msg.timestamp
                            ))
                        }

                        if (replyContent.isNotBlank()) {
                            response = msg.copy(content = replyContent)
                            elements.add(TurnElement.TextSegment(
                                content = replyContent,
                                timestamp = msg.timestamp
                            ))
                        } else if (msg.toolCalls.isEmpty() && thinkingContent == null) {
                            response = msg
                            if (msg.content.isNotBlank()) {
                                elements.add(TurnElement.TextSegment(
                                    content = msg.content,
                                    timestamp = msg.timestamp
                                ))
                            }
                        }
                    }
                    MessageRole.TOOL -> {
                        val targetIdx = toolCalls.indexOfFirst { it.result == null }
                        if (targetIdx >= 0) {
                            val toolMsgTimestamp = msg.timestamp.toEpochMilli()
                            val durationMs = if (lastAssistantTimestamp > 0) {
                                toolMsgTimestamp - lastAssistantTimestamp
                            } else 0L
                            val updatedBlock = toolCalls[targetIdx].copy(result = msg, durationMs = durationMs)
                            toolCalls[targetIdx] = updatedBlock
                            val tcId = updatedBlock.toolCall.id
                            for (i in elements.indices) {
                                val el = elements[i]
                                if (el is TurnElement.ToolCallSegment && el.block.toolCall.id == tcId) {
                                    elements[i] = el.copy(block = updatedBlock)
                                    break
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }

            elements.sortBy { it.timestamp.toEpochMilli() }

            val restoredMetadata = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.metadataJson?.let { json ->
                try {
                    metadataJson.decodeFromString<TurnMetadata>(json)
                } catch (_: Exception) {
                    null
                }
            }

            return ChatTurn.AgentTurn(
                id = messages.first().id,
                thinking = thinking,
                toolCalls = toolCalls.map { block ->
                    if (block.result == null && block.toolCall.status == ToolCallStatus.COMPLETED) {
                        block.copy(toolCall = block.toolCall.copy(status = ToolCallStatus.RUNNING))
                    } else block
                },
                response = response ?: messages.last(),
                elements = elements.toList(),
                metadata = restoredMetadata,
                senderAgentId = senderAgentId,
                quotedMessageId = quotedMsgId,
                quotedContent = quotedContent,
                quotedSenderName = quotedSenderName
            )
        } finally {
            toolCallPool.release(toolCalls)
            elementPool.release(elements)
        }
    }

    private val blockquoteRegex = Regex("(?m)^>\\s?(.+)$")

    private fun parseBlockquoteQuote(content: String): Pair<String, String?>? {
        val lines = content.lines()
        val quoteLines = mutableListOf<String>()
        var senderName: String? = null
        var inQuote = false
        var quoteEnded = false

        for (line in lines) {
            if (line.startsWith("> ")) {
                inQuote = true
                val quoteText = line.removePrefix("> ").trim()
                if (senderName == null && quoteText.contains(":")) {
                    val colonIdx = quoteText.indexOf(":")
                    val possibleName = quoteText.substring(0, colonIdx).trim()
                    if (possibleName.length <= 20 && possibleName.all { it.isLetterOrDigit() || it in "._- " }) {
                        senderName = possibleName
                        quoteLines.add(quoteText.substring(colonIdx + 1).trim())
                    } else {
                        quoteLines.add(quoteText)
                    }
                } else {
                    quoteLines.add(quoteText)
                }
            } else if (inQuote && line.trim() == "---") {
                quoteEnded = true
                break
            } else if (inQuote && line.isNotBlank()) {
                break
            }
        }

        if (quoteLines.isEmpty()) return null
        return quoteLines.joinToString(" ").take(200) to senderName
    }

    private fun parseThinkingTree(content: String): ThinkingBlock {
        val children = mutableListOf<ThinkingBlock>()
        var remaining = content
        NESTED_THINKING_REGEX.findAll(content).forEach { matchResult ->
            children.add(ThinkingBlock(content = matchResult.groupValues[1].trim()))
        }
        if (children.isNotEmpty()) {
            remaining = NESTED_THINKING_REGEX.replace(content, "").trim()
        }
        return ThinkingBlock(
            content = remaining.ifBlank { content },
            children = children
        )
    }

    companion object {
        private val THINKING_REGEX = Regex("⋞(.*?)⋟", RegexOption.DOT_MATCHES_ALL)
        private val NESTED_THINKING_REGEX = Regex("⪡(.*?)⪢", RegexOption.DOT_MATCHES_ALL)
        private val ATTACHMENT_REGEX = Regex("""\[附件:\s*(\S+)\]""")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

        private val metadataJson = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun parseThinkingAndReply(content: String): Pair<String?, String> {
            val allThinking = mutableListOf<String>()
            THINKING_REGEX.findAll(content).forEach { match ->
                allThinking.add(match.groupValues[1].trim())
            }
            if (allThinking.isEmpty()) return null to content
            val thinkingContent = allThinking.joinToString("\n\n")
            var replyContent = THINKING_REGEX.replace(content, "").trim()
            replyContent = NESTED_THINKING_REGEX.replace(replyContent, "").trim()
            replyContent = replyContent.replace("⋞", "").replace("⋟", "").replace("⪡", "").replace("⪢", "")
            return thinkingContent to replyContent
        }
    }
}

