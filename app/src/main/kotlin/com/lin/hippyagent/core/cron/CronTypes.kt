package com.lin.hippyagent.core.cron

import kotlinx.serialization.Serializable

@Serializable
data class CronSchedule(
    val raw: String,
    val fieldCount: Int,
    @Serializable(with = NullableIntRangeSerializer::class)
    val second: IntRange? = null,
    @Serializable(with = IntRangeSerializer::class)
    val minute: IntRange,
    @Serializable(with = IntRangeSerializer::class)
    val hour: IntRange,
    @Serializable(with = IntRangeSerializer::class)
    val dayOfMonth: IntRange,
    @Serializable(with = IntRangeSerializer::class)
    val month: IntRange,
    @Serializable(with = IntRangeSerializer::class)
    val dayOfWeek: IntRange
)

enum class CronRunStatus { SUCCESS, FAILED, SKIPPED }

@Serializable
data class CronRunRecord(
    val id: String = com.lin.hippyagent.core.pool.FastId.next(),
    val taskId: String,
    val scheduledTime: Long,
    val actualFireTime: Long,
    val completedAt: Long? = null,
    val status: String = CronRunStatus.SUCCESS.name,
    val errorMessage: String? = null,
    val durationMs: Long = 0L
)

@Serializable
data class CronTask(
    val id: String = com.lin.hippyagent.core.pool.FastId.next(),
    val name: String,
    val cronExpression: String,
    val taskRef: String,
    val enabled: Boolean = true,
    val nextFireTime: Long = 0L,
    val lastRunId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
