package com.lin.hippyagent.core.agent.session

import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 回归测试：WS-21 会话生命周期终结（close/fail）与 token 更新必须可重复调用。
 *
 * SessionStatsEntity.sessionId 是主键，若 closeSession/failSession 使用普通 insert 写入
 * stats 行，第二次终结同一会话会因主键冲突抛 SQLiteConstraintException。当前实现通过
 * SessionStatsDao.upsert (@Upsert, INSERT ... ON CONFLICT DO UPDATE) 保证重复调用安全。
 */
class RoomSessionStoreStatsUpsertTest {

    private lateinit var sessionDao: SessionDao
    private lateinit var sessionStatsDao: SessionStatsDao
    private lateinit var store: RoomSessionStore

    @Before
    fun setUp() {
        sessionDao = mockk(relaxed = true)
        sessionStatsDao = mockk(relaxed = true)
        val sessionCompressionDao = mockk<SessionCompressionDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        val database = mockk<AppDatabase>(relaxed = true)
        every { database.transactionExecutor } returns Executor { it.run() }
        every { database.beginTransaction() } just runs
        every { database.setTransactionSuccessful() } just runs
        every { database.endTransaction() } just runs
        store = RoomSessionStore(
            sessionDao = sessionDao,
            sessionStatsDao = sessionStatsDao,
            sessionCompressionDao = sessionCompressionDao,
            messageDao = messageDao,
            database = database
        )
    }

    @Test
    fun closeSession_calledTwice_doesNotFail() = runTest {
        val first = store.closeSession("s1", "gpt-4o", 10, 20, 5, 5, 0.001)
        val second = store.closeSession("s1", "gpt-4o", 30, 60, 10, 10, 0.002)

        assertTrue("第一次 closeSession 应成功", first.isSuccess)
        assertTrue("第二次 closeSession 不应因 stats 主键冲突失败", second.isSuccess)
        coVerify(exactly = 2) { sessionStatsDao.upsert(SessionStatsEntity(sessionId = "s1")) }
        coVerify(exactly = 2) { sessionStatsDao.updateTokenUsage("s1", any(), any(), any(), any(), any()) }
        coVerify(exactly = 2) { sessionStatsDao.updateFinishedAt("s1", any()) }
        coVerify(exactly = 2) { sessionDao.updateStatus("s1", "completed") }
    }

    @Test
    fun failSession_afterCloseSession_doesNotFail() = runTest {
        val closed = store.closeSession("s1", "gpt-4o", 10, 20, 5, 5, 0.001)
        val failed = store.failSession("s1", "gpt-4o", 10, 20, 5, 5, 0.001, "timeout")

        assertTrue("closeSession 应成功", closed.isSuccess)
        assertTrue("同一会话再次 failSession 不应失败", failed.isSuccess)
        coVerify(exactly = 2) { sessionStatsDao.upsert(SessionStatsEntity(sessionId = "s1")) }
        coVerify { sessionDao.updateStatus("s1", "completed") }
        coVerify { sessionDao.updateStatus("s1", "failed") }
    }

    @Test
    fun updateSessionTokenUsage_calledRepeatedly_doesNotFail() = runTest {
        val first = store.updateSessionTokenUsage("s1", 10, 20, 5, 5, 0.001)
        val second = store.updateSessionTokenUsage("s1", 30, 60, 10, 10, 0.002)

        assertTrue("第一次 updateSessionTokenUsage 应成功", first.isSuccess)
        assertTrue("重复 updateSessionTokenUsage 不应因 stats 主键冲突失败", second.isSuccess)
        coVerify(exactly = 2) { sessionStatsDao.upsert(SessionStatsEntity(sessionId = "s1")) }
    }

    @Test
    fun createSession_thenCloseSession_completesLifecycle() = runTest {
        val created = store.createSession("agent-1", "会话 A")
        assertTrue("createSession 应成功", created.isSuccess)

        val closed = store.closeSession("s1", "gpt-4o", 10, 20, 5, 5, 0.001)
        assertTrue("closeSession 应成功", closed.isSuccess)
        coVerify { sessionStatsDao.upsert(SessionStatsEntity(sessionId = "s1")) }
    }
}
