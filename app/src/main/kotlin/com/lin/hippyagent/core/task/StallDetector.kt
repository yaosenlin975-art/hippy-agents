package com.lin.hippyagent.core.task

import timber.log.Timber

class StallDetector(private val dao: HippyJobDao) {

    suspend fun detectAndRecover() {
        val now = System.currentTimeMillis()

        val stalled = dao.findStalled(now)
        for (job in stalled) {
            val newCounter = job.stalledCounter + 1
            if (newCounter < job.maxStalled) {
                Timber.w("Stall detected for job ${job.id}, requeueing (attempt $newCounter/${job.maxStalled})")
                dao.requeueAsWaiting(job.id, newCounter, now)
            } else {
                Timber.e("Job ${job.id} exceeded max stalled count, marking as failed")
                dao.markAsFailed(job.id, "max stalled count exceeded", now)
            }
        }

        val timedOut = dao.findTimedOut(now)
        for (job in timedOut) {
            Timber.e("Job ${job.id} timed out, marking as failed")
            dao.markAsFailed(job.id, "timeout exceeded", now)
        }
    }
}

