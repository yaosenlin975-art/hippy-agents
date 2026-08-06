package com.lin.hippyagent.core.cron

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.Operation
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.session.SessionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CronJobManager 快照逻辑（getJobs/getExecutions/getStats/recordExecution/clearHistory）
 * 纯内存聚合测试；WorkManager/Context 使用 mockk 隔离。
 */
class CronJobManagerSnapshotTest {

    private lateinit var tempDir: File
    private lateinit var manager: CronJobManager

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "cron-test-${System.nanoTime()}").apply { mkdirs() }

        mockkStatic(WorkManager::class)
        val workManager = mockk<WorkManager>()
        every { WorkManager.getInstance(any()) } returns workManager
        every { workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) } returns mockk<Operation>()
        every { workManager.cancelUniqueWork(any()) } returns mockk<Operation>()

        val context = mockk<Context>()
        every { context.filesDir } returns tempDir
        every { context.getString(any(), any()) } returns "Cron 会话"

        val agentFactory = mockk<AgentFactory>()
        val sessionStore = mockk<SessionStore>()
        manager = CronJobManager(context, agentFactory, sessionStore)
    }

    @After
    fun tearDown() {
        unmockkAll()
        tempDir.deleteRecursively()
    }

    private fun job(id: String, name: String = "job-$id", schedule: String = "0 9 * * *", enabled: Boolean = true) =
        CronJob(id = id, name = name, query = "query", schedule = schedule, agentId = "agent-1", enabled = enabled)

    private fun execution(
        jobId: String,
        startedAt: Long,
        completedAt: Long? = startedAt + 1000,
        success: Boolean = true,
        error: String? = null
    ) = CronJobExecution(
        jobId = jobId, jobName = "job-$jobId", agentId = "agent-1",
        startedAt = startedAt, completedAt = completedAt, success = success, error = error
    )

    // ═══════════ create / snapshot ═══════════

    @Test
    fun createJob_snapshotReflectsImmediately() = runTest {
        manager.createJob(job("j1"))
        manager.createJob(job("j2", enabled = false))
        assertEquals(2, manager.getJobs().size)
        assertEquals(listOf("j1"), manager.getEnabledJobs().map { it.id })
        assertEquals("j1", manager.getJob("j1")?.id)
        assertNull(manager.getJob("missing"))
    }

    @Test
    fun deleteJob_removesFromSnapshot() = runTest {
        manager.createJob(job("j1"))
        manager.deleteJob("j1")
        assertTrue(manager.getJobs().isEmpty())
        assertNull(manager.getJob("j1"))
    }

    @Test
    fun updateJob_replacesInSnapshot() = runTest {
        manager.createJob(job("j1", schedule = "0 9 * * *"))
        manager.updateJob(job("j1", name = "renamed", schedule = "0 10 * * *"))
        assertEquals("renamed", manager.getJob("j1")?.name)
        assertEquals(1, manager.getJobs().size)
    }

    // ═══════════ executions / stats ═══════════

    @Test
    fun recordExecution_thenStats_aggregates() = runTest {
        manager.createJob(job("j1"))
        manager.recordExecution(execution("j1", startedAt = 1000, completedAt = 4000, success = true))
        manager.recordExecution(execution("j1", startedAt = 5000, completedAt = 8000, success = false, error = "boom"))
        manager.recordExecution(execution("j1", startedAt = 9000, completedAt = 10000, success = true))

        val stats = manager.getStats("j1")
        assertEquals("job-j1", stats.jobName)
        assertEquals(3, stats.totalRuns)
        assertEquals(2, stats.successCount)
        assertEquals(1, stats.failureCount)
        assertEquals(9000L, stats.lastRunAt ?: 0L)
        // (4000-1000 + 8000-5000 + 10000-9000) / 3 = 7000 / 3
        assertEquals(2333L, stats.averageDurationMs)
    }

    @Test
    fun stats_unknownJob_returnsEmptyStats() = runTest {
        val stats = manager.getStats("ghost")
        assertEquals("ghost", stats.jobName)
        assertEquals(0, stats.totalRuns)
        assertEquals(0L, stats.averageDurationMs)
    }

    @Test
    fun getExecutions_sortedDescAndFiltered() = runTest {
        manager.createJob(job("j1"))
        manager.createJob(job("j2"))
        manager.recordExecution(execution("j1", startedAt = 1000))
        manager.recordExecution(execution("j2", startedAt = 2000))
        manager.recordExecution(execution("j1", startedAt = 3000))

        val all = manager.getExecutions()
        assertEquals(3, all.size)
        assertEquals(listOf(3000L, 2000L, 1000L), all.map { it.startedAt })

        val onlyJ1 = manager.getExecutions(jobId = "j1")
        assertEquals(2, onlyJ1.size)
        assertTrue(onlyJ1.all { it.jobId == "j1" })

        val limited = manager.getExecutions(limit = 1)
        assertEquals(1, limited.size)
    }

    @Test
    fun recordExecution_capsHistoryAt500() = runTest {
        manager.createJob(job("j1"))
        repeat(510) { i -> manager.recordExecution(execution("j1", startedAt = i.toLong())) }
        assertEquals(500, manager.getExecutions(limit = 1000).size)
    }

    @Test
    fun getAllStats_coversAllJobs() = runTest {
        manager.createJob(job("j1"))
        manager.createJob(job("j2"))
        manager.recordExecution(execution("j1", startedAt = 1000, success = true))
        assertEquals(2, manager.getAllStats().size)
        assertEquals(1, manager.getStats("j1").successCount)
        assertEquals(0, manager.getStats("j2").totalRuns)
    }

    @Test
    fun clearHistory_removesExecutions() = runTest {
        manager.createJob(job("j1"))
        manager.recordExecution(execution("j1", startedAt = 1000))
        manager.recordExecution(execution("j1", startedAt = 2000))
        manager.clearHistory("j1")
        assertTrue(manager.getExecutions().isEmpty())
        assertEquals(0, manager.getStats("j1").totalRuns)
    }

    // ═══════════ serialization round-trip ═══════════

    @Test
    fun cronJob_serialization_roundTrip() {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val job = job("j1")
        val decoded = json.decodeFromString<CronJob>(json.encodeToString(CronJob.serializer(), job))
        assertEquals(job.id, decoded.id)
        assertEquals(job.schedule, decoded.schedule)
        assertEquals(job.enabled, decoded.enabled)
    }

    @Test
    fun cronJobExecution_serialization_roundTrip() {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val exec = execution("j1", startedAt = 42, completedAt = 142, success = true)
        val decoded = json.decodeFromString<CronJobExecution>(json.encodeToString(CronJobExecution.serializer(), exec))
        assertEquals(42L, decoded.startedAt)
        assertEquals(142L, decoded.completedAt ?: 0L)
        assertTrue(decoded.success)
    }

    @Test
    fun cronJobStats_serialization_roundTrip() {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val stats = CronJobStats(jobId = "j1", jobName = "n", totalRuns = 5, successCount = 3, failureCount = 2, lastRunAt = 100)
        val decoded = json.decodeFromString<CronJobStats>(json.encodeToString(CronJobStats.serializer(), stats))
        assertEquals(5, decoded.totalRuns)
        assertEquals(3, decoded.successCount)
        assertEquals(100L, decoded.lastRunAt ?: 0L)
    }
}
