package com.lin.hippyagent.core.agent.task

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TaskApprovalServiceTest {

    private fun taskWithNode(
        taskId: String = "task-1",
        nodeId: String = "node-1",
        taskStatus: TaskStatus = TaskStatus.AWAITING_APPROVAL,
        nodeStatus: ApprovalStatus = ApprovalStatus.PENDING
    ) = TaskEntity(
        id = taskId,
        title = "测试任务",
        agentId = "agent-1",
        status = taskStatus,
        steps = listOf(TaskStep(id = "step-1", description = "step", requiresApproval = true)),
        executionContext = ExecutionContext(snapshot = "snap"),
        approvalNodes = listOf(ApprovalNode(id = nodeId, stepId = "step-1", prompt = "确认?", status = nodeStatus))
    )

    @Test
    fun approve_pendingNode_transitionsToRunning() = runTest {
        val dao = mockk<TaskDao>()
        val audit = mockk<AuditLogger>()
        val task = taskWithNode()

        coEvery { dao.findByApprovalNodeId(any()) } returns task
        coEvery { dao.update(any()) } returns Unit
        coEvery { audit.logToolCall(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit

        val svc = TaskApprovalService(dao, audit, null, this)
        svc.register(task, task.approvalNodes.first())
        svc.approve("node-1")

        coVerify(exactly = 1) { dao.update(any()) }
        val captured = io.mockk.slot<TaskEntity>()
        coVerify { dao.update(capture(captured)) }
        val updated = captured.captured
        assertEquals(TaskStatus.RUNNING, updated.status)
        assertEquals(ApprovalStatus.APPROVED, updated.approvalNodes.first().status)
        assertEquals("user", updated.approvalNodes.first().decidedBy)
        coVerify(exactly = 1) { audit.logToolCall(any(), any(), any(), any(), any(), true, any(), any(), any()) }
    }

    @Test
    fun reject_pendingNode_transitionsToFailed() = runTest {
        val dao = mockk<TaskDao>()
        val audit = mockk<AuditLogger>()
        val task = taskWithNode()

        coEvery { dao.findByApprovalNodeId(any()) } returns task
        coEvery { dao.update(any()) } returns Unit
        coEvery { audit.logToolCall(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit

        val svc = TaskApprovalService(dao, audit, null, this)
        svc.register(task, task.approvalNodes.first())
        svc.reject("node-1", reason = "不同意")

        val captured = io.mockk.slot<TaskEntity>()
        coVerify { dao.update(capture(captured)) }
        val updated = captured.captured
        assertEquals(TaskStatus.FAILED, updated.status)
        assertEquals(ApprovalStatus.REJECTED, updated.approvalNodes.first().status)
        assertEquals("不同意", updated.approvalNodes.first().decisionReason)
    }

    @Test
    fun modify_pendingNode_transitionsToRunning() = runTest {
        val dao = mockk<TaskDao>()
        val audit = mockk<AuditLogger>()
        val task = taskWithNode()

        coEvery { dao.findByApprovalNodeId(any()) } returns task
        coEvery { dao.update(any()) } returns Unit
        coEvery { audit.logToolCall(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit

        val svc = TaskApprovalService(dao, audit, null, this)
        svc.register(task, task.approvalNodes.first())
        svc.modify("node-1", reason = "改一下")

        val captured = io.mockk.slot<TaskEntity>()
        coVerify { dao.update(capture(captured)) }
        assertEquals(TaskStatus.RUNNING, captured.captured.status)
        assertEquals(ApprovalStatus.MODIFIED, captured.captured.approvalNodes.first().status)
    }

    @Test
    fun decide_onAlreadyDecidedNode_isNoOp() = runTest {
        val dao = mockk<TaskDao>()
        val audit = mockk<AuditLogger>()
        val task = taskWithNode(nodeStatus = ApprovalStatus.APPROVED)

        coEvery { dao.findByApprovalNodeId(any()) } returns task
        coEvery { dao.update(any()) } returns Unit

        val svc = TaskApprovalService(dao, audit, null, this)
        svc.approve("node-1")

        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun decide_onTaskNotAwaitingApproval_isNoOp() = runTest {
        val dao = mockk<TaskDao>()
        val audit = mockk<AuditLogger>()
        val task = taskWithNode(taskStatus = TaskStatus.RUNNING)

        coEvery { dao.findByApprovalNodeId(any()) } returns task
        coEvery { dao.update(any()) } returns Unit

        val svc = TaskApprovalService(dao, audit, null, this)
        svc.approve("node-1")

        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun decide_taskNotFound_isNoOp() = runTest {
        val dao = mockk<TaskDao>()
        val audit = mockk<AuditLogger>()
        coEvery { dao.findByApprovalNodeId(any()) } returns null
        coEvery { dao.update(any()) } returns Unit

        val svc = TaskApprovalService(dao, audit, null, this)
        svc.approve("node-ghost")

        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun timeout_afterTimeoutSec_marksTimeoutAndFailed() = runTest {
        val dao = mockk<TaskDao>()
        val audit = mockk<AuditLogger>()
        val task = taskWithNode()

        coEvery { dao.findByApprovalNodeId(any()) } returns task
        coEvery { dao.getById(any()) } returns task
        coEvery { dao.update(any()) } returns Unit
        coEvery { audit.logToolCall(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit

        val svc = TaskApprovalService(dao, audit, null, this)
        svc.register(task, task.approvalNodes.first())
        runCurrent()

        advanceTimeBy(300_001)
        runCurrent()

        val captured = io.mockk.slot<TaskEntity>()
        coVerify { dao.update(capture(captured)) }
        val updated = captured.captured
        assertEquals(TaskStatus.FAILED, updated.status)
        assertEquals(ApprovalStatus.TIMEOUT, updated.approvalNodes.first().status)
        assertEquals("system", updated.approvalNodes.first().decidedBy)
    }

    @Test
    fun register_returnsTask() = runTest {
        val dao = mockk<TaskDao>()
        val audit = mockk<AuditLogger>()
        val task = taskWithNode()

        val svc = TaskApprovalService(dao, audit, null, this)
        val returned = svc.register(task, task.approvalNodes.first())
        assertNotNull(returned)
        assertEquals(task.id, returned.id)
    }

    @Test
    fun timeout_alreadyDecidedNode_noDoubleUpdate() = runTest {
        val dao = mockk<TaskDao>()
        val audit = mockk<AuditLogger>()
        val task = taskWithNode()

        coEvery { dao.findByApprovalNodeId(any()) } returns task
        coEvery { dao.getById(any()) } returns task
        coEvery { dao.update(any()) } returns Unit
        coEvery { audit.logToolCall(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit

        val svc = TaskApprovalService(dao, audit, null, this)
        svc.register(task, task.approvalNodes.first())
        svc.approve("node-1")

        advanceTimeBy(300_001)
        runCurrent()

        // 已 APPROVED → 超时不覆盖
        coVerify(exactly = 1) { dao.update(any()) }
    }
}
