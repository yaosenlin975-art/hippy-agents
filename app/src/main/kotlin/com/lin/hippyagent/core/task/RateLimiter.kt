package com.lin.hippyagent.core.task

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

class RateLimiter(maxConcurrent: Int = 8) {
    private val maxConcurrent = maxConcurrent.coerceAtLeast(1)
    private val semaphore = Semaphore(this.maxConcurrent)
    private val acquired = AtomicInteger(0)

    fun tryAcquire(): Boolean {
        val got = semaphore.tryAcquire()
        if (got) acquired.incrementAndGet()
        return got
    }

    fun release() {
        while (true) {
            val current = acquired.get()
            if (current <= 0) return
            if (acquired.compareAndSet(current, current - 1)) {
                semaphore.release()
                return
            }
        }
    }

    val currentCount: Int get() = semaphore.availablePermits()
}
