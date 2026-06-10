package com.lin.hippyagent.core.agent.task

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverter
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TaskStatus {
    PENDING,
    RUNNING,
    AWAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    /**
     * 共享的状态机校验,被 TaskExecutionEngine 和 TaskApprovalService 共用。
     * 任何状态写入都应先经过这里,避免"暗箱状态改写"。
     */
    fun canTransitionTo(next: TaskStatus): Boolean = when (this) {
        PENDING -> next == RUNNING
        RUNNING ->
            next == AWAITING_APPROVAL ||
                next == COMPLETED ||
                next == FAILED ||
                next == CANCELLED
        AWAITING_APPROVAL ->
            next == RUNNING ||
                next == FAILED ||
                next == CANCELLED
        COMPLETED, FAILED, CANCELLED -> false
    }
}

enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    MODIFIED,
    TIMEOUT
}

data class TaskStep(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val requiresApproval: Boolean = false,
    val toolRef: String? = null,
    val payload: String? = null,
    val status: StepStatus = StepStatus.PENDING,
    val result: String? = null,
    val error: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null
)

data class ExecutionContext(
    val snapshot: String,
    val currentStepId: String? = null,
    val variables: Map<String, String> = emptyMap()
)

data class ApprovalNode(
    val id: String = UUID.randomUUID().toString(),
    val stepId: String,
    val prompt: String,
    val options: List<String> = listOf("approve", "reject", "modify"),
    val timeoutSec: Long = 300,
    val status: ApprovalStatus = ApprovalStatus.PENDING,
    val decidedBy: String? = null,
    val decidedAt: Long? = null,
    val decisionReason: String? = null
)

@Entity(
    tableName = "executable_tasks",
    indices = [
        Index("status"),
        Index("created_at"),
        Index("agent_id"),
        Index("source"),
        Index(value = ["status", "source", "created_at"])
    ]
)
data class TaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    @ColumnInfo(name = "agent_id") val agentId: String,
    val sessionId: String? = null,
    val status: TaskStatus,
    val source: String = "task",
    val steps: List<TaskStep>,
    val executionContext: ExecutionContext,
    val approvalNodes: List<ApprovalNode>,
    val result: String? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null
)

class TaskTypeConverters {

    @TypeConverter
    fun fromTaskStatus(value: TaskStatus?): String? = value?.name

    @TypeConverter
    fun toTaskStatus(value: String?): TaskStatus? =
        value?.let { runCatching { TaskStatus.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromStepStatus(value: StepStatus?): String? = value?.name

    @TypeConverter
    fun toStepStatus(value: String?): StepStatus? =
        value?.let { runCatching { StepStatus.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromApprovalStatus(value: ApprovalStatus?): String? = value?.name

    @TypeConverter
    fun toApprovalStatus(value: String?): ApprovalStatus? =
        value?.let { runCatching { ApprovalStatus.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromTaskStepList(value: List<TaskStep>?): String {
        if (value.isNullOrEmpty()) return "[]"
        val arr = JSONArray()
        value.forEach { step ->
            val obj = JSONObject()
            obj.put("id", step.id)
            obj.put("description", step.description)
            obj.put("requiresApproval", step.requiresApproval)
            obj.put("toolRef", step.toolRef ?: JSONObject.NULL)
            obj.put("payload", step.payload ?: JSONObject.NULL)
            obj.put("status", step.status.name)
            obj.put("result", step.result ?: JSONObject.NULL)
            obj.put("error", step.error ?: JSONObject.NULL)
            obj.put("startedAt", step.startedAt ?: JSONObject.NULL)
            obj.put("completedAt", step.completedAt ?: JSONObject.NULL)
            arr.put(obj)
        }
        return arr.toString()
    }

    @TypeConverter
    fun toTaskStepList(value: String?): List<TaskStep> {
        if (value.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(value)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TaskStep(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    description = obj.optString("description", ""),
                    requiresApproval = obj.optBoolean("requiresApproval", false),
                    toolRef = obj.optStringOrNull("toolRef"),
                    payload = obj.optStringOrNull("payload"),
                    status = obj.optString("status", "PENDING")
                        .let { runCatching { StepStatus.valueOf(it) }.getOrDefault(StepStatus.PENDING) },
                    result = obj.optStringOrNull("result"),
                    error = obj.optStringOrNull("error"),
                    startedAt = obj.optLongOrNull("startedAt"),
                    completedAt = obj.optLongOrNull("completedAt")
                )
            }
        }.getOrDefault(emptyList())
    }

    @TypeConverter
    fun fromExecutionContext(value: ExecutionContext?): String {
        if (value == null) return "{}"
        val obj = JSONObject()
        obj.put("snapshot", value.snapshot)
        obj.put("currentStepId", value.currentStepId ?: JSONObject.NULL)
        val varsObj = JSONObject()
        value.variables.forEach { (k, v) -> varsObj.put(k, v) }
        obj.put("variables", varsObj)
        return obj.toString()
    }

    @TypeConverter
    fun toExecutionContext(value: String?): ExecutionContext {
        if (value.isNullOrEmpty()) return ExecutionContext(snapshot = "")
        return runCatching {
            val obj = JSONObject(value)
            val varsObj = obj.optJSONObject("variables")
            val variables = mutableMapOf<String, String>()
            if (varsObj != null) {
                val keys = varsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    variables[k] = varsObj.optString(k, "")
                }
            }
            ExecutionContext(
                snapshot = obj.optString("snapshot", ""),
                currentStepId = obj.optStringOrNull("currentStepId"),
                variables = variables
            )
        }.getOrDefault(ExecutionContext(snapshot = value))
    }

    @TypeConverter
    fun fromApprovalNodeList(value: List<ApprovalNode>?): String {
        if (value.isNullOrEmpty()) return "[]"
        val arr = JSONArray()
        value.forEach { node ->
            val obj = JSONObject()
            obj.put("id", node.id)
            obj.put("stepId", node.stepId)
            obj.put("prompt", node.prompt)
            val optsArr = JSONArray()
            node.options.forEach { optsArr.put(it) }
            obj.put("options", optsArr)
            obj.put("timeoutSec", node.timeoutSec)
            obj.put("status", node.status.name)
            obj.put("decidedBy", node.decidedBy ?: JSONObject.NULL)
            obj.put("decidedAt", node.decidedAt ?: JSONObject.NULL)
            obj.put("decisionReason", node.decisionReason ?: JSONObject.NULL)
            arr.put(obj)
        }
        return arr.toString()
    }

    @TypeConverter
    fun toApprovalNodeList(value: String?): List<ApprovalNode> {
        if (value.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(value)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val optsArr = obj.optJSONArray("options")
                val options = if (optsArr != null) {
                    (0 until optsArr.length()).map { optsArr.optString(it, "") }
                } else emptyList()
                ApprovalNode(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    stepId = obj.optString("stepId", ""),
                    prompt = obj.optString("prompt", ""),
                    options = options,
                    timeoutSec = obj.optLong("timeoutSec", 300L),
                    status = obj.optString("status", "PENDING")
                        .let { runCatching { ApprovalStatus.valueOf(it) }.getOrDefault(ApprovalStatus.PENDING) },
                    decidedBy = obj.optStringOrNull("decidedBy"),
                    decidedAt = obj.optLongOrNull("decidedAt"),
                    decisionReason = obj.optStringOrNull("decisionReason")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name, "").takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.optLongOrNull(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return optLong(name)
    }
}

@Dao
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM executable_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM executable_tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM executable_tasks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM executable_tasks WHERE status = :status ORDER BY created_at DESC")
    fun observeByStatus(status: TaskStatus): Flow<List<TaskEntity>>

    @Query("SELECT * FROM executable_tasks WHERE sessionId = :sessionId ORDER BY created_at DESC")
    fun observeBySession(sessionId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM executable_tasks WHERE agent_id = :agentId ORDER BY created_at DESC")
    fun observeByAgent(agentId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM executable_tasks WHERE executionContext LIKE :keyword ESCAPE '\\' ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun searchByContext(keyword: String, limit: Int = 100, offset: Int = 0): List<TaskEntity>

    @Query("SELECT * FROM executable_tasks WHERE approvalNodes LIKE :nodeIdPattern ESCAPE '\\' ORDER BY updated_at DESC LIMIT 1")
    suspend fun findByApprovalNodeId(nodeIdPattern: String): TaskEntity?

    @Query("SELECT * FROM executable_tasks WHERE source IN (:sources) AND status = :status AND (sessionId IS NULL OR sessionId != :sessionId) ORDER BY created_at DESC LIMIT 1")
    suspend fun findByOtherSessionSourcesStatus(
        sessionId: String,
        sources: List<String>,
        status: TaskStatus
    ): TaskEntity?

    @Query("SELECT * FROM executable_tasks WHERE source IN (:sources) AND status = :status AND sessionId = :sessionId ORDER BY created_at DESC LIMIT 1")
    fun observeCurrentSessionApprovalList(
        sessionId: String,
        sources: List<String>,
        status: TaskStatus
    ): Flow<List<TaskEntity>>

    @Query("SELECT * FROM executable_tasks WHERE source IN (:sources) AND status = :status AND (sessionId IS NULL OR sessionId != :sessionId) ORDER BY created_at DESC LIMIT 1")
    fun observeOtherSessionApprovalList(
        sessionId: String,
        sources: List<String>,
        status: TaskStatus
    ): Flow<List<TaskEntity>>

    @Query("SELECT * FROM executable_tasks WHERE source = :source ORDER BY created_at DESC")
    fun observeBySource(source: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM executable_tasks WHERE status = :status AND source = :source ORDER BY created_at DESC")
    fun observeByStatusAndSource(status: TaskStatus, source: String): Flow<List<TaskEntity>>

    @Query("DELETE FROM executable_tasks WHERE source = :source AND status IN (:statuses) AND created_at < :cutoffMs")
    suspend fun deleteBySourceOlderThanStatuses(
        source: String,
        statuses: List<TaskStatus>,
        cutoffMs: Long
    ): Int
}
