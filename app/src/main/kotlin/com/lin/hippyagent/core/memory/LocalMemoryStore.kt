package com.lin.hippyagent.core.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID

/** JSON 序列化用的持久化模型（不含 embedding，太大数据不适合 JSON） */
@Serializable
data class PersistentMemoryEntry(
    val id: String,
    val content: String,
    val timestamp: Long,
    val type: String,
    val metadata: Map<String, String> = emptyMap()
)

class LocalMemoryStore(
    private val memoryDir: File
) : MemoryStore {

    private val entries = mutableMapOf<String, MemoryEntry>()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    init {
        memoryDir.mkdirs()
        loadFromDisk()
    }

    /** 启动时从磁盘加载所有持久化条目 */
    private fun loadFromDisk() {
        val files = memoryDir.listFiles { f -> f.extension == "json" } ?: return
        for (file in files) {
            try {
                val text = file.readText()
                val persistent = json.decodeFromString<PersistentMemoryEntry>(text)
                val entry = MemoryEntry(
                    id = persistent.id,
                    content = persistent.content,
                    timestamp = persistent.timestamp,
                    type = MemoryType.valueOf(persistent.type),
                    metadata = persistent.metadata
                )
                entries[entry.id] = entry
            } catch (e: Exception) {
                Timber.e(e, "Failed to load memory entry from ${file.name}")
            }
        }
        Timber.d("LocalMemoryStore: loaded ${entries.size} entries from disk")
    }

    /** 将单条 entry 持久化到磁盘 */
    private fun persistToDisk(entry: MemoryEntry) {
        try {
            val persistent = PersistentMemoryEntry(
                id = entry.id,
                content = entry.content,
                timestamp = entry.timestamp,
                type = entry.type.name,
                metadata = entry.metadata
            )
            val file = File(memoryDir, "${entry.id}.json")
            file.writeText(json.encodeToString(persistent))
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist memory entry ${entry.id}")
        }
    }

    /** 从磁盘删除指定 entry 文件 */
    private fun deleteFromDisk(id: String) {
        try {
            val file = File(memoryDir, "$id.json")
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete memory file $id")
        }
    }

    override suspend fun addEntry(entry: MemoryEntry): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val id = entry.id.ifBlank { UUID.randomUUID().toString() }
            val newEntry = entry.copy(id = id)
            entries[id] = newEntry
            persistToDisk(newEntry)
            id
        }
    }

    override suspend fun getEntry(id: String): Result<MemoryEntry?> = withContext(Dispatchers.IO) {
        runCatching { entries[id] }
    }

    override suspend fun deleteEntry(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            entries.remove(id)
            deleteFromDisk(id)
            Unit
        }
    }

    override suspend fun updateEntry(id: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entry = entries[id] ?: return@runCatching
            val updated = entry.copy(content = content, timestamp = System.currentTimeMillis())
            entries[id] = updated
            persistToDisk(updated)
            Unit
        }
    }

    override suspend fun search(query: MemoryQuery): Result<List<SearchResult>> = withContext(Dispatchers.IO) {
        runCatching {
            entries.values
                .filter { entry ->
                    if (query.memoryTypes.isNotEmpty()) {
                        entry.type in query.memoryTypes
                    } else true
                }
                .filter { entry ->
                    if (query.filters.isNotEmpty()) {
                        query.filters.all { (key, value) -> entry.metadata[key] == value }
                    } else true
                }
                .map { entry ->
                    val score = computeRelevance(entry, query.query)
                    SearchResult(entry = entry, score = score)
                }
                .filter { it.score >= query.minScore }
                .sortedByDescending { it.score }
                .take(query.maxResults)
        }
    }

    override suspend fun getAll(): Result<List<MemoryEntry>> = withContext(Dispatchers.IO) {
        runCatching { entries.values.toList() }
    }

    override suspend fun clear(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching<Unit> {
            entries.clear()
            // 清除磁盘上所有 JSON 文件
            memoryDir.listFiles { f -> f.extension == "json" }?.forEach { it.delete() }
        }
    }

    private fun computeRelevance(entry: MemoryEntry, query: String): Float {
        val queryLower = query.lowercase()
        val contentLower = entry.content.lowercase()
        if (contentLower.contains(queryLower)) return 1.0f
        val queryWords = queryLower.split("\\s+".toRegex())
        val contentWords = contentLower.split("\\s+".toRegex()).toSet()
        val matchCount = queryWords.count { it in contentWords }
        return if (queryWords.isEmpty()) 0f else matchCount.toFloat() / queryWords.size
    }
}
