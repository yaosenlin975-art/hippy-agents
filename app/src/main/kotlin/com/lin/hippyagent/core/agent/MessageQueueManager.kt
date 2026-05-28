package com.lin.hippyagent.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class QueuedMessage(
    val content: String,
    val sessionId: String,
    val channelId: String,
    val timestamp: Long = System.currentTimeMillis()
)

class MessageQueueManager {
    private val queue = ConcurrentLinkedQueue<QueuedMessage>()
    private val mutex = Mutex()

    fun isEmpty(): Boolean = queue.isEmpty()

    fun size(): Int = queue.size

    fun enqueue(message: QueuedMessage) {
        queue.add(message)
        Timber.d("Message queued, queue size: ${queue.size}")
    }

    suspend fun flushAll(): List<QueuedMessage> {
        return mutex.withLock {
            val messages = queue.toList()
            queue.clear()
            Timber.d("Flushed ${messages.size} messages from queue")
            messages
        }
    }

    fun combineMessages(messages: List<QueuedMessage>): String {
        return messages.joinToString("\n---\n") { it.content }
    }
}

