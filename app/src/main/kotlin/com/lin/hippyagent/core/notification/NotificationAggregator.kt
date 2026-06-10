package com.lin.hippyagent.core.notification

import java.util.concurrent.ConcurrentHashMap

class NotificationAggregator(
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {
    private val lastSeen = ConcurrentHashMap<String, Long>()

    suspend fun checkDuplicate(aggregateKey: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastSeen.put(aggregateKey, now)
        return previous == null || (now - previous) > windowMs
    }

    fun reset() {
        lastSeen.clear()
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 60_000L
    }
}
