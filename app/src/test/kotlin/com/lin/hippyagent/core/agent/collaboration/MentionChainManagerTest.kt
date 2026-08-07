package com.lin.hippyagent.core.agent.collaboration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class MentionChainManagerTest {

    @Suppress("UNCHECKED_CAST")
    private fun processedMapOf(manager: MentionChainManager): ConcurrentHashMap<ProcessedPathKey, String> {
        val field = MentionChainManager::class.java.getDeclaredField("processedMap")
        field.isAccessible = true
        return field.get(manager) as ConcurrentHashMap<ProcessedPathKey, String>
    }

    @Test
    fun cleanupOnlyRemovesProcessedEntriesOfGivenGroup() {
        val manager = MentionChainManager()
        val processedMap = processedMapOf(manager)
        processedMap[ProcessedPathKey("groupA", "msg-1")] = "path-1"
        processedMap[ProcessedPathKey("groupA", "msg-3")] = "path-3"
        processedMap[ProcessedPathKey("groupB", "msg-2")] = "path-2"

        manager.cleanup("groupA")

        assertEquals(1, processedMap.size)
        assertNull(processedMap[ProcessedPathKey("groupA", "msg-1")])
        assertNull(processedMap[ProcessedPathKey("groupA", "msg-3")])
        assertNotNull(processedMap[ProcessedPathKey("groupB", "msg-2")])
    }

    @Test
    fun cleanupOnlyRemovesActivePathsOfGivenGroup() {
        val manager = MentionChainManager()
        val processedMap = processedMapOf(manager)
        val pathA = MentionPath(path = listOf("agent1", "agent2"), pathId = "path-A")
        val pathB = MentionPath(path = listOf("agent3", "agent4"), pathId = "path-B")
        processedMap[ProcessedPathKey("groupA", "msg-1")] = pathA.pathId
        processedMap[ProcessedPathKey("groupB", "msg-2")] = pathB.pathId
        manager.registerPath(pathA)
        manager.registerPath(pathB)

        manager.cleanup("groupA")

        assertNull(manager.getPath("path-A"))
        assertEquals(pathB, manager.getPath("path-B"))
    }

    @Test
    fun cleanupOnlyRemovesCircuitBreakerOfGivenGroup() {
        val manager = MentionChainManager()
        repeat(5) { manager.recordRejection("groupA") }
        repeat(5) { manager.recordRejection("groupB") }
        assertTrue(manager.isCircuitOpen("groupA"))
        assertTrue(manager.isCircuitOpen("groupB"))

        manager.cleanup("groupA")

        assertFalse(manager.isCircuitOpen("groupA"))
        assertTrue(manager.isCircuitOpen("groupB"))
    }
}
