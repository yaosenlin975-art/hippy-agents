package com.lin.hippyagent.core.task

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class HippyJobStatus {
    WAITING, ACTIVE, COMPLETED, FAILED, CANCELLED, DELAYED, WAITING_CHILDREN
}

enum class BackoffType {
    FIXED, EXPONENTIAL
}

enum class ChildFailPolicy {
    FAIL_PARENT, REMOVE_DEP, IGNORE, CONTINUE
}

@Entity(
    tableName = "hippy_jobs",
    indices = [
        Index(value = ["status", "priority", "createdAt"]),
        Index(value = ["name", "status"]),
        Index(value = ["parentJobId"]),
        Index(value = ["idempotencyKey"], unique = true)
    ]
)
data class HippyJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val queue: String = "default",
    val status: HippyJobStatus = HippyJobStatus.WAITING,
    val priority: Int = 0,
    val dataJson: String = "{}",
    val maxAttempts: Int = 3,
    val attemptsMade: Int = 0,
    val backoffType: BackoffType = BackoffType.EXPONENTIAL,
    val backoffDelayMs: Long = 5000,
    val backoffJitter: Float = 0.1f,
    val maxStalled: Int = 3,
    @ColumnInfo(defaultValue = "NULL") val lockToken: String? = null,
    @ColumnInfo(defaultValue = "NULL") val lockUntil: Long? = null,
    val stalledCounter: Int = 0,
    @ColumnInfo(defaultValue = "NULL") val delayUntil: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val parentJobId: Long? = null,
    val onChildFail: ChildFailPolicy = ChildFailPolicy.CONTINUE,
    @ColumnInfo(defaultValue = "NULL") val timeoutMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val timeoutAt: Long? = null,
    val depth: Int = 0,
    @ColumnInfo(defaultValue = "NULL") val idempotencyKey: String? = null,
    @ColumnInfo(defaultValue = "NULL") val maxWaiting: Int? = null,
    val tokensInput: Long = 0,
    val tokensOutput: Long = 0,
    @ColumnInfo(defaultValue = "NULL") val resultJson: String? = null,
    @ColumnInfo(defaultValue = "NULL") val errorText: String? = null,
    @ColumnInfo(defaultValue = "NULL") val progressJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "NULL") val startedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val finishedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "hippy_inbox",
    indices = [Index(value = ["jobId"])]
)
data class HippyInboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val sender: String,
    val payloadJson: String,
    val readAt: Long? = null,
    val sentAt: Long = System.currentTimeMillis()
)
