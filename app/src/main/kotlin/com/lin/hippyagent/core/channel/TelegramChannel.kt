package com.lin.hippyagent.core.channel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

/**
 * Telegram Bot 渠道接入
 */
class TelegramChannel(
    private val botToken: String,
    private val chatId: String
) : Channel {
    override val id = "telegram"
    override val name = "Telegram"
    override var isEnabled = true

    private val client = OkHttpClient()
    private val _messageFlow = MutableSharedFlow<ChannelMessage>()
    private val baseUrl = "https://api.telegram.org/bot$botToken"
    private var lastActivityTime = 0L
    private var connected = false

    override suspend fun sendMessage(message: ChannelMessage): Result<Unit> = runCatching {
        val jsonBody = JSONObject().apply {
            put("chat_id", chatId)
            put("text", message.content)
            put("parse_mode", "Markdown")
        }
        val body = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/sendMessage")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("Telegram send failed: HTTP ${response.code}")
        }
        lastActivityTime = System.currentTimeMillis()
        connected = true
        _messageFlow.emit(message)
    }

    override suspend fun receiveMessages(): Flow<ChannelMessage> = _messageFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = runCatching {
        val request = Request.Builder()
            .url("$baseUrl/getMe")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("Telegram bot validation failed: HTTP ${response.code}")
        }
        connected = true
        Timber.i("Telegram bot connected successfully")
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        connected = false
    }

    override suspend fun isConnected(): Boolean = connected && isEnabled

    override suspend fun healthCheck(): ChannelHealthStatus {
        if (!isEnabled) return ChannelHealthStatus(id, false, lastActivityTime, error = "Channel disabled")
        return try {
            val start = System.currentTimeMillis()
            val request = Request.Builder().url("$baseUrl/getMe").get().build()
            val response = client.newCall(request).execute()
            val latency = System.currentTimeMillis() - start
            ChannelHealthStatus(id, response.isSuccessful, lastActivityTime, latencyMs = latency)
        } catch (e: Exception) {
            ChannelHealthStatus(id, false, lastActivityTime, error = e.message)
        }
    }

    override suspend fun restart(): Result<Unit> = runCatching {
        disconnect().getOrThrow()
        connect().getOrThrow()
        Timber.i("Telegram channel restarted")
    }

    /**
     * 获取 Bot 信息
     */
    suspend fun getBotInfo(): Result<JSONObject> = runCatching {
        val request = Request.Builder()
            .url("$baseUrl/getMe")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("Failed to get bot info: HTTP ${response.code}")
        }
        JSONObject(response.body?.string() ?: "{}")
    }

    /**
     * 获取聊天更新
     */
    suspend fun getUpdates(offset: Long = 0): Result<JSONObject> = runCatching {
        val request = Request.Builder()
            .url("$baseUrl/getUpdates?offset=$offset&timeout=30")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("Failed to get updates: HTTP ${response.code}")
        }
        JSONObject(response.body?.string() ?: "{}")
    }
}

