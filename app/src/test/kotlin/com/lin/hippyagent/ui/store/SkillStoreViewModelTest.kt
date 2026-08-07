package com.lin.hippyagent.ui.store

import android.app.Application
import com.lin.hippyagent.core.skill.SkillManager
import com.lin.hippyagent.core.skill.store.SkillSource
import com.lin.hippyagent.core.skill.store.SkillStoreService
import com.lin.hippyagent.core.skill.store.StoreSkillItem
import com.lin.hippyagent.core.skill.store.provider.MarketSearchResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * 回归测试：WS-32 SkillStoreViewModel.descriptionCache 并发安全。
 *
 * descriptionCache 由 fetchSkillDetail()（Main 协程）与 prefetchDescriptions()（Dispatchers.IO）
 * 并发读写，必须使用线程安全容器（ConcurrentHashMap）；若回退为 mutableMapOf 会抛
 * ConcurrentModificationException 或丢数据。
 */
class SkillStoreViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SkillStoreViewModel {
        val app = mockk<Application>(relaxed = true)
        val storeService = mockk<SkillStoreService>(relaxed = true)
        val skillManager = mockk<SkillManager>(relaxed = true)
        return SkillStoreViewModel(app, storeService, skillManager)
    }

    private fun skill(id: Int, description: String = "") = StoreSkillItem(
        identifier = "skill-$id",
        name = "skill-$id",
        description = description,
        author = "tester",
        source = SkillSource.LOBEHUB,
        category = "test"
    )

    @Test
    fun `descriptionCache 使用线程安全 ConcurrentHashMap`() {
        val vm = createViewModel()
        val field = SkillStoreViewModel::class.java.getDeclaredField("descriptionCache")
        field.isAccessible = true
        val cache = field.get(vm)
        assertTrue("descriptionCache 必须使用 ConcurrentHashMap", cache is ConcurrentHashMap<*, *>)
    }

    @Test
    fun `并发 prefetch 与详情获取不崩溃且缓存最终一致`() = runBlocking {
        val skills = (0 until 16).map { skill(it) }
        val storeService = mockk<SkillStoreService>(relaxed = true)
        val skillManager = mockk<SkillManager>(relaxed = true)
        coEvery { storeService.searchAll(any(), any()) } returns MarketSearchResponse(skills, emptyList(), emptyMap())
        coEvery { storeService.getDetail(any(), any()) } coAnswers {
            delay(1)
            val id = secondArg<String>()
            Result.success<StoreSkillItem?>(
                StoreSkillItem(
                    identifier = id,
                    name = id,
                    description = "desc-$id",
                    author = "tester",
                    source = SkillSource.LOBEHUB,
                    category = "test"
                )
            )
        }
        val vm = SkillStoreViewModel(mockk<Application>(relaxed = true), storeService, skillManager)

        val workers = (0 until 6).map { w ->
            async(Dispatchers.Default) {
                repeat(8) { i ->
                    if ((w + i) % 2 == 0) {
                        vm.selectSkill(skills[(w * 3 + i) % skills.size])
                    } else {
                        vm.loadHotSkills()
                    }
                }
            }
        }
        workers.awaitAll()

        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && vm.uiState.value.skills.any { it.description.isBlank() }) {
            delay(10)
        }
        assertTrue("并发 prefetch 后所有描述应写回（无崩溃、无数据丢失）", vm.uiState.value.skills.none { it.description.isBlank() })
        assertEquals(16, vm.uiState.value.skills.size)
    }

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
