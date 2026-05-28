package com.lin.hippyagent.core.task

import timber.log.Timber
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

class HippyJobQueue(private val dao: HippyJobDao) {

    suspend fun submit(
        name: String,
        data: Map<String, Any> = emptyMap(),
        opts: HippyJobSubmitOpts = HippyJobSubmitOpts()
    ): HippyJobEntity {
        if (opts.idempotencyKey != null) {
            val existing = dao.findByIdempotencyKey(opts.idempotencyKey)
            if (existing != null) return existing
        }

        if (opts.maxWaiting != null) {
            val waitingCount = dao.countWaiting(name, opts.queue)
            if (waitingCount >= opts.maxWaiting) {
                val recent = dao.findMostRecentWaiting(name, opts.queue)
                return recent ?: throw IllegalStateException("Backpressure triggered but no waiting job")
            }
        }

        val depth = if (opts.parentJobId != null) {
            val parent = dao.getById(opts.parentJobId) ?: throw IllegalArgumentException("Parent job not found")
            if (parent.depth >= MAX_SPAWN_DEPTH) {
                throw IllegalArgumentException("Exceeded max spawn depth $MAX_SPAWN_DEPTH")
            }
            parent.depth + 1
        } else 0

        val dataJson = HippyJobJson.mapToJson(data)

        val entity = HippyJobEntity(
            name = name,
            queue = opts.queue,
            priority = opts.priority,
            dataJson = dataJson,
            maxAttempts = opts.maxAttempts,
            backoffType = opts.backoffType,
            backoffDelayMs = opts.backoffDelayMs,
            backoffJitter = opts.backoffJitter,
            maxStalled = opts.maxStalled,
            timeoutMs = opts.timeoutMs,
            idempotencyKey = opts.idempotencyKey,
            maxWaiting = opts.maxWaiting,
            depth = depth,
            delayUntil = opts.delay?.let { System.currentTimeMillis() + it },
            parentJobId = opts.parentJobId,
            onChildFail = opts.onChildFail
        )

        val id = dao.insert(entity)

        if (opts.parentJobId != null) {
            val parent = dao.getById(opts.parentJobId)
            if (parent != null && parent.status != HippyJobStatus.WAITING_CHILDREN) {
                dao.updateStatus(opts.parentJobId, HippyJobStatus.WAITING_CHILDREN)
            }
        }

        return dao.getById(id) ?: entity
    }

    suspend fun claim(queue: String, registeredNames: List<String>): HippyJobEntity? {
        val now = System.currentTimeMillis()
        val lockToken = UUID.randomUUID().toString()
        val lockUntil = now + DEFAULT_LOCK_DURATION_MS

        dao.promoteDelayed(now)

        val rowsUpdated = dao.claimNext(queue, registeredNames, lockToken, lockUntil, now)
        if (rowsUpdated <= 0) return null

        return dao.getByLockToken(lockToken)
    }

    suspend fun complete(jobId: Long, lockToken: String, result: Map<String, Any>?) {
        val job = dao.getById(jobId) ?: return
        if (job.lockToken != lockToken) return

        val now = System.currentTimeMillis()
        val resultJson = result?.let { HippyJobJson.mapToJson(it) } ?: "{}"

        dao.completeJob(jobId, lockToken, resultJson, now)

        job.parentJobId?.let { parentId ->
            notifyParentChildDone(parentId, jobId, job.name, result)
        }
    }

    suspend fun fail(jobId: Long, lockToken: String, errorText: String) {
        val job = dao.getById(jobId) ?: return
        if (job.lockToken != lockToken) return

        val isUnrecoverable = errorText.contains("Unrecoverable")
        val attemptsExhausted = job.attemptsMade + 1 >= job.maxAttempts

        val newStatus = if (isUnrecoverable || attemptsExhausted) {
            HippyJobStatus.FAILED
        } else {
            HippyJobStatus.DELAYED
        }

        val backoffMs = if (newStatus == HippyJobStatus.DELAYED) {
            calculateBackoff(job.backoffType, job.backoffDelayMs, job.backoffJitter, job.attemptsMade + 1)
        } else 0L

        val now = System.currentTimeMillis()
        dao.failJob(
            id = jobId,
            lockToken = lockToken,
            status = newStatus,
            errorText = errorText,
            attemptsMade = job.attemptsMade + 1,
            delayUntil = if (newStatus == HippyJobStatus.DELAYED) now + backoffMs else null,
            finishedAt = if (newStatus == HippyJobStatus.FAILED) now else null,
            updatedAt = now
        )

        job.parentJobId?.let { parentId ->
            when (job.onChildFail) {
                ChildFailPolicy.FAIL_PARENT -> {
                    dao.markAsFailed(parentId, "Child job $jobId failed: $errorText")
                }
                ChildFailPolicy.REMOVE_DEP -> {
                    dao.clearParentDependency(jobId)
                }
                else -> {}
            }
        }
    }

    suspend fun cancel(jobId: Long) {
        val now = System.currentTimeMillis()
        dao.updateStatus(jobId, HippyJobStatus.CANCELLED, now)
    }

    private suspend fun notifyParentChildDone(
        parentId: Long, childId: Long, childName: String, result: Map<String, Any>?
    ) {
        val payload = mapOf(
            "type" to "child_done",
            "child_id" to childId,
            "job_name" to childName,
            "result" to (result ?: emptyMap<String, Any>())
        )
        dao.insertInbox(
            HippyInboxEntity(
                jobId = parentId,
                sender = "minions",
                payloadJson = HippyJobJson.mapToJson(payload)
            )
        )

        val activeChildren = dao.countActiveChildren(parentId)
        if (activeChildren == 0) {
            dao.updateStatus(parentId, HippyJobStatus.WAITING)
        }
    }

    private fun calculateBackoff(type: BackoffType, baseDelay: Long, jitter: Float, attempt: Int): Long {
        val delay = when (type) {
            BackoffType.FIXED -> baseDelay
            BackoffType.EXPONENTIAL -> baseDelay * (1L shl min(attempt - 1, 5))
        }
        val jitterRange = (delay * jitter).toLong()
        return delay + Random.nextLong(-jitterRange, jitterRange + 1)
    }

    companion object {
        private const val MAX_SPAWN_DEPTH = 5
        private const val DEFAULT_LOCK_DURATION_MS = 30_000L
    }
}
