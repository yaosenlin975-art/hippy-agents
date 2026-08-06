package com.lin.hippyagent.core.security

import com.lin.hippyagent.core.agent.task.ApprovalNode
import com.lin.hippyagent.core.agent.task.ExecutionContext
import com.lin.hippyagent.core.agent.task.TaskApprovalService
import com.lin.hippyagent.core.agent.task.TaskDao
import com.lin.hippyagent.core.agent.task.TaskEntity
import com.lin.hippyagent.core.agent.task.TaskStatus
import com.lin.hippyagent.core.agent.task.TaskStep
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalManagerTest {

    private class Fixture {
        val taskDao = mockk<TaskDao>()
        val ruleDao = mockk<ToolApprovalRuleDao>()
        val service = mockk<TaskApprovalService>()
        val manager = ToolApprovalManager(service, taskDao, ruleDao)

        init {
            coEvery { taskDao.insert(any()) } returns Unit
            coEvery { service.register(any(), any()) } answers { arg<TaskEntity>(0) }
            coEvery { ruleDao.getByKey(any()) } returns null
        }
    }

    // ═══════════ rule keys ═══════════

    @Test
    fun ruleKey_deterministicAndSorted() {
        val f = Fixture()
        val key1 = f.manager.ruleKey("read_file", mapOf("b" to "2", "a" to "1"))
        val key2 = f.manager.ruleKey("read_file", mapOf("a" to "1", "b" to "2"))
        assertEquals(key1, key2)
        assertTrue(key1.startsWith("read_file|"))
        assertTrue(key1.contains("a=1"))
        assertTrue(key1.contains("b=2"))
    }

    @Test
    fun ruleKey_excludesCallId() {
        val f = Fixture()
        val withCallId = f.manager.ruleKey("read_file", mapOf("path" to "/a", "callId" to "xyz"))
        val withoutCallId = f.manager.ruleKey("read_file", mapOf("path" to "/a"))
        assertEquals(withCallId, withoutCallId)
    }

    @Test
    fun ruleKey_truncatesLongValues() {
        val f = Fixture()
        val longValue = "v".repeat(100)
        val key = f.manager.ruleKey("read_file", mapOf("path" to longValue))
        assertTrue(key.contains("v".repeat(50)))
        assertFalse(key.contains("v".repeat(51)))
    }

    @Test
    fun ruleKeyForTool_usesWildcard() {
        val f = Fixture()
        assertEquals("read_file|*", f.manager.ruleKeyForTool("read_file"))
    }

    // ═══════════ checkRule ═══════════

    @Test
    fun checkRule_exactMatch_returnsAction() = runTest {
        val f = Fixture()
        coEvery { f.ruleDao.getByKey("read_file|path=/a") } returns
            ToolApprovalRule("read_file|path=/a", "ALLOW_ALWAYS", "read_file", "hash")
        val action = f.manager.checkRule("read_file", mapOf("path" to "/a"))
        assertEquals(ApprovalAction.ALLOW_ALWAYS, action)
    }

    @Test
    fun checkRule_toolLevelMatch_returnsAction() = runTest {
        val f = Fixture()
        coEvery { f.ruleDao.getByKey("read_file|*") } returns
            ToolApprovalRule("read_file|*", "DENY_ALWAYS", "read_file", "*")
        val action = f.manager.checkRule("read_file", mapOf("path" to "/b"))
        assertEquals(ApprovalAction.DENY_ALWAYS, action)
    }

    @Test
    fun checkRule_onceActions_ignored() = runTest {
        val f = Fixture()
        coEvery { f.ruleDao.getByKey("read_file|path=/a") } returns
            ToolApprovalRule("read_file|path=/a", "ALLOW_ONCE", "read_file", "hash")
        assertNull(f.manager.checkRule("read_file", mapOf("path" to "/a")))
    }

    @Test
    fun checkRule_noRule_returnsNull() = runTest {
        val f = Fixture()
        assertNull(f.manager.checkRule("read_file", mapOf("path" to "/a")))
    }

    // ═══════════ requestApproval ═══════════

    @Test
    fun requestApproval_existingAllowAlways_returnsImmediately() = runTest {
        val f = Fixture()
        coEvery { f.ruleDao.getByKey(any()) } returns
            ToolApprovalRule("read_file|*", "ALLOW_ALWAYS", "read_file", "*")
        val action = f.manager.requestApproval("read_file", mapOf("path" to "/a"), RiskLevel.HIGH, emptyList(), null, "agent-1")
        assertEquals(ApprovalAction.ALLOW_ALWAYS, action)
        coVerify(exactly = 0) { f.taskDao.insert(any()) }
    }

    @Test
    fun requestApproval_existingDenyAlways_returnsImmediately() = runTest {
        val f = Fixture()
        coEvery { f.ruleDao.getByKey(any()) } returns
            ToolApprovalRule("read_file|*", "DENY_ALWAYS", "read_file", "*")
        val action = f.manager.requestApproval("read_file", mapOf("path" to "/a"), RiskLevel.HIGH, emptyList(), null, "agent-1")
        assertEquals(ApprovalAction.DENY_ALWAYS, action)
    }

    @Test
    fun requestApproval_createsTaskAndRegisters_thenReturnsResolvedAction() = runTest {
        val f = Fixture()
        val deferred = async {
            f.manager.requestApproval(
                "read_file", mapOf("path" to "/a"), RiskLevel.HIGH,
                emptyList(), "s1", "agent-1"
            )
        }
        runCurrent()
        val pending = f.manager.pendingApprovals.value.first()
        assertEquals("read_file", pending.toolName)
        assertEquals(RiskLevel.HIGH, pending.riskLevel)
        coVerify { f.taskDao.insert(any()) }
        coVerify { f.service.register(any(), any()) }

        f.manager.resolveApproval(pending.requestId, ApprovalAction.ALLOW_ONCE)
        val result = deferred.await()
        assertEquals(ApprovalAction.ALLOW_ONCE, result)
        assertTrue(f.manager.pendingApprovals.value.isEmpty())
    }

    @Test
    fun requestApproval_timeout_returnsDenyOnce() = runTest {
        val f = Fixture()
        val deferred = async {
            f.manager.requestApproval("read_file", mapOf("path" to "/a"), RiskLevel.MEDIUM, emptyList(), null, "agent-1")
        }
        runCurrent()
        advanceTimeBy(311_000)
        val result = deferred.await()
        assertEquals(ApprovalAction.DENY_ONCE, result)
        assertTrue(f.manager.pendingApprovals.value.isEmpty())
    }

    // ═══════════ resolveApproval ═══════════

    @Test
    fun resolveApproval_allowOnce_callsApprove() = runTest {
        val f = Fixture()
        coEvery { f.service.approve(any()) } returns Unit
        val deferred = async { f.manager.requestApproval("read_file", emptyMap(), RiskLevel.HIGH, emptyList(), null, "a1") }
        runCurrent()
        f.manager.resolveApproval(f.manager.pendingApprovals.value.first().requestId, ApprovalAction.ALLOW_ONCE)
        deferred.await()
        coVerify(exactly = 1) { f.service.approve(any()) }
        coVerify(exactly = 0) { f.ruleDao.insert(any()) }
    }

    @Test
    fun resolveApproval_allowAlways_savesRules() = runTest {
        val f = Fixture()
        coEvery { f.service.approve(any()) } returns Unit
        coEvery { f.ruleDao.insert(any()) } returns Unit
        val deferred = async { f.manager.requestApproval("read_file", mapOf("path" to "/a"), RiskLevel.HIGH, emptyList(), null, "a1") }
        runCurrent()
        f.manager.resolveApproval(f.manager.pendingApprovals.value.first().requestId, ApprovalAction.ALLOW_ALWAYS)
        deferred.await()
        coVerify(exactly = 2) { f.ruleDao.insert(any()) }
        coVerify(exactly = 1) { f.service.approve(any()) }
    }

    @Test
    fun resolveApproval_denyOnce_callsReject() = runTest {
        val f = Fixture()
        coEvery { f.service.reject(any()) } returns Unit
        val deferred = async { f.manager.requestApproval("read_file", emptyMap(), RiskLevel.HIGH, emptyList(), null, "a1") }
        runCurrent()
        f.manager.resolveApproval(f.manager.pendingApprovals.value.first().requestId, ApprovalAction.DENY_ONCE)
        deferred.await()
        coVerify(exactly = 1) { f.service.reject(any()) }
        coVerify(exactly = 0) { f.service.approve(any()) }
    }

    @Test
    fun resolveApproval_idempotent_singleProcessing() = runTest {
        val f = Fixture()
        coEvery { f.service.approve(any()) } returns Unit
        val deferred = async { f.manager.requestApproval("read_file", emptyMap(), RiskLevel.HIGH, emptyList(), null, "a1") }
        runCurrent()
        val id = f.manager.pendingApprovals.value.first().requestId
        f.manager.resolveApproval(id, ApprovalAction.ALLOW_ONCE)
        f.manager.resolveApproval(id, ApprovalAction.ALLOW_ONCE)
        f.manager.resolveApproval(id, ApprovalAction.DENY_ONCE)
        deferred.await()
        coVerify(exactly = 1) { f.service.approve(any()) }
        coVerify(exactly = 0) { f.service.reject(any()) }
    }

    // ═══════════ rules management ═══════════

    @Test
    fun getAllRules_filtersAlwaysAndSortsDesc() = runTest {
        val f = Fixture()
        coEvery { f.ruleDao.getAll() } returns listOf(
            ToolApprovalRule("k1", "ALLOW_ALWAYS", "t1", "*", createdAt = 100),
            ToolApprovalRule("k2", "DENY_ALWAYS", "t2", "*", createdAt = 300),
            ToolApprovalRule("k3", "ALLOW_ONCE", "t3", "*", createdAt = 200)
        )
        val rules = f.manager.getAllRules()
        assertEquals(2, rules.size)
        assertEquals(listOf("k2", "k1"), rules.map { it.key })
    }

    @Test
    fun removeAndClearRules_delegateToDao() = runTest {
        val f = Fixture()
        coEvery { f.ruleDao.deleteByKey(any()) } returns Unit
        coEvery { f.ruleDao.clearAll() } returns Unit
        f.manager.removeRule("k1")
        f.manager.clearAllRules()
        coVerify { f.ruleDao.deleteByKey("k1") }
        coVerify { f.ruleDao.clearAll() }
    }
}
