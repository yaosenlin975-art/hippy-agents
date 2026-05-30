package com.lin.hippyagent.core.skill.store

import com.lin.hippyagent.ui.store.InstallTarget
import org.junit.Assert.*
import org.junit.Test

class InstallQueueTest {

    @Test
    fun `QueueItem Status enum has expected values`() {
        val values = InstallQueue.QueueItem.Status.entries
        assertEquals(5, values.size)
        assertTrue(values.contains(InstallQueue.QueueItem.Status.QUEUED))
        assertTrue(values.contains(InstallQueue.QueueItem.Status.INSTALLING))
        assertTrue(values.contains(InstallQueue.QueueItem.Status.COMPLETED))
        assertTrue(values.contains(InstallQueue.QueueItem.Status.FAILED))
        assertTrue(values.contains(InstallQueue.QueueItem.Status.CANCELLED))
    }

    @Test
    fun `QueueItem data class has expected fields`() {
        val item = InstallQueue.QueueItem(
            id = "test-id",
            skill = StoreSkillItem(
                identifier = "test",
                name = "Test Skill",
                description = "desc",
                author = "tester",
                source = SkillSource.LOBEHUB,
                category = ""
            ),
            target = InstallTarget.Workspace,
            status = InstallQueue.QueueItem.Status.QUEUED
        )
        assertEquals("test-id", item.id)
        assertEquals("Test Skill", item.skill.name)
        assertEquals(InstallTarget.Workspace, item.target)
        assertEquals(InstallQueue.QueueItem.Status.QUEUED, item.status)
        assertNull(item.error)
    }

    @Test
    fun `QueueItem copy updates status`() {
        val item = InstallQueue.QueueItem(
            id = "1",
            skill = StoreSkillItem("t", "T", "", "", SkillSource.LOBEHUB, ""),
            target = InstallTarget.Workspace,
            status = InstallQueue.QueueItem.Status.QUEUED
        )
        val failed = item.copy(status = InstallQueue.QueueItem.Status.FAILED, error = "oops")
        assertEquals(InstallQueue.QueueItem.Status.FAILED, failed.status)
        assertEquals("oops", failed.error)
    }

    @Test
    fun `QueueItem copy preserves other fields`() {
        val item = InstallQueue.QueueItem(
            id = "1",
            skill = StoreSkillItem("t", "My Skill", "", "", SkillSource.CLAWHUB, ""),
            target = InstallTarget.Pool,
            status = InstallQueue.QueueItem.Status.INSTALLING
        )
        val completed = item.copy(status = InstallQueue.QueueItem.Status.COMPLETED)
        assertEquals("1", completed.id)
        assertEquals("My Skill", completed.skill.name)
        assertEquals(InstallTarget.Pool, completed.target)
    }
}
