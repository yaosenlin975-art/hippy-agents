package com.lin.hippyagent.core.task

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface HippyJobDao {
    @Insert
    suspend fun insert(job: HippyJobEntity): Long

    @Update
    suspend fun update(job: HippyJobEntity)

    @Query("SELECT * FROM hippy_jobs WHERE id = :id")
    suspend fun getById(id: Long): HippyJobEntity?

    @Query("SELECT * FROM hippy_jobs WHERE idempotencyKey = :key LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): HippyJobEntity?

    @Query("SELECT count(*) FROM hippy_jobs WHERE name = :name AND queue = :queue AND status = 'WAITING'")
    suspend fun countWaiting(name: String, queue: String): Int

    @Query("SELECT * FROM hippy_jobs WHERE name = :name AND queue = :queue AND status = 'WAITING' ORDER BY createdAt DESC LIMIT 1")
    suspend fun findMostRecentWaiting(name: String, queue: String): HippyJobEntity?

    @Query("""
        UPDATE hippy_jobs SET status = 'ACTIVE', lockToken = :lockToken, lockUntil = :lockUntil,
        startedAt = COALESCE(startedAt, :now), updatedAt = :now,
        timeoutAt = CASE WHEN timeoutMs IS NOT NULL THEN :lockUntil ELSE timeoutAt END
        WHERE id = (
            SELECT id FROM hippy_jobs WHERE queue = :queue AND status = 'WAITING'
            AND name IN (:names) ORDER BY priority ASC, createdAt ASC LIMIT 1
        ) AND lockToken IS NULL
    """)
    suspend fun claimNext(queue: String, names: List<String>, lockToken: String, lockUntil: Long, now: Long): Int

    @Query("SELECT * FROM hippy_jobs WHERE lockToken = :lockToken LIMIT 1")
    suspend fun getByLockToken(lockToken: String): HippyJobEntity?

    @Query("UPDATE hippy_jobs SET status = 'WAITING', delayUntil = NULL WHERE status = 'DELAYED' AND delayUntil IS NOT NULL AND delayUntil <= :now")
    suspend fun promoteDelayed(now: Long): Int

    @Query("UPDATE hippy_jobs SET status = 'WAITING', lockToken = NULL, lockUntil = NULL, stalledCounter = :stalled, updatedAt = :now WHERE id = :id")
    suspend fun requeueAsWaiting(id: Long, stalled: Int, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM hippy_jobs WHERE status = 'ACTIVE' AND lockUntil IS NOT NULL AND lockUntil < :now LIMIT :limit OFFSET :offset")
    suspend fun findStalled(now: Long, limit: Int = 100, offset: Int = 0): List<HippyJobEntity>

    @Query("SELECT * FROM hippy_jobs WHERE status = 'ACTIVE' AND timeoutAt IS NOT NULL AND timeoutAt < :now LIMIT :limit OFFSET :offset")
    suspend fun findTimedOut(now: Long, limit: Int = 100, offset: Int = 0): List<HippyJobEntity>

    @Query("UPDATE hippy_jobs SET status = 'FAILED', errorText = :errorText, finishedAt = :finishedAt, lockToken = NULL, lockUntil = NULL, updatedAt = :now WHERE id = :id")
    suspend fun markAsFailed(id: Long, errorText: String, finishedAt: Long = System.currentTimeMillis(), now: Long = System.currentTimeMillis())

    @Query("UPDATE hippy_jobs SET status = 'COMPLETED', resultJson = :resultJson, finishedAt = :finishedAt, lockToken = NULL, lockUntil = NULL, updatedAt = :now WHERE id = :id AND lockToken = :lockToken")
    suspend fun completeJob(id: Long, lockToken: String, resultJson: String, finishedAt: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE hippy_jobs SET status = :status, errorText = :errorText, attemptsMade = :attemptsMade, delayUntil = :delayUntil, finishedAt = :finishedAt, lockToken = NULL, lockUntil = NULL, updatedAt = :updatedAt WHERE id = :id AND lockToken = :lockToken")
    suspend fun failJob(id: Long, lockToken: String, status: HippyJobStatus, errorText: String, attemptsMade: Int, delayUntil: Long?, finishedAt: Long?, updatedAt: Long)

    @Query("UPDATE hippy_jobs SET tokensInput = tokensInput + :input, tokensOutput = tokensOutput + :output, updatedAt = :now WHERE id = :id AND status NOT IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun updateTokens(id: Long, input: Long, output: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE hippy_jobs SET progressJson = :progressJson, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: Long, progressJson: String, now: Long = System.currentTimeMillis())

    @Query("SELECT count(*) FROM hippy_jobs WHERE parentJobId = :parentId AND status NOT IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun countActiveChildren(parentId: Long): Int

    @Query("UPDATE hippy_jobs SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: HippyJobStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE hippy_jobs SET parentJobId = NULL WHERE id = :id")
    suspend fun clearParentDependency(id: Long)

    @Query("SELECT * FROM hippy_jobs WHERE parentJobId = :parentId LIMIT :limit OFFSET :offset")
    suspend fun getChildren(parentId: Long, limit: Int = 100, offset: Int = 0): List<HippyJobEntity>

    @Query("SELECT * FROM hippy_jobs WHERE status IN ('WAITING','ACTIVE','DELAYED') ORDER BY priority ASC, createdAt ASC LIMIT :limit OFFSET :offset")
    suspend fun getPendingJobs(limit: Int = 100, offset: Int = 0): List<HippyJobEntity>

    @Insert
    suspend fun insertInbox(entity: HippyInboxEntity)

    @Query("SELECT * FROM hippy_inbox WHERE jobId = :jobId AND readAt IS NULL ORDER BY sentAt ASC LIMIT :limit OFFSET :offset")
    suspend fun getUnreadInbox(jobId: Long, limit: Int = 100, offset: Int = 0): List<HippyInboxEntity>

    @Query("UPDATE hippy_inbox SET readAt = :now WHERE jobId = :jobId AND readAt IS NULL")
    suspend fun markInboxRead(jobId: Long, now: Long = System.currentTimeMillis())
}
