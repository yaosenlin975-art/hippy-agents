package com.lin.hippyagent.core.chat

import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.agent.session.SessionMessage
import com.lin.hippyagent.core.agent.session.SessionToolCall
import com.lin.hippyagent.core.agent.session.ToolCallStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnConverterTest {

    private val converter = ChatTurnConverter()
    private val t0 = Instant.ofEpochMilli(1_000_000_000_000L)

    private fun msg(
        id: String,
        role: MessageRole,
        content: String = "",
        senderId: String? = null,
        toolCalls: List<SessionToolCall> = emptyList(),
        metadataJson: String? = null,
        toolName: String? = null,
        timestamp: Instant = t0
    ) = SessionMessage(
        id = id,
        sessionId = "s1",
        role = role,
        content = content,
        timestamp = timestamp,
        toolCalls = toolCalls,
        senderId = senderId,
        metadataJson = metadataJson,
        toolName = toolName
    )

    // ═══════════ basic turn classification ═══════════

    @Test
    fun convert_userMessage_producesUserTurn() {
        val turns = converter.convert(listOf(msg("u1", MessageRole.USER, "hello")))
        assertEquals(1, turns.size)
        val turn = turns[0] as ChatTurn.UserTurn
        assertEquals("u1", turn.id)
        assertEquals("hello", turn.message.content)
        assertNull(turn.originalImageUri)
        assertNull(turn.targetedAgentIds)
    }

    @Test
    fun convert_userMessage_withImageAttachment_extractsUri() {
        val content = "看一下这张图 [附件: /storage/emulated/0/DCIM/photo.jpg]"
        val turns = converter.convert(listOf(msg("u1", MessageRole.USER, content)))
        val turn = turns[0] as ChatTurn.UserTurn
        assertEquals("/storage/emulated/0/DCIM/photo.jpg", turn.originalImageUri)
    }

    @Test
    fun convert_userMessage_withNonImageAttachment_doesNotExtractUri() {
        val content = "附件: [附件: /data/notes.txt]"
        val turns = converter.convert(listOf(msg("u1", MessageRole.USER, content)))
        val turn = turns[0] as ChatTurn.UserTurn
        assertNull(turn.originalImageUri)
    }

    @Test
    fun convert_userMessage_withTargetedAgentIds_parsesMetadata() {
        val meta = """{"targetedAgentIds":["agent-b","agent-c"]}"""
        val turns = converter.convert(listOf(msg("u1", MessageRole.USER, "hi", metadataJson = meta)))
        val turn = turns[0] as ChatTurn.UserTurn
        assertEquals(listOf("agent-b", "agent-c"), turn.targetedAgentIds)
    }

    @Test
    fun convert_userMessage_withQuotedMetadata_parsesQuote() {
        val meta = """{"quotedMessageId":"m9","quotedContent":"原始内容","quotedSenderName":"小明"}"""
        val turns = converter.convert(listOf(msg("u1", MessageRole.USER, "回复", metadataJson = meta)))
        val turn = turns[0] as ChatTurn.UserTurn
        assertEquals("m9", turn.quotedMessageId)
        assertEquals("原始内容", turn.quotedContent)
        assertEquals("小明", turn.quotedSenderName)
    }

    @Test
    fun convert_userMessage_withMalformedMetadata_doesNotCrash() {
        val turns = converter.convert(listOf(msg("u1", MessageRole.USER, "hi", metadataJson = "{not-json")))
        val turn = turns[0] as ChatTurn.UserTurn
        assertNull(turn.targetedAgentIds)
        assertNull(turn.quotedMessageId)
    }

    @Test
    fun convert_systemMessage_classifiesType() {
        val success = converter.convert(listOf(msg("s1", MessageRole.SYSTEM, "上下文压缩完成 ✅")))
        assertEquals(SystemTurnType.SUCCESS, (success[0] as ChatTurn.SystemTurn).type)

        val warning = converter.convert(listOf(msg("s2", MessageRole.SYSTEM, "⚠ 检测到异常")))
        assertEquals(SystemTurnType.WARNING, (warning[0] as ChatTurn.SystemTurn).type)

        val info = converter.convert(listOf(msg("s3", MessageRole.SYSTEM, "普通系统消息")))
        assertEquals(SystemTurnType.INFO, (info[0] as ChatTurn.SystemTurn).type)
    }

    @Test
    fun convert_privateMessage_producesPrivateTurn() {
        val turns = converter.convert(listOf(msg("p1", MessageRole.PRIVATE, "私密内容", senderId = "a1")))
        val turn = turns[0] as ChatTurn.PrivateTurn
        assertEquals("私密内容", turn.content)
        assertEquals("a1", turn.senderId)
    }

    @Test
    fun convert_emptyInput_returnsEmpty() {
        assertTrue(converter.convert(emptyList()).isEmpty())
    }

    // ═══════════ agent turn grouping ═══════════

    @Test
    fun convert_assistantAndToolMessages_groupedIntoSingleAgentTurn() {
        val messages = listOf(
            msg("a1", MessageRole.ASSISTANT, "我来执行", toolCalls = listOf(SessionToolCall("tc1", "read_file", "{}"))),
            msg("t1", MessageRole.TOOL, "file content", toolName = "read_file")
        )
        val turns = converter.convert(messages)
        assertEquals(1, turns.size)
        val turn = turns[0] as ChatTurn.AgentTurn
        assertEquals("a1", turn.id)
        assertEquals(1, turn.toolCalls.size)
        assertEquals("file content", turn.toolCalls[0].result?.content)
    }

    @Test
    fun convert_differentSenders_splitIntoSeparateAgentTurns() {
        val messages = listOf(
            msg("a1", MessageRole.ASSISTANT, "来自 A", senderId = "agent-a"),
            msg("a2", MessageRole.ASSISTANT, "来自 B", senderId = "agent-b")
        )
        val turns = converter.convert(messages)
        assertEquals(2, turns.size)
        assertEquals("agent-a", (turns[0] as ChatTurn.AgentTurn).senderAgentId)
        assertEquals("agent-b", (turns[1] as ChatTurn.AgentTurn).senderAgentId)
    }

    @Test
    fun convert_toolAfterSenderSwitch_staysWithPreviousTurn() {
        val messages = listOf(
            msg("a1", MessageRole.ASSISTANT, "A 发言", senderId = "agent-a", toolCalls = listOf(SessionToolCall("tc1", "read_file", "{}"))),
            msg("a2", MessageRole.ASSISTANT, "B 发言", senderId = "agent-b"),
            msg("t1", MessageRole.TOOL, "结果", toolName = "read_file")
        )
        val turns = converter.convert(messages)
        assertEquals(2, turns.size)
        assertEquals(1, (turns[0] as ChatTurn.AgentTurn).toolCalls.size)
        assertEquals("结果", (turns[0] as ChatTurn.AgentTurn).toolCalls[0].result?.content)
    }

    // ═══════════ thinking / reply parsing ═══════════

    @Test
    fun convert_thinkingAndReply_splitsContent() {
        val content = "⋞内部思考⋟公开回复内容"
        val turns = converter.convert(listOf(msg("a1", MessageRole.ASSISTANT, content)))
        val turn = turns[0] as ChatTurn.AgentTurn
        assertEquals("内部思考", turn.thinking?.content)
        assertEquals("公开回复内容", turn.response?.content)
    }

    @Test
    fun convert_nestedThinking_parsesChildren() {
        val content = "⋞外层思考 ⪡内层细节⪢⋟回复"
        val turns = converter.convert(listOf(msg("a1", MessageRole.ASSISTANT, content)))
        val turn = turns[0] as ChatTurn.AgentTurn
        assertEquals(1, turn.thinking?.children?.size)
        assertEquals("内层细节", turn.thinking?.children?.first()?.content)
    }

    @Test
    fun convert_plainReply_noThinking() {
        val turns = converter.convert(listOf(msg("a1", MessageRole.ASSISTANT, "直接回复")))
        val turn = turns[0] as ChatTurn.AgentTurn
        assertNull(turn.thinking)
        assertEquals("直接回复", turn.response?.content)
    }

    @Test
    fun parseThinkingAndReply_companion_pure() {
        val (thinking, reply) = ChatTurnConverter.parseThinkingAndReply("⋞A⋟⋞B⋟正文")
        assertEquals("A\n\nB", thinking)
        assertEquals("正文", reply)
    }

    // ═══════════ tool call status / metadata ═══════════

    @Test
    fun convert_completedToolCallWithoutResult_demotedToRunning() {
        val messages = listOf(
            msg("a1", MessageRole.ASSISTANT, "",
                toolCalls = listOf(SessionToolCall("tc1", "read_file", "{}", status = ToolCallStatus.COMPLETED)))
        )
        val turns = converter.convert(messages)
        val turn = turns[0] as ChatTurn.AgentTurn
        assertEquals(ToolCallStatus.RUNNING, turn.toolCalls[0].toolCall.status)
    }

    @Test
    fun convert_toolResult_setsDurationMs() {
        val messages = listOf(
            msg("a1", MessageRole.ASSISTANT, "",
                toolCalls = listOf(SessionToolCall("tc1", "read_file", "{}")),
                timestamp = t0),
            msg("t1", MessageRole.TOOL, "ok", toolName = "read_file", timestamp = t0.plusMillis(1500))
        )
        val turns = converter.convert(messages)
        val turn = turns[0] as ChatTurn.AgentTurn
        assertEquals(1500L, turn.toolCalls[0].durationMs)
    }

    @Test
    fun convert_restoresTurnMetadata_fromLastAssistantMessage() {
        val meta = """{"model":"qwen2.5","latencyMs":123,"isFallback":true}"""
        val messages = listOf(msg("a1", MessageRole.ASSISTANT, "回复", metadataJson = meta))
        val turns = converter.convert(messages)
        val turn = turns[0] as ChatTurn.AgentTurn
        assertEquals("qwen2.5", turn.metadata?.model)
        assertEquals(123L, turn.metadata?.latencyMs)
        assertTrue(turn.metadata?.isFallback == true)
    }

    @Test
    fun convert_elementsSortedByTimestamp() {
        val messages = listOf(
            msg("a1", MessageRole.ASSISTANT, "⋞think⋟", timestamp = t0,
                toolCalls = listOf(SessionToolCall("tc1", "tool_a", "{}"))),
            msg("t1", MessageRole.TOOL, "result", toolName = "tool_a", timestamp = t0.plusMillis(500))
        )
        val turns = converter.convert(messages)
        val turn = turns[0] as ChatTurn.AgentTurn
        assertTrue(turn.elements.isNotEmpty())
        assertEquals(turn.elements.sortedBy { it.timestamp.toEpochMilli() }, turn.elements)
    }

    @Test
    fun convert_blockquoteQuote_parsedFromAssistantContent() {
        val content = "> 小明: 你上次说的方案\n---\n我同意"
        val turns = converter.convert(listOf(msg("a1", MessageRole.ASSISTANT, content)))
        val turn = turns[0] as ChatTurn.AgentTurn
        assertEquals("你上次说的方案", turn.quotedContent)
        assertEquals("小明", turn.quotedSenderName)
    }

    // ═══════════ incremental conversion ═══════════

    @Test
    fun convertIncremental_sameMessages_returnsCachedTurns() {
        val messages = listOf(msg("u1", MessageRole.USER, "hi"))
        val first = converter.convertIncremental(messages)
        val second = converter.convertIncremental(messages)
        assertEquals(first, second)
        assertTrue(first === second)
    }

    @Test
    fun convertIncremental_appendedMessage_reusesPrefix() {
        val base = listOf(msg("u1", MessageRole.USER, "hi"))
        val appended = base + msg("a1", MessageRole.ASSISTANT, "hello!")
        val first = converter.convertIncremental(base)
        val second = converter.convertIncremental(appended)
        assertEquals(2, second.size)
        assertEquals("u1", (second[0] as ChatTurn.UserTurn).id)
        assertEquals("a1", (second[1] as ChatTurn.AgentTurn).id)
    }

    @Test
    fun convertIncremental_changedMessages_fullyReconverts() {
        val first = converter.convertIncremental(listOf(msg("u1", MessageRole.USER, "hi")))
        val second = converter.convertIncremental(listOf(msg("u2", MessageRole.USER, "changed")))
        assertEquals("u2", (second[0] as ChatTurn.UserTurn).id)
        assertTrue(first[0] !== second[0])
    }

    @Test
    fun convertIncremental_emptyMessages_resetsCache() {
        converter.convertIncremental(listOf(msg("u1", MessageRole.USER, "hi")))
        assertTrue(converter.convertIncremental(emptyList()).isEmpty())
        val after = converter.convertIncremental(listOf(msg("u1", MessageRole.USER, "hi")))
        assertEquals(1, after.size)
    }

    @Test
    fun invalidateCache_forcesFullReconvert() {
        val messages = listOf(msg("u1", MessageRole.USER, "hi"))
        val first = converter.convertIncremental(messages)
        converter.invalidateCache()
        val second = converter.convertIncremental(messages)
        assertEquals(first, second)
        assertTrue(first !== second)
    }
}
