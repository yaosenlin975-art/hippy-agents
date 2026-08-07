package com.lin.hippyagent.core.agent

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentStreamProcessor.processMessageStream 同一会话 mutex 竞争回归测试（WS-20）。
 *
 * 旧实现 tryLock() 失败时抛 IllegalStateException（"Session ... busy"），
 * 且 finally 块无条件调用 sessionMutex.unlock()——若异常被外层 catch 捕获，
 * 会对从未持有的 mutex 调用 unlock() 抛 "Mutex is not locked" 崩溃。
 *
 * 修复后：tryLock 失败改为挂起排队等待锁释放（不再抛异常），
 * 并用标志位记录是否持锁，finally 仅在实际持锁时 unlock()。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentStreamMutexQueueingTest {

    private fun agentWithMutex(mutex: Mutex): Agent {
        val agent = mockk<Agent>(relaxed = true)
        every { agent.getOrCreateSessionMutex(any()) } returns mutex
        return agent
    }

    @Test
    fun busySession_queuesInsteadOfThrowingIllegalStateException() = runTest {
        val mutex = Mutex()
        val agent = agentWithMutex(mutex)

        // 模拟同一会话已有请求在处理（tryLock 将失败）
        mutex.lock()

        val collectJob = launch {
            // 旧实现：这里会立刻抛 IllegalStateException
            // 新实现：挂起排队等待锁释放
            agent.processMessageStream("s1", "console", "hello").collect { }
        }
        runCurrent()

        assertTrue("busy 会话应排队等待而不是抛异常", collectJob.isActive)
        assertTrue("排队期间 mutex 应仍被占用", mutex.isLocked)

        // 前一个处理完成，释放锁 → 排队中的消息继续执行
        mutex.unlock()
        advanceUntilIdle()

        assertFalse("锁释放后排队任务应完成", collectJob.isActive)
        assertFalse("流程结束后 mutex 应已被 finally 释放", mutex.isLocked)
    }

    @Test
    fun idleSession_completesAndReleasesMutexInFinally() = runTest {
        val mutex = Mutex()
        val agent = agentWithMutex(mutex)

        agent.processMessageStream("s1", "console", "hello").collect { }
        advanceUntilIdle()

        assertFalse("单条消息正常处理后 mutex 应已释放", mutex.isLocked)
    }
}
