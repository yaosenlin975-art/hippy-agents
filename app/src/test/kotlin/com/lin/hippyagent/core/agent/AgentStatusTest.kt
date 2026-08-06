package com.lin.hippyagent.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent.kt 中纯逻辑部分的状态机测试（AgentStatus 转换规则）。
 * Agent 主循环（LLM 调用/上下文压缩）依赖 ModelClient 等重型组件，不在此覆盖。
 */
class AgentStatusTest {

    @Test
    fun idle_canTransitionTo_thinkingErrorStopped() {
        assertTrue(AgentStatus.IDLE.canTransitionTo(AgentStatus.THINKING))
        assertTrue(AgentStatus.IDLE.canTransitionTo(AgentStatus.ERROR))
        assertTrue(AgentStatus.IDLE.canTransitionTo(AgentStatus.STOPPED))
        assertFalse(AgentStatus.IDLE.canTransitionTo(AgentStatus.EXECUTING_TOOL))
    }

    @Test
    fun thinking_canTransitionTo_toolExecutionIdleErrorStopped() {
        assertTrue(AgentStatus.THINKING.canTransitionTo(AgentStatus.EXECUTING_TOOL))
        assertTrue(AgentStatus.THINKING.canTransitionTo(AgentStatus.IDLE))
        assertTrue(AgentStatus.THINKING.canTransitionTo(AgentStatus.ERROR))
        assertTrue(AgentStatus.THINKING.canTransitionTo(AgentStatus.STOPPED))
        assertFalse(AgentStatus.THINKING.canTransitionTo(AgentStatus.THINKING))
    }

    @Test
    fun executingTool_canTransitionBackToThinking() {
        assertTrue(AgentStatus.EXECUTING_TOOL.canTransitionTo(AgentStatus.THINKING))
        assertTrue(AgentStatus.EXECUTING_TOOL.canTransitionTo(AgentStatus.IDLE))
        assertTrue(AgentStatus.EXECUTING_TOOL.canTransitionTo(AgentStatus.ERROR))
        assertTrue(AgentStatus.EXECUTING_TOOL.canTransitionTo(AgentStatus.STOPPED))
        assertFalse(AgentStatus.EXECUTING_TOOL.canTransitionTo(AgentStatus.EXECUTING_TOOL))
    }

    @Test
    fun error_canRecoverToIdleThinkingStopped() {
        assertTrue(AgentStatus.ERROR.canTransitionTo(AgentStatus.IDLE))
        assertTrue(AgentStatus.ERROR.canTransitionTo(AgentStatus.THINKING))
        assertTrue(AgentStatus.ERROR.canTransitionTo(AgentStatus.STOPPED))
        assertFalse(AgentStatus.ERROR.canTransitionTo(AgentStatus.EXECUTING_TOOL))
    }

    @Test
    fun stopped_canResumeToIdleOrThinking() {
        assertTrue(AgentStatus.STOPPED.canTransitionTo(AgentStatus.IDLE))
        assertTrue(AgentStatus.STOPPED.canTransitionTo(AgentStatus.THINKING))
        assertFalse(AgentStatus.STOPPED.canTransitionTo(AgentStatus.EXECUTING_TOOL))
        assertFalse(AgentStatus.STOPPED.canTransitionTo(AgentStatus.STOPPED))
    }
}
