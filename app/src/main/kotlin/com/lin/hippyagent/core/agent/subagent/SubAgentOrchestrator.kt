package com.lin.hippyagent.core.agent.subagent

import com.lin.hippyagent.core.task.ChildFailPolicy
import com.lin.hippyagent.core.task.HippyJobDao
import com.lin.hippyagent.core.task.HippyJobEntity
import com.lin.hippyagent.core.task.HippyJobQueue
import com.lin.hippyagent.core.task.HippyJobSubmitOpts
import kotlinx.coroutines.delay
import timber.log.Timber

class SubAgentOrchestrator(
    private val jobQueue: HippyJobQueue,
    private val dao: HippyJobDao,
    private val aggregator: SubAgentAggregator
) {

    suspend fun spawnSubAgent(
        agentId: String,
        prompt: String,
        parentSessionId: String = "",
        parentJobId: Long? = null,
        maxTurns: Int = 20,
        onChildFail: ChildFailPolicy = ChildFailPolicy.FAIL_PARENT,
        maxChildren: Int? = null,
        parentContext: Map<String, String> = emptyMap()
    ): HippyJobEntity {
        if (parentJobId != null) {
            val limit = maxChildren ?: DEFAULT_MAX_CHILDREN
            val activeChildren = dao.countActiveChildren(parentJobId)
            if (activeChildren >= limit) {
                throw IllegalStateException("Exceeded max children per parent: $limit")
            }
        }

        val enrichedPrompt = if (parentContext.isNotEmpty()) {
            val contextBlock = parentContext.entries.joinToString("\n") { (k, v) ->
                "- $k: $v"
            }
            "<parent_context>\n以下是来自父代理的上下文信息，请参考这些信息执行任务：\n$contextBlock\n</parent_context>\n\n$prompt"
        } else {
            prompt
        }

        return jobQueue.submit(
            name = "subagent_loop",
            data = mapOf(
                "agent_id" to agentId,
                "prompt" to enrichedPrompt,
                "max_turns" to maxTurns,
                "parent_session_id" to parentSessionId
            ),
            opts = HippyJobSubmitOpts(
                parentJobId = parentJobId,
                onChildFail = onChildFail,
                maxChildren = maxChildren
            )
        )
    }

    suspend fun createParentTask(): HippyJobEntity {
        return jobQueue.submit(
            name = "subagent_parent",
            data = mapOf("type" to "subagent_parent")
        )
    }

    suspend fun spawnMultiple(
        tasks: List<SubAgentTask>,
        agentId: String = "",
        parentSessionId: String = "",
        parentJobId: Long? = null,
        onChildFail: ChildFailPolicy = ChildFailPolicy.FAIL_PARENT
    ): SpawnMultipleResult {
        val parentId = parentJobId ?: createParentTask().id

        val jobs = tasks.mapIndexed { index, task ->
            val taskId = task.taskId.ifBlank { "task_$index" }
            spawnSubAgent(
                agentId = agentId,
                prompt = task.prompt,
                parentSessionId = parentSessionId,
                parentJobId = parentId,
                maxTurns = task.maxTurns,
                onChildFail = onChildFail,
                parentContext = task.context
            )
        }

        return SpawnMultipleResult(parentJobId = parentId, jobs = jobs)
    }

    suspend fun awaitChildren(parentJobId: Long, timeoutMs: Long = DEFAULT_TIMEOUT_MS): AggregatedResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false

        while (System.currentTimeMillis() < deadline) {
            val active = dao.countActiveChildren(parentJobId)
            if (active == 0) {
                Timber.d("SubAgentOrchestrator: all children done for parent=$parentJobId")
                break
            }
            delay(POLL_INTERVAL_MS)
        }

        if (System.currentTimeMillis() >= deadline) {
            val active = dao.countActiveChildren(parentJobId)
            if (active > 0) {
                Timber.w("SubAgentOrchestrator: timed out waiting for children of parent=$parentJobId, $active still active")
                timedOut = true
            }
        }

        return aggregator.aggregate(parentJobId).copy(timedOut = timedOut)
    }

    suspend fun getChildrenStatus(parentJobId: Long): List<HippyJobEntity> {
        return dao.getChildren(parentJobId)
    }

    companion object {
        private const val DEFAULT_MAX_CHILDREN = 3
        private const val DEFAULT_TIMEOUT_MS = 600_000L
        private const val POLL_INTERVAL_MS = 1000L
        val SUBAGENT_DISABLED_TOOLS = listOf(
            "spawn_subagent",
            "spawn_sub_agent",
            "check_subagent_tasks",
            "aggregate_subagent_results",
            "chat_with_agent"
        )
    }
}
