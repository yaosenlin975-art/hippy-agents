package com.lin.hippyagent.core.memory

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 回归测试：WS-22 LocalMemoryStore 并发安全。
 *
 * entries 容器必须是线程安全集合（ConcurrentHashMap）：addEntry / deleteEntry /
 * updateEntry / search / getAll 均通过 withContext(Dispatchers.IO) 在多个线程上执行，
 * 若使用普通 HashMap，并发读写会抛 ConcurrentModificationException 或丢数据。
 */
class LocalMemoryStoreTest {

    private lateinit var memoryDir: File
    private lateinit var store: LocalMemoryStore

    @Before
    fun setUp() {
        memoryDir = Files.createTempDirectory("local-memory-store-test").toFile()
        store = LocalMemoryStore(memoryDir)
    }

    @After
    fun tearDown() {
        memoryDir.deleteRecursively()
    }

    private fun entry(
        id: String,
        content: String = "content-$id",
        type: MemoryType = MemoryType.SHORT_TERM
    ) = MemoryEntry(
        id = id,
        content = content,
        timestamp = System.currentTimeMillis(),
        type = type
    )

    @Test
    fun basicCRUDAndSearch_worksCorrectly() = runBlocking {
        val id = store.addEntry(entry("e1", "今天学习了 Kotlin 协程")).getOrThrow()
        assertEquals("e1", id)

        assertEquals("今天学习了 Kotlin 协程", store.getEntry("e1").getOrThrow()?.content)

        assertTrue(store.updateEntry("e1", "今天复习了 Kotlin 协程").isSuccess)
        assertEquals("今天复习了 Kotlin 协程", store.getEntry("e1").getOrThrow()?.content)

        val results = store.search(MemoryQuery(query = "Kotlin 协程", maxResults = 5)).getOrThrow()
        assertTrue("关键词搜索应命中", results.isNotEmpty())
        assertEquals("e1", results.first().entry.id)

        assertTrue(store.deleteEntry("e1").isSuccess)
        assertNull(store.getEntry("e1").getOrThrow())
        assertTrue(store.search(MemoryQuery(query = "Kotlin", maxResults = 5)).getOrThrow().isEmpty())
    }

    @Test
    fun concurrentAddSearchUpdate_noException_allEntriesConsistent() = runBlocking {
        val total = 200
        val expectedIds = (0 until total).map { "c-$it" }
        val writers = 8

        (0 until writers).map { w ->
            async {
                val range = (w * total / writers) until ((w + 1) * total / writers)
                range.forEach { i ->
                    val id = "c-$i"
                    store.addEntry(entry(id, "并发写入-$i")).getOrThrow()
                    store.getEntry(id).getOrThrow()
                    store.search(MemoryQuery(query = "并发写入", maxResults = 100)).getOrThrow()
                    store.updateEntry(id, "并发更新-$id").getOrThrow()
                    store.getAll().getOrThrow()
                }
            }
        }.awaitAll()

        val all = store.getAll().getOrThrow().associateBy { it.id }
        expectedIds.forEach { id ->
            assertTrue("条目 $id 应存在", all.containsKey(id))
            assertEquals("并发更新-$id", all[id]?.content)
        }
        assertEquals("磁盘持久化文件应与内存一致", total, memoryDir.listFiles { f -> f.extension == "json" }?.size)
    }

    @Test
    fun concurrentDeleteAndSearch_noException() = runBlocking {
        (0 until 100).forEach { store.addEntry(entry("d-$it")).getOrThrow() }

        (0 until 6).map { w ->
            async {
                repeat(50) { i ->
                    val id = "d-$i"
                    when (w % 3) {
                        0 -> store.deleteEntry(id).getOrThrow()
                        1 -> store.getEntry(id).getOrThrow()
                        else -> store.search(MemoryQuery(query = "content-d-$i", maxResults = 10)).getOrThrow()
                    }
                }
            }
        }.awaitAll()

        val remaining = store.getAll().getOrThrow()
        assertTrue("删除后剩余条目应小于等于 100", remaining.size <= 100)
        assertTrue(remaining.map { it.id }.distinct().size == remaining.size)
    }
}
