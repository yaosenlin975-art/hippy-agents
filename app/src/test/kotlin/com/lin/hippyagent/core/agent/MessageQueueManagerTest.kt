package com.lin.hippyagent.core.agent

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageQueueManagerTest {

    private fun message(content: String, sessionId: String = "s1") =
        QueuedMessage(content = content, sessionId = sessionId, channelId = "chat")

    // ═══════════ basic queue ops ═══════════

    @Test
    fun enqueue_assignsIncrementingIds() {
        val queue = MessageQueueManager()
        val m1 = queue.enqueue(message("a"))
        val m2 = queue.enqueue(message("b"))
        assertEquals(1L, m1.id)
        assertEquals(2L, m2.id)
        assertEquals(2, queue.size())
        assertFalse(queue.isEmpty())
    }

    @Test
    fun newQueue_isEmpty() {
        val queue = MessageQueueManager()
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size())
    }

    @Test
    fun flushAll_returnsAllAndEmptiesQueue() = runTest {
        val queue = MessageQueueManager()
        queue.enqueue(message("a"))
        queue.enqueue(message("b"))
        val flushed = queue.flushAll()
        assertEquals(2, flushed.size)
        assertEquals(listOf("a", "b"), flushed.map { it.content })
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size())
    }

    @Test
    fun removeAt_outOfBounds_isNoOp() = runTest {
        val queue = MessageQueueManager()
        queue.enqueue(message("a"))
        queue.removeAt(5)
        queue.removeAt(-1)
        assertEquals(1, queue.size())
    }

    @Test
    fun removeAt_validIndex_removesMessage() = runTest {
        val queue = MessageQueueManager()
        queue.enqueue(message("a"))
        queue.enqueue(message("b"))
        queue.removeAt(0)
        assertEquals(1, queue.size())
        assertEquals("b", queue.flushAll().first().content)
    }

    @Test
    fun move_reordersQueue() = runTest {
        val queue = MessageQueueManager()
        queue.enqueue(message("a"))
        queue.enqueue(message("b"))
        queue.enqueue(message("c"))
        queue.move(0, 2)
        assertEquals(listOf("b", "c", "a"), queue.flushAll().map { it.content })
    }

    @Test
    fun move_outOfBounds_isNoOp() = runTest {
        val queue = MessageQueueManager()
        queue.enqueue(message("a"))
        queue.move(0, 3)
        queue.move(3, 0)
        assertEquals(1, queue.size())
    }

    @Test
    fun combineMessages_joinsWithSeparator() {
        val queue = MessageQueueManager()
        val combined = queue.combineMessages(listOf(message("a"), message("b")))
        assertEquals("a\n---\nb", combined)
    }

    // ═══════════ StateFlow snapshot ═══════════

    @Test
    fun queueItems_flow_publishesEnqueuedMessages() = runTest {
        val queue = MessageQueueManager()
        val collector = launch { queue.queueItems.collect { } }
        runCurrent()
        queue.enqueue(message("x"))
        assertEquals(listOf("x"), queue.queueItems.value.map { it.content })
        collector.cancel()
    }

    @Test
    fun queueItems_flow_updatesOnFlush() = runTest {
        val queue = MessageQueueManager()
        val collector = launch { queue.queueItems.collect { } }
        runCurrent()
        queue.enqueue(message("x"))
        queue.flushAll()
        assertTrue(queue.queueItems.value.isEmpty())
        collector.cancel()
    }
}
