package com.lin.hippyagent.core.skill.store

import android.content.Context
import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.skill.SkillManager
import com.lin.hippyagent.core.skill.store.provider.AvailabilityResult
import com.lin.hippyagent.core.skill.store.provider.MarketProvider
import com.lin.hippyagent.core.skill.store.provider.SearchResult
import com.lin.hippyagent.ui.store.InstallTarget
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class InstallQueueRaceTest {

    private class BlockingProvider : MarketProvider {
        override val key = "lobehub"
        override val label = "LobeHub"
        override val source = SkillSource.LOBEHUB

        val gate = CompletableDeferred<Unit>()
        val installCounts = mutableMapOf<String, AtomicInteger>()

        override fun available(): AvailabilityResult = AvailabilityResult(true, null)

        override suspend fun search(query: String, page: Int, pageSize: Int): Result<SearchResult> =
            Result.success(SearchResult(emptyList(), false))

        override suspend fun install(identifier: String): Result<String> {
            installCounts.getOrPut(identifier) { AtomicInteger() }.incrementAndGet()
            gate.await()
            return Result.failure(IllegalStateException("blocked install"))
        }
    }

    private fun skill(identifier: String, name: String) =
        StoreSkillItem(identifier, name, "", "", SkillSource.LOBEHUB, "")

    private fun TestScope.runTestQueue(): Triple<BlockingProvider, SkillStoreService, InstallQueue> {
        val context = mockk<Context>(relaxed = true)
        val provider = BlockingProvider()
        val storeService = SkillStoreService(mockk<LinuxManager>(relaxed = true), mapOf(provider.key to provider))
        val queue = InstallQueue(
            scope = backgroundScope,
            storeService = storeService,
            context = context,
            skillManager = mockk<SkillManager>(relaxed = true)
        )
        return Triple(provider, storeService, queue)
    }

    @Test
    fun `concurrent runNext claims each queued item exactly once`() = runTest {
        val (provider, _, queue) = runTestQueue()

        queue.enqueue(listOf(skill("skill-a", "Skill A")), InstallTarget.Pool)
        queue.enqueue(listOf(skill("skill-b", "Skill B")), InstallTarget.Pool)
        advanceUntilIdle()

        // 并发的 runNext() 不能让同一技能被安装两次
        assertEquals(1, provider.installCounts["skill-a"]?.get() ?: 0)

        provider.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, provider.installCounts["skill-a"]?.get() ?: 0)
        assertEquals(1, provider.installCounts["skill-b"]?.get() ?: 0)
        assertEquals(2, queue.items.value.size)
        assertEquals(2, queue.items.value.count { it.status == InstallQueue.QueueItem.Status.FAILED })
    }

    @Test
    fun `runNext during active install defers the next item`() = runTest {
        val (provider, _, queue) = runTestQueue()

        queue.enqueue(listOf(skill("skill-a", "Skill A")), InstallTarget.Pool)
        queue.enqueue(listOf(skill("skill-b", "Skill B")), InstallTarget.Pool)
        advanceUntilIdle()

        // 第一个安装进行中时，第二个技能保持 QUEUED，不会被并发启动
        val items = queue.items.value
        assertEquals(1, items.count { it.status == InstallQueue.QueueItem.Status.INSTALLING })
        assertEquals(1, items.count { it.status == InstallQueue.QueueItem.Status.QUEUED })
        assertEquals(1, provider.installCounts["skill-a"]?.get() ?: 0)
        assertEquals(0, provider.installCounts["skill-b"]?.get() ?: 0)

        provider.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, provider.installCounts["skill-a"]?.get() ?: 0)
        assertEquals(1, provider.installCounts["skill-b"]?.get() ?: 0)
    }
}
