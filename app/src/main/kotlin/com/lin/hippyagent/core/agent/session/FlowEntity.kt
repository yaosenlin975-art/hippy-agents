package com.lin.hippyagent.core.agent.session

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Entity(
    tableName = "flow_records",
    indices = [
        Index("agentId"),
        Index("status"),
        Index("createdAt")
    ]
)
data class FlowRecordEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val name: String,
    val status: String = "queued",
    val revision: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val result: String? = null
)

@Entity(
    tableName = "flow_steps",
    indices = [
        Index("flowId")
    ]
)
data class FlowStepEntity(
    @PrimaryKey val id: String,
    val flowId: String,
    val stepIndex: Int,
    val taskDescription: String,
    val status: String = "pending",
    val result: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)

@Dao
interface FlowRecordDao {
    @Insert
    suspend fun insert(entity: FlowRecordEntity)

    @Update
    suspend fun update(entity: FlowRecordEntity)

    @Query("SELECT * FROM flow_records WHERE id = :flowId")
    suspend fun getById(flowId: String): FlowRecordEntity?

    @Query("SELECT * FROM flow_records WHERE agentId = :agentId ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getByAgentId(agentId: String, limit: Int = 100, offset: Int = 0): List<FlowRecordEntity>

    @Query("SELECT * FROM flow_records WHERE status = :status ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getByStatus(status: String, limit: Int = 100, offset: Int = 0): List<FlowRecordEntity>
}

@Dao
interface FlowStepDao {
    @Insert
    suspend fun insert(entity: FlowStepEntity)

    @Insert
    suspend fun insertAll(entities: List<FlowStepEntity>)

    @Update
    suspend fun update(entity: FlowStepEntity)

    @Query("SELECT * FROM flow_steps WHERE flowId = :flowId ORDER BY stepIndex ASC LIMIT :limit OFFSET :offset")
    suspend fun getByFlowId(flowId: String, limit: Int = 100, offset: Int = 0): List<FlowStepEntity>
}
