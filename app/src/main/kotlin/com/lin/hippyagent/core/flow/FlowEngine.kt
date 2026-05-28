package com.lin.hippyagent.core.flow

import com.lin.hippyagent.core.agent.session.FlowRecordDao
import com.lin.hippyagent.core.agent.session.FlowRecordEntity
import com.lin.hippyagent.core.agent.session.FlowStepDao
import com.lin.hippyagent.core.agent.session.FlowStepEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class FlowState { QUEUED, RUNNING, WAITING, FINISHED, FAILED, CANCELLED }
enum class StepStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

data class FlowRecord(
    val flowId: String,
    val agentId: String,
    val name: String,
    val state: FlowState = FlowState.QUEUED,
    val revision: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val result: String? = null
)

data class FlowStep(
    val stepId: String,
    val flowId: String,
    val stepIndex: Int,
    val action: String,
    val status: StepStatus = StepStatus.PENDING,
    val result: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)

sealed class FlowMutationResult {
    data class Success(val revision: Int) : FlowMutationResult()
    data class Conflict(val currentRevision: Int) : FlowMutationResult()
    data class Error(val message: String) : FlowMutationResult()
}

class FlowEngine(
    private val flowRecordDao: FlowRecordDao,
    private val flowStepDao: FlowStepDao
) {
    suspend fun createManaged(agentId: String, name: String, steps: List<FlowStep>): Result<FlowRecord> =
        withContext(Dispatchers.IO) {
            runCatching {
                val flowId = com.lin.hippyagent.core.pool.FastId.next()
                val now = System.currentTimeMillis()
                val record = FlowRecord(
                    flowId = flowId,
                    agentId = agentId,
                    name = name,
                    state = FlowState.QUEUED,
                    createdAt = now,
                    updatedAt = now
                )

                flowRecordDao.insert(
                    FlowRecordEntity(
                        id = record.flowId,
                        agentId = record.agentId,
                        name = record.name,
                        status = record.state.name.lowercase(),
                        revision = record.revision,
                        createdAt = record.createdAt,
                        updatedAt = record.updatedAt
                    )
                )

                flowStepDao.insertAll(steps.map { step ->
                    FlowStepEntity(
                        id = step.stepId,
                        flowId = flowId,
                        stepIndex = step.stepIndex,
                        taskDescription = step.action,
                        status = step.status.name.lowercase(),
                        createdAt = step.createdAt
                    )
                })

                record
            }
        }

    suspend fun runFlow(flowId: String, expectedRevision: Int): FlowMutationResult =
        withContext(Dispatchers.IO) {
            val record = flowRecordDao.getById(flowId)
                ?: return@withContext FlowMutationResult.Error("Flow not found: $flowId")

            if (record.revision != expectedRevision) {
                return@withContext FlowMutationResult.Conflict(record.revision)
            }

            val now = System.currentTimeMillis()
            flowRecordDao.update(
                record.copy(
                    status = FlowState.RUNNING.name.lowercase(),
                    revision = expectedRevision + 1,
                    updatedAt = now
                )
            )
            FlowMutationResult.Success(expectedRevision + 1)
        }

    suspend fun setWaiting(flowId: String, expectedRevision: Int): FlowMutationResult =
        withContext(Dispatchers.IO) {
            val record = flowRecordDao.getById(flowId)
                ?: return@withContext FlowMutationResult.Error("Flow not found: $flowId")

            if (record.revision != expectedRevision) {
                return@withContext FlowMutationResult.Conflict(record.revision)
            }

            val now = System.currentTimeMillis()
            flowRecordDao.update(
                record.copy(
                    status = FlowState.WAITING.name.lowercase(),
                    revision = expectedRevision + 1,
                    updatedAt = now
                )
            )
            FlowMutationResult.Success(expectedRevision + 1)
        }

    suspend fun resume(flowId: String, expectedRevision: Int): FlowMutationResult =
        runFlow(flowId, expectedRevision)

    suspend fun finish(flowId: String, expectedRevision: Int, result: String?): FlowMutationResult =
        withContext(Dispatchers.IO) {
            val record = flowRecordDao.getById(flowId)
                ?: return@withContext FlowMutationResult.Error("Flow not found: $flowId")

            if (record.revision != expectedRevision) {
                return@withContext FlowMutationResult.Conflict(record.revision)
            }

            val now = System.currentTimeMillis()
            flowRecordDao.update(
                record.copy(
                    status = FlowState.FINISHED.name.lowercase(),
                    revision = expectedRevision + 1,
                    result = result,
                    finishedAt = now,
                    updatedAt = now
                )
            )
            FlowMutationResult.Success(expectedRevision + 1)
        }

    suspend fun fail(flowId: String, expectedRevision: Int, error: String): FlowMutationResult =
        withContext(Dispatchers.IO) {
            val record = flowRecordDao.getById(flowId)
                ?: return@withContext FlowMutationResult.Error("Flow not found: $flowId")

            if (record.revision != expectedRevision) {
                return@withContext FlowMutationResult.Conflict(record.revision)
            }

            val now = System.currentTimeMillis()
            flowRecordDao.update(
                record.copy(
                    status = FlowState.FAILED.name.lowercase(),
                    revision = expectedRevision + 1,
                    result = error,
                    finishedAt = now,
                    updatedAt = now
                )
            )
            FlowMutationResult.Success(expectedRevision + 1)
        }

    suspend fun completeStep(stepId: String, result: String?) = withContext(Dispatchers.IO) {
        val steps = flowStepDao.getByFlowId(stepId)
        val step = steps.find { it.id == stepId } ?: return@withContext
        flowStepDao.update(
            step.copy(
                status = StepStatus.COMPLETED.name.lowercase(),
                result = result,
                finishedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getFlow(flowId: String): Pair<FlowRecord, List<FlowStep>>? = withContext(Dispatchers.IO) {
        val record = flowRecordDao.getById(flowId) ?: return@withContext null
        val steps = flowStepDao.getByFlowId(flowId)
        record.toDomain() to steps.map { it.toDomain() }
    }

    suspend fun getFlowsForAgent(agentId: String): List<FlowRecord> = withContext(Dispatchers.IO) {
        flowRecordDao.getByAgentId(agentId).map { it.toDomain() }
    }

    private fun FlowRecordEntity.toDomain() = FlowRecord(
        flowId = id,
        agentId = agentId,
        name = name,
        state = FlowState.valueOf(status.uppercase()),
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
        finishedAt = finishedAt,
        result = result
    )

    private fun FlowStepEntity.toDomain() = FlowStep(
        stepId = id,
        flowId = flowId,
        stepIndex = stepIndex,
        action = taskDescription,
        status = StepStatus.valueOf(status.uppercase()),
        result = result,
        createdAt = createdAt,
        finishedAt = finishedAt
    )
}

