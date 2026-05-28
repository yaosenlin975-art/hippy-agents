package com.lin.hippyagent.core.task

import java.util.concurrent.Semaphore

class RateLimiter(maxConcurrent: Int = 8) {
    private val semaphore = Semaphore(maxConcurrent.coerceAtLeast(1))

    fun tryAcquire(): Boolean {
        return semaphore.tryAcquire()
    }

    fun release() {
        semaphore.release()
    }

    val currentCount: Int get() = semaphore.availablePermits()
}
