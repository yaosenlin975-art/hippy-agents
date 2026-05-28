package com.lin.hippyagent.core.channel.feishu

import com.lin.hippyagent.core.channel.Channel
import com.lin.hippyagent.core.channel.ChannelHealthStatus
import com.lin.hippyagent.core.channel.ChannelMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import timber.log.Timber

class FeishuChannel(
    private val appId: String,
    private val appSecret: String,
    private val okHttpClient: OkHttpClient,
    private val chatId: String = "",
    private val pollIntervalMs: Long = 5000L
) : Channel {
    override val id = "feishu"
    override val name = "飞书"
    override var isEnabled = true

    private val apiClient = FeishuApiClient(appId, appSecret, okHttpClient)
    private val _messageFlow = MutableSharedFlow<ChannelMessage>()
    private var lastActivityTime = 0L
    private var connected = false
    private var pollJob: Job? = null
    private var lastMessageId: String = ""
    private val scope = CoroutineScope(Dispatchers.IO)

    override suspend fun sendMessage(message: ChannelMessage): Result<Unit> = runCatching {
        val receiveId = message.metadata["chatId"] ?: chatId
        if (receiveId.isEmpty()) {
            throw RuntimeException("No chatId provided for Feishu message")
        }
        apiClient.sendMessage(receiveId, message.content).getOrThrow()
        lastActivityTime = System.currentTimeMillis()
        _messageFlow.emit(message)
    }

    override suspend fun receiveMessages(): Flow<ChannelMessage> = _messageFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = runCatching {
        apiClient.getTenantAccessToken().getOrThrow()
        connected = true
        startPolling()
        Timber.i("Feishu channel connected")
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        stopPolling()
        apiClient.clearToken()
        connected = false
        Timber.i("Feishu channel disconnected")
    }

    override suspend fun isConnected(): Boolean = connected && isEnabled

    override suspend fun healthCheck(): ChannelHealthStatus {
        if (!isEnabled) return ChannelHealthStatus(id, false, lastActivityTime, error = "Channel disabled")
        if (!connected) return ChannelHealthStatus(id, false, lastActivityTime, error = "Not connected")
        return try {
            val start = System.currentTimeMillis()
            val result = apiClient.getTenantAccessToken()
            val latency = System.currentTimeMillis() - start
            if (result.isSuccess) {
                ChannelHealthStatus(id, true, lastActivityTime, latencyMs = latency)
            } else {
                ChannelHealthStatus(id, false, lastActivityTime, latencyMs = latency, error = result.exceptionOrNull()?.message)
            }
        } catch (e: Exception) {
            ChannelHealthStatus(id, false, lastActivityTime, error = e.message)
        }
    }

    override suspend fun restart(): Result<Unit> = runCatching {
        disconnect().getOrThrow()
        connect().getOrThrow()
        Timber.i("Feishu channel restarted")
    }

    private fun startPolling() {
        if (chatId.isEmpty()) {
            Timber.w("Feishu poll skipped: no chatId configured")
            return
        }
        stopPolling()
        pollJob = scope.launch {
            while (coroutineContext.isActive) {
                try {
                    val result = apiClient.listMessages(chatId)
                    if (result.isSuccess) {
                        val items = result.getOrDefault(emptyList())
                        for (item in items) {
                            if (item.message_id.isNotEmpty() && item.message_id != lastMessageId) {
                                lastMessageId = item.message_id
                                val senderId = item.sender?.sender_id?.open_id ?: item.sender?.sender_id?.user_id ?: ""
                                val channelMessage = ChannelMessage(
                                    content = extractText(item.content),
                                    senderId = senderId,
                                    sessionId = item.chat_id,
                                    metadata = mapOf(
                                        "messageId" to item.message_id,
                                        "chatId" to item.chat_id,
                                        "msgType" to item.msg_type
                                    ),
                                    timestamp = item.create_time.toLongOrNull() ?: System.currentTimeMillis()
                                )
                                _messageFlow.emit(channelMessage)
                                lastActivityTime = System.currentTimeMillis()
                            }
                        }
                    } else {
                        Timber.w(result.exceptionOrNull(), "Feishu poll failed")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Feishu poll error")
                }
                delay(pollIntervalMs)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private val textJson = Json { ignoreUnknownKeys = true }

    private fun extractText(content: String): String {
        return try {
            val element = textJson.parseToJsonElement(content)
            element.jsonObject["text"]?.jsonPrimitive?.content ?: content
        } catch (_: Exception) {
            content
        }
    }
}
