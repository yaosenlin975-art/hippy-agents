package com.lin.hippyagent.ui.chat

import com.lin.hippyagent.core.agent.session.SessionToolCall
import com.lin.hippyagent.core.chat.ThinkingBlock
import com.lin.hippyagent.core.chat.ToolCallBlock
import com.lin.hippyagent.core.chat.TurnElement
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentReplyTextAggregationTest {

    private val t0 = Instant.ofEpochMilli(1_000_000_000_000L)

    private fun text(content: String) = TurnElement.TextSegment(content, t0)

    private fun thinking() = TurnElement.ThinkingSegment(ThinkingBlock(content = "思考中"), t0)

    private fun toolCall() = TurnElement.ToolCallSegment(
        ToolCallBlock(toolCall = SessionToolCall(id = "tc1", name = "shell", arguments = "{}")),
        t0
    )

    @Test
    fun emptyDisplayElements_fallsBackToLegacyContent() {
        assertEquals("legacy", aggregateAgentReplyText(emptyList(), "legacy"))
        assertEquals("", aggregateAgentReplyText(emptyList(), null))
    }

    @Test
    fun singleTextSegment_returnsItsContent() {
        val result = aggregateAgentReplyText(listOf(text("你好，世界")), null)
        assertEquals("你好，世界", result)
    }

    @Test
    fun multipleTextSegments_joinedWithNewlineInOrder() {
        val result = aggregateAgentReplyText(listOf(text("第一段"), text("第二段")), null)
        assertEquals("第一段\n第二段", result)
    }

    @Test
    fun mixedElements_aggregatesOnlyTextSegments() {
        val result = aggregateAgentReplyText(
            listOf(thinking(), text("第一段"), toolCall(), text("第二段")),
            null
        )
        assertEquals("第一段\n第二段", result)
    }

    @Test
    fun blankTextSegments_areSkipped() {
        val result = aggregateAgentReplyText(listOf(text("   "), text("有效内容"), text("")), null)
        assertEquals("有效内容", result)
    }

    @Test
    fun noTextSegments_fallsBackToLegacyContent() {
        val result = aggregateAgentReplyText(listOf(thinking(), toolCall()), "legacy")
        assertEquals("legacy", result)
    }

    @Test
    fun allTextSegmentsBlank_fallsBackToLegacyContent() {
        assertEquals("legacy", aggregateAgentReplyText(listOf(text("  ")), "legacy"))
    }
}
