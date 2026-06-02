package com.lin.hippyagent.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

data class QueuedMessage(
    val id: Long = 0L,
    val content: String,
    val sessionId: String,
    val channelId: String,
    val timestamp: Long = System.currentTimeMillis()
)

class MessageQueueManager {
    private val queue = mutableListOf<QueuedMessage>()
    private val mutex = Mutex()
    private val idCounter = AtomicLong(0)
    private var snapshotVersion = 0
    private var lastSnapshotVersion = -1
    private var cachedSnapshot: List<QueuedMessage> = emptyList()

    private val _queueItems = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val queueItems: StateFlow<List<QueuedMessage>> = _queueItems.asStateFlow()

    private fun publishSnapshot() {
        if (_queueItems.subscriptionCount.value > 0) {
            if (lastSnapshotVersion != snapshotVersion) {
                cachedSnapshot = queue.toList()
                lastSnapshotVersion = snapshotVersion
                _queueItems.value = cachedSnapshot
            }
        }
    }

    private fun invalidateSnapshot() {
        snapshotVersion++
    }

    fun isEmpty(): Boolean = queue.isEmpty()

    fun size(): Int = queue.size

    fun enqueue(message: QueuedMessage): QueuedMessage {
        val withId = message.copy(id = idCounter.incrementAndGet())
        synchronized(queue) {
            queue.add(withId)
            invalidateSnapshot()
            publishSnapshot()
        }
        Timber.d("Message queued, queue size: ${queue.size}")
        return withId
    }

    suspend fun flushAll(): List<QueuedMessage> = mutex.withLock {
        synchronized(queue) {
            val messages = queue.toList()
            queue.clear()
            invalidateSnapshot()
            cachedSnapshot = emptyList()
            lastSnapshotVersion = snapshotVersion
            _queueItems.value = emptyList()
            Timber.d("Flushed ${messages.size} messages from queue")
            messages
        }
    }

    suspend fun removeAt(index: Int) = mutex.withLock {
        synchronized(queue) {
            if (index < 0 || index >= queue.size) return@withLock
            queue.removeAt(index)
            invalidateSnapshot()
            publishSnapshot()
        }
    }

    suspend fun move(fromIndex: Int, toIndex: Int) = mutex.withLock {
        synchronized(queue) {
            if (fromIndex < 0 || fromIndex >= queue.size) return@withLock
            if (toIndex < 0 || toIndex >= queue.size) return@withLock
            if (fromIndex == toIndex) return@withLock
            val item = queue.removeAt(fromIndex)
            queue.add(toIndex.coerceIn(0, queue.size), item)
            invalidateSnapshot()
            publishSnapshot()
        }
    }

    fun combineMessages(messages: List<QueuedMessage>): String {
        return messages.joinToString("\n---\n") { it.content }
    }
}
