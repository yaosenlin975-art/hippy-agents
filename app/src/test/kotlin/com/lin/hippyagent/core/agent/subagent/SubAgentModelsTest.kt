package com.lin.hippyagent.core.agent.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentModelsTest {

    @Test
    fun subAgentTaskDefaults() {
        val task = SubAgentTask(prompt = "test")
        assertEquals("test", task.prompt)
        assertEquals(20, task.maxTurns)
        assertEquals("", task.taskId)
    }

    @Test
    fun subAgentTaskCustomMaxTurns() {
        val task = SubAgentTask(prompt = "code", maxTurns = 5)
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
}
