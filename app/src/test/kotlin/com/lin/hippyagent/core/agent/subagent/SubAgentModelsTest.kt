package com.lin.hippyagent.core.agent.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentModelsTest {

    @Test
    fun subAgentTaskDefaultMaxTurns() {
        val task = SubAgentTask(agentId = "researcher", prompt = "test")
        assertEquals("researcher", task.agentId)
        assertEquals("test", task.prompt)
        assertEquals(20, task.maxTurns)
    }

    @Test
    fun subAgentTaskCustomMaxTurns() {
        val task = SubAgentTask(agentId = "coder", prompt = "code", maxTurns = 5)
        assertEquals(5, task.maxTurns)
    }

    @Test
    fun childResultDefaults() {
        val result = ChildResult(childId = 1L, jobName = "test", status = "completed")
        assertEquals(1L, result.childId)
        assertEquals("test", result.jobName)
        assertEquals("completed", result.status)
        assertEquals(emptyMap<String, Any>(), result.result)
    }

    @Test
    fun aggregatedResultFields() {
        val children = listOf(
            ChildResult(1L, "task_a", "completed", mapOf("output" to "ok")),
            ChildResult(2L, "task_b", "failed", mapOf("error" to "timeout"))
        )
        val result = AggregatedResult(
            total = 2,
            completed = 1,
            failed = 1,
            children = children,
            summary = "1 completed, 1 failed"
        )
        assertEquals(2, result.total)
        assertEquals(1, result.completed)
        assertEquals(1, result.failed)
        assertEquals(2, result.children.size)
        assertTrue(result.summary.contains("1 completed"))
    }

    @Test
    fun pawJobStatusIncludesWaitingChildren() {
        val statuses = com.lin.hippyagent.core.task.PawJobStatus.values()
        assertTrue(statuses.any { it.name == "WAITING_CHILDREN" })
    }

    @Test
    fun pawJobSubmitOptsMaxChildrenDefaultNull() {
        val opts = com.lin.hippyagent.core.task.PawJobSubmitOpts()
        assertEquals(null, opts.maxChildren)
    }

    @Test
    fun pawJobSubmitOptsMaxChildrenCustom() {
        val opts = com.lin.hippyagent.core.task.PawJobSubmitOpts(maxChildren = 5)
        assertEquals(5, opts.maxChildren)
    }
}
