package com.lin.hippyagent.core.model

import com.lin.hippyagent.core.agent.config.RunningConfig
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class LlmRateLimiter(config: RunningConfig) {

    private val semaphore = Semaphore(config.llmMaxConcurrent)
    private val maxQpm = config.llmMaxQpm
    private val pauseMs = (config.llmRateLimitPause * 1000).toLong()
    private val jitterMs = (config.llmRateLimitJitter * 1000).toLong()
    private val acquireTimeoutMs = (config.llmAcquireTimeout * 1000).toLong()

    private val minuteWindowMs = 60_000L
    private val requestTimestamps = mutableListOf<Long>()
    private val timestampsLock = Any()

    private var is429Paused = false
    private val pauseUntil = AtomicLong(0)

    suspend fun <T> acquireAndExecute(block: suspend () -> T): T {
        waitIf429Paused()

        if (maxQpm > 0) {
            val acquired = withTimeoutOrNull(acquireTimeoutMs) {
                waitForQpmSlot()
            }
            if (acquired == null) {
                throw LlmRateLimitException("获取 LLM 请求槽位超时 (${acquireTimeoutMs}ms)")
            }
        }

        return semaphore.withPermit {
            recordRequest()
            block()
        }
    }

    fun notify429() {
        val jitter = if (jitterMs > 0) Random.nextLong(0, jitterMs) else 0
        val pauseDuration = pauseMs + jitter
        pauseUntil.set(System.currentTimeMillis() + pauseDuration)
        is429Paused = true
        Timber.w("429 received, pausing LLM requests for ${pauseDuration}ms")
    }

    private suspend fun waitIf429Paused() {
        if (!is429Paused) return
        val remaining = pauseUntil.get() - System.currentTimeMillis()
        if (remaining > 0) {
            Timber.d("Waiting for 429 pause: ${remaining}ms remaining")
            delay(remaining)
        }
        is429Paused = false
    }

    private suspend fun waitForQpmSlot() {
        while (true) {
            synchronized(timestampsLock) {
                val now = System.currentTimeMillis()
                requestTimestamps.removeAll { it < now - minuteWindowMs }
                if (requestTimestamps.size < maxQpm) return
            }
            delay(100)
        }
    }

    private fun recordRequest() {
        synchronized(timestampsLock) {
            val now = System.currentTimeMillis()
            requestTimestamps.removeAll { it < now - minuteWindowMs }
            requestTimestamps.add(now)
        }
    }
}

class LlmRateLimitException(message: String) : Exception(message)

