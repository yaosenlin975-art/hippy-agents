package com.lin.hippyagent.core.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * 离线消息缓存
 */
@Serializable
data class QueuedMessage(
    val id: String = com.lin.hippyagent.core.pool.FastId.next(),
    val sessionId: String,
    val content: String,
    val channelId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)

/**
 * 离线消息队列管理器
 * 
 * 当网络断开时，将用户消息缓存到本地
 * 网络恢复后，自动按顺序发送排队的消息
 */
class OfflineMessageQueue(
    private val context: Context
) {
    private val queueDir by lazy {
        File(context.filesDir, "offline_queue").apply { mkdirs() }
    }

    private val json = Json { encodeDefaults = true; prettyPrint = false }

    /** 网络恢复后的重发回调 */
    var onNetworkRestored: (suspend (QueuedMessage) -> Result<Unit>)? = null

    /**
     * 将消息加入离线队列
     */
    suspend fun enqueue(message: QueuedMessage): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(queueDir, "${message.id}.json")
            file.writeText(json.encodeToString(message))
            Timber.d("Message enqueued: ${message.id}")
        }
    }

    /**
     * 获取所有排队的消息
     */
    suspend fun getAll(): List<QueuedMessage> = withContext(Dispatchers.IO) {
        queueDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    json.decodeFromString<QueuedMessage>(file.readText())
                }.getOrNull()
            }
            ?.sortedBy { it.createdAt }
            ?: emptyList()
    }

    /**
     * 移除已发送的消息
     */
    suspend fun remove(messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            File(queueDir, "$messageId.json").delete()
            Timber.d("Message removed from queue: $messageId")
        }
    }

    /**
     * 清空队列
     */
    suspend fun clear(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            queueDir.listFiles()?.forEach { it.delete() }
            Timber.d("Offline queue cleared")
        }
    }

    /**
     * 队列是否为空
     */
    suspend fun isEmpty(): Boolean = getAll().isEmpty()

    /**
     * 队列大小
     */
    suspend fun size(): Int = getAll().size

    /**
     * 网络恢复后自动重发所有排队消息
     * 按创建时间顺序逐一发送，发送成功则移除，失败则跳过并记录
     */
    suspend fun flushPendingMessages() {
        val callback = onNetworkRestored ?: return
        val messages = getAll()
        if (messages.isEmpty()) return

        Timber.i("OfflineMessageQueue: flushing ${messages.size} pending messages")
        for (msg in messages) {
            if (msg.retryCount >= 5) {
                Timber.w("OfflineMessageQueue: dropping message ${msg.id} after ${msg.retryCount} retries")
                remove(msg.id)
                continue
            }
            val result = callback(msg)
            if (result.isSuccess) {
                remove(msg.id)
                Timber.d("OfflineMessageQueue: flushed message ${msg.id}")
            } else {
                // 更新重试计数
                val updated = msg.copy(retryCount = msg.retryCount + 1)
                val file = File(queueDir, "${msg.id}.json")
                runCatching { file.writeText(json.encodeToString(updated)) }
                Timber.w("OfflineMessageQueue: failed to flush ${msg.id}, retryCount=${updated.retryCount}")
            }
        }
    }
}

