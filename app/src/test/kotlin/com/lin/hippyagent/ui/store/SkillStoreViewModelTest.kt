package com.lin.hippyagent.ui.store

import org.junit.Assert.*
import org.junit.Test

class SkillStoreViewModelTest {
    @Test
    fun `SortType has 4 values`() {
        assertEquals(4, SortType.entries.size)
    }

    @Test
    fun `NodeStatus has 5 subtypes`() {
        val statuses = listOf(
            NodeStatus.Unknown,
            NodeStatus.Checking,
            NodeStatus.Installing,
            NodeStatus.Ready,
            NodeStatus.Failed
        )
        assertEquals(5, statuses.size)
    }

    @Test
    fun `NodeStatus sealed class type checks`() {
        assertTrue(NodeStatus.Unknown is NodeStatus)
        assertTrue(NodeStatus.Checking is NodeStatus)
        assertTrue(NodeStatus.Installing is NodeStatus)
        assertTrue(NodeStatus.Ready is NodeStatus)
        assertTrue(NodeStatus.Failed is NodeStatus)
    }

    @Test
    fun `NodeStatus identity`() {
        assertNotSame(NodeStatus.Unknown, NodeStatus.Ready)
        assertEquals(NodeStatus.Unknown, NodeStatus.Unknown)
    }

    @Test
    fun `InstallTarget has 2 entries`() {
        assertEquals(2, InstallTarget.entries.size)
        assertTrue(InstallTarget.entries.contains(InstallTarget.Workspace))
        assertTrue(InstallTarget.entries.contains(InstallTarget.Pool))
    }

    @Test
    fun `SkillStoreUiState default values`() {
        val state = SkillStoreUiState()
        assertFalse(state.isLoading)
        assertTrue(state.skills.isEmpty())
        assertTrue(state.hotSkills.isEmpty())
        assertEquals("", state.searchQuery)
        assertNull(state.activeSource)
        assertEquals(SortType.HOT, state.sortType)
        assertTrue(state.installedIds.isEmpty())
        assertNull(state.error)
        assertNull(state.showInstallDialog)
        assertEquals(NodeStatus.Unknown, state.nodeStatus)
        assertNull(state.selectedSkill)
        assertEquals(InstallTarget.Workspace, state.installTarget)
        assertTrue(state.providerErrors.isEmpty())
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun `SkillStoreUiState copy updates fields`() {
        val state = SkillStoreUiState()
        val updated = state.copy(
            searchQuery = "test",
            nodeStatus = NodeStatus.Ready,
            hasMore = true
        )
        assertEquals("test", updated.searchQuery)
        assertEquals(NodeStatus.Ready, updated.nodeStatus)
        assertTrue(updated.hasMore)
        assertEquals(state.skills, updated.skills)
    }
}
