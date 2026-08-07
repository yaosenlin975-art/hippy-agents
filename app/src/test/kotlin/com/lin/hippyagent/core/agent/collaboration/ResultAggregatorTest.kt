package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.agent.Agent
import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.session.LocalSessionStore
import com.lin.hippyagent.core.agent.session.MessageRole
import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.firstArg
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ResultAggregator 结果聚合测试：验证聚合结果包含各智能体的实际回复内容，
 * 而不是被丢弃为 "OK"（WS-26 缺陷回归覆盖）。
 */
class ResultAggregatorTest {

    private fun aggregator(
        sessionStore: LocalSessionStore = LocalSessionStore(),
        agentFactory: AgentFactory = mockk<AgentFactory>()
    ) = ResultAggregator(agentFactory, sessionStore)

    @Test
    fun aggregateResults_includesActualAgentReplies() = runTest {
        val sessionStore = LocalSessionStore()
        val agentFactory = mockk<AgentFactory>()
        val aggregator = aggregator(sessionStore, agentFactory)

        val agent1 = mockk<Agent>()
        val agent2 = mockk<Agent>()
        coEvery { agentFactory.getAgent("agent-1") } returns agent1
        coEvery { agentFactory.getAgent("agent-2") } returns agent2
        coEvery { agent1.processMessage(any(), any(), any()) } coAnswers {
            sessionStore.addMessage(firstArg(), MessageRole.ASSISTANT, "分析报告：数据增长 20%")
            Result.success(Unit)
        }
        coEvery { agent2.processMessage(any(), any(), any()) } coAnswers {
            sessionStore.addMessage(firstArg(), MessageRole.ASSISTANT, "生成报告完成：包含图表")
            Result.success(Unit)
        }

        val result = aggregator.aggregateResults(listOf("agent-1", "agent-2"), "帮我分析这份数据并生成报告")

        assertEquals(2, result.successfulAgents)
        assertEquals("分析报告：数据增长 20%", result.results[0].content)
        assertEquals("生成报告完成：包含图表", result.results[1].content)
        assertTrue(result.summary.contains("分析报告：数据增长 20%"))
        assertTrue(result.summary.contains("生成报告完成：包含图表"))
        assertFalse(result.summary.contains("OK"))
    }

    @Test
    fun aggregateResults_stripsThinkingBlockFromReply() = runTest {
        val sessionStore = LocalSessionStore()
        val agentFactory = mockk<AgentFactory>()
        val aggregator = aggregator(sessionStore, agentFactory)

        val agent = mockk<Agent>()
        coEvery { agentFactory.getAgent("agent-1") } returns agent
        coEvery { agent.processMessage(any(), any(), any()) } coAnswers {
            sessionStore.addMessage(firstArg(), MessageRole.ASSISTANT, "⋞思考过程…⋟\n最终结论：营收翻倍")
            Result.success(Unit)
        }

        val result = aggregator.aggregateResults(listOf("agent-1"), "分析", AggregationStrategy.BEST)

        assertEquals("最终结论：营收翻倍", result.results[0].content)
        assertTrue(result.summary.contains("最终结论：营收翻倍"))
    }

    @Test
    fun aggregateResults_successWithNoReply_keepsEmptyContent() = runTest {
        val sessionStore = LocalSessionStore()
        val agentFactory = mockk<AgentFactory>()
        val aggregator = aggregator(sessionStore, agentFactory)

        val agent = mockk<Agent>()
        coEvery { agentFactory.getAgent("agent-1") } returns agent
        coEvery { agent.processMessage(any(), any(), any()) } returns Result.success(Unit)

        val result = aggregator.aggregateResults(listOf("agent-1"), "分析")

        assertTrue(result.results[0].success)
        assertEquals("", result.results[0].content)
        assertTrue(result.summary.contains("（无回复内容）"))
    }

    @Test
    fun aggregateResults_failure_keepsErrorAndEmptyContent() = runTest {
        val sessionStore = LocalSessionStore()
        val agentFactory = mockk<AgentFactory>()
        val aggregator = aggregator(sessionStore, agentFactory)

        val agent = mockk<Agent>()
        coEvery { agentFactory.getAgent("agent-1") } returns agent
        coEvery { agent.processMessage(any(), any(), any()) } returns
            Result.failure(RuntimeException("LLM 调用失败"))

        val result = aggregator.aggregateResults(listOf("agent-1"), "分析", AggregationStrategy.MERGE)

        assertEquals(0, result.successfulAgents)
        assertTrue(result.hasErrors)
        assertEquals("LLM 调用失败", result.results[0].error)
        assertEquals("", result.results[0].content)
        assertTrue(result.summary.contains("No successful results"))
    }

    @Test
    fun aggregateResults_missingAgent_reportsError() = runTest {
        val sessionStore = LocalSessionStore()
        val agentFactory = mockk<AgentFactory>()
        val aggregator = aggregator(sessionStore, agentFactory)

        coEvery { agentFactory.getAgent("ghost") } returns null

        val result = aggregator.aggregateResults(listOf("ghost"), "分析")

        assertFalse(result.results[0].success)
        assertEquals("Agent not found: ghost", result.results[0].error)
        assertTrue(result.hasErrors)
    }
}
