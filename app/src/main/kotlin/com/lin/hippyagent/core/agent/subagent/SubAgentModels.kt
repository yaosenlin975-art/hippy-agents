package com.lin.hippyagent.core.agent.subagent

data class SubAgentTask(
    val prompt: String,
    val maxTurns: Int = 20,
    val context: Map<String, String> = emptyMap(),
    val taskId: String = ""
)

data class ChildResult(
    val childId: Long,
    val jobName: String,
    val status: String,
    val result: Map<String, Any> = emptyMap()
)

data class AggregatedResult(
    val total: Int,
    val completed: Int,
    val failed: Int,
    val children: List<ChildResult>,
    val summary: String,
    val timedOut: Boolean = false
)

data class SpawnMultipleResult(
    val parentJobId: Long,
    val jobs: List<com.lin.hippyagent.core.task.HippyJobEntity>
)
