package com.lin.hippyagent.core.agent.collaboration

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class QueuedMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val content: String,
    val mentions: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val isUrgent: Boolean = false,
    var isProcessed: Boolean = false,
    val senderIsUser: Boolean = false
)

class AgentMessageQueue(
    val agentId: String,
    private val maxSize: Int = 100
) {
    private val urgentQueue = ConcurrentLinkedQueue<QueuedMessage>()
    private val normalQueue = ConcurrentLinkedQueue<QueuedMessage>()
    private val isPaused = AtomicBoolean(false)
    private val totalSize = AtomicInteger(0)

    val pendingCount: Int get() = totalSize.get()

    val isEmpty: Boolean get() = totalSize.get() == 0

    val isNotEmpty: Boolean get() = totalSize.get() > 0

    fun enqueue(message: QueuedMessage): Boolean {
        if (totalSize.get() >= maxSize) {
            return false
        }
        totalSize.incrementAndGet()
        if (message.isUrgent) {
            urgentQueue.offer(message)
        } else {
            normalQueue.offer(message)
        }
        return true
    }

    fun dequeue(): QueuedMessage? {
        if (isPaused.get()) return null

        val urgent = urgentQueue.poll()
        if (urgent != null) {
            urgent.isProcessed = true
            totalSize.decrementAndGet()
            return urgent
        }

        val normal = normalQueue.poll()
        if (normal != null) {
            normal.isProcessed = true
            totalSize.decrementAndGet()
        }
        return normal
    }

    fun peek(): QueuedMessage? {
        return urgentQueue.peek() ?: normalQueue.peek()
    }

    fun peekAll(): List<QueuedMessage> {
        val result = mutableListOf<QueuedMessage>()
        urgentQueue.forEach { if (!it.isProcessed) result.add(it) }
        normalQueue.forEach { if (!it.isProcessed) result.add(it) }
        return result
    }

    fun clear() {
        urgentQueue.clear()
        normalQueue.clear()
        totalSize.set(0)
    }

    fun insertUrgent(message: QueuedMessage) {
        val urgentMessage = QueuedMessage(
            id = UUID.randomUUID().toString(),
            senderId = message.senderId,
            content = message.content,
            mentions = message.mentions,
            timestamp = message.timestamp,
            isUrgent = true,
            isProcessed = false,
            senderIsUser = message.senderIsUser
        )
        totalSize.incrementAndGet()
        urgentQueue.offer(urgentMessage)
    }

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
    }

    fun isPaused(): Boolean = isPaused.get()

    fun remove(messageId: String): Boolean {
        val removedFromUrgent = urgentQueue.removeIf { it.id == messageId }
        val removedFromNormal = normalQueue.removeIf { it.id == messageId }
        val removed = removedFromUrgent || removedFromNormal
        if (removed) totalSize.decrementAndGet()
        return removed
    }

    fun markProcessed(messageId: String) {
        urgentQueue.forEach { if (it.id == messageId) it.isProcessed = true }
        normalQueue.forEach { if (it.id == messageId) it.isProcessed = true }
    }

    fun getUrgentMessages(): List<QueuedMessage> {
        return urgentQueue.filter { !it.isProcessed }
    }

    fun getUnprocessedCount(): Int {
        var count = 0
        urgentQueue.forEach { if (!it.isProcessed) count++ }
        normalQueue.forEach { if (!it.isProcessed) count++ }
        return count
    }
}
