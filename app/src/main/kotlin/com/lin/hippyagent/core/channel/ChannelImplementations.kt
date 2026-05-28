package com.lin.hippyagent.core.channel

import com.lin.hippyagent.core.util.FileUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.URLEncoder

class DingTalkChannel(
    private val webhookUrl: String,
    private val secret: String = ""
) : Channel {
    override val id = "dingtalk"
    override val name = "钉钉"
    override var isEnabled = true

    private val client = OkHttpClient()
    private val _messageFlow = MutableSharedFlow<ChannelMessage>()
    private var lastActivityTime = 0L
    private var connected = false

    override suspend fun sendMessage(message: ChannelMessage): Result<Unit> = runCatching {
        val jsonBody = JSONObject().apply {
            put("msgtype", "text")
            put("text", JSONObject().apply {
                put("content", message.content)
            })
        }
        val body = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(webhookUrl).post(body).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("DingTalk send failed: HTTP ${response.code}")
        }
        lastActivityTime = System.currentTimeMillis()
        connected = true
        _messageFlow.emit(message)
    }

    override suspend fun receiveMessages(): Flow<ChannelMessage> = _messageFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = runCatching {
        connected = true
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        connected = false
    }

    override suspend fun isConnected(): Boolean = connected && isEnabled

    override suspend fun healthCheck(): ChannelHealthStatus {
        if (!isEnabled) return ChannelHealthStatus(id, false, lastActivityTime, error = "Channel disabled")
        return try {
            val start = System.currentTimeMillis()
            val probeBody = JSONObject().apply {
                put("msgtype", "text")
                put("text", JSONObject().apply { put("content", "health probe") })
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(webhookUrl).post(probeBody).build()
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
        Timber.i("DingTalk channel restarted")
    }
}

class FeishuChannel(
    private val webhookUrl: String,
    private val secret: String = ""
) : Channel {
    override val id = "feishu"
    override val name = "飞书"
    override var isEnabled = true

    private val client = OkHttpClient()
    private val _messageFlow = MutableSharedFlow<ChannelMessage>()
    private var lastActivityTime = 0L
    private var connected = false

    override suspend fun sendMessage(message: ChannelMessage): Result<Unit> = runCatching {
        val jsonBody = JSONObject().apply {
            put("msg_type", "text")
            put("content", JSONObject().apply {
                put("text", message.content)
            })
        }
        val body = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(webhookUrl).post(body).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("Feishu send failed: HTTP ${response.code}")
        }
        lastActivityTime = System.currentTimeMillis()
        connected = true
        _messageFlow.emit(message)
    }

    override suspend fun receiveMessages(): Flow<ChannelMessage> = _messageFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = runCatching {
        connected = true
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        connected = false
    }

    override suspend fun isConnected(): Boolean = connected && isEnabled

    override suspend fun healthCheck(): ChannelHealthStatus {
        if (!isEnabled) return ChannelHealthStatus(id, false, lastActivityTime, error = "Channel disabled")
        return try {
            val start = System.currentTimeMillis()
            val probeBody = JSONObject().apply {
                put("msg_type", "text")
                put("content", JSONObject().apply { put("text", "health probe") })
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(webhookUrl).post(probeBody).build()
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
        Timber.i("Feishu channel restarted")
    }
}

class WeChatChannel(
    private val webhookUrl: String,
    private val corpId: String = "",
    private val agentId: String = ""
) : Channel {
    override val id = "wechat"
    override val name = "微信"
    override var isEnabled = true

    private val client = OkHttpClient()
    private val _messageFlow = MutableSharedFlow<ChannelMessage>()
    private var lastActivityTime = 0L
    private var connected = false

    override suspend fun sendMessage(message: ChannelMessage): Result<Unit> = runCatching {
        val jsonBody = JSONObject().apply {
            put("msgtype", "text")
            put("text", JSONObject().apply {
                put("content", message.content)
            })
        }
        val body = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(webhookUrl).post(body).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("WeChat send failed: HTTP ${response.code}")
        }
        lastActivityTime = System.currentTimeMillis()
        connected = true
        _messageFlow.emit(message)
    }

    override suspend fun receiveMessages(): Flow<ChannelMessage> = _messageFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = runCatching {
        connected = true
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        connected = false
    }

    override suspend fun isConnected(): Boolean = connected && isEnabled

    override suspend fun healthCheck(): ChannelHealthStatus {
        if (!isEnabled) return ChannelHealthStatus(id, false, lastActivityTime, error = "Channel disabled")
        return try {
            val start = System.currentTimeMillis()
            val probeBody = JSONObject().apply {
                put("msgtype", "text")
                put("text", JSONObject().apply { put("content", "health probe") })
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(webhookUrl).post(probeBody).build()
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
        Timber.i("WeChat channel restarted")
    }
}

class DiscordChannel(
    private val webhookUrl: String
) : Channel {
    override val id = "discord"
    override val name = "Discord"
    override var isEnabled = true

    private val client = OkHttpClient()
    private val _messageFlow = MutableSharedFlow<ChannelMessage>()
    private var lastActivityTime = 0L
    private var connected = false

    override suspend fun sendMessage(message: ChannelMessage): Result<Unit> = runCatching {
        val jsonBody = JSONObject().apply {
            put("content", message.content)
        }
        val body = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(webhookUrl).post(body).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("Discord send failed: HTTP ${response.code}")
        }
        lastActivityTime = System.currentTimeMillis()
        connected = true
        _messageFlow.emit(message)
    }

    override suspend fun receiveMessages(): Flow<ChannelMessage> = _messageFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = runCatching {
        connected = true
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        connected = false
    }

    override suspend fun isConnected(): Boolean = connected && isEnabled

    override suspend fun healthCheck(): ChannelHealthStatus {
        if (!isEnabled) return ChannelHealthStatus(id, false, lastActivityTime, error = "Channel disabled")
        return try {
            val start = System.currentTimeMillis()
            val probeBody = JSONObject().apply {
                put("content", "health probe")
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(webhookUrl).post(probeBody).build()
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
        Timber.i("Discord channel restarted")
    }
}

class ChannelConfigStore(
    private val storageDir: java.io.File? = null
) {
    private val configs = mutableMapOf<String, Map<String, String>>()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        storageDir?.let {
            it.mkdirs()
            loadConfigs()
        }
    }

    fun saveConfig(channelId: String, config: Map<String, String>) {
        configs[channelId] = config
        persistConfig(channelId, config)
    }

    fun getConfig(channelId: String): Map<String, String>? = configs[channelId]

    fun listConfigs(): Map<String, Map<String, String>> = configs.toMap()

    fun deleteConfig(channelId: String) {
        configs.remove(channelId)
        storageDir?.let { File(it, "$channelId.json").delete() }
    }

    private fun persistConfig(channelId: String, config: Map<String, String>) {
        storageDir?.let { dir ->
            try {
                val file = File(dir, "$channelId.json")
                val jsonObj = kotlinx.serialization.json.buildJsonObject {
                    config.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
                }
                FileUtils.atomicWrite(file, jsonObj.toString())
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to persist channel config: $channelId")
            }
        }
    }

    private fun loadConfigs() {
        storageDir?.let { dir ->
            dir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
                try {
                    val channelId = file.nameWithoutExtension
                    val jsonObj = json.parseToJsonElement(file.readText()).jsonObject
                    val config = jsonObj.mapValues { (_, v) ->
                        (v as? JsonPrimitive)?.content ?: v.toString().removeSurrounding("\"")
                    }
                    configs[channelId] = config
                } catch (e: Exception) {
                    timber.log.Timber.e(e, "Failed to load channel config: ${file.name}")
                }
            }
        }
    }
}

