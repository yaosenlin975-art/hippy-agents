package com.lin.hippyagent.core.channel

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class ChannelMessage(
    val content: String,
    val senderId: String = "",
    val sessionId: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

data class ChannelHealthStatus(
    val channelId: String,
    val isHealthy: Boolean,
    val lastActivityTime: Long = 0L,
    val latencyMs: Long? = null,
    val error: String? = null
)

interface Channel {
    val id: String
    val name: String
    var isEnabled: Boolean

    suspend fun sendMessage(message: ChannelMessage): Result<Unit>
    suspend fun receiveMessages(): Flow<ChannelMessage>
    suspend fun connect(): Result<Unit>
    suspend fun disconnect(): Result<Unit>
    suspend fun isConnected(): Boolean

    suspend fun healthCheck(): ChannelHealthStatus
    suspend fun restart(): Result<Unit>
}

class ConsoleChannel : Channel {
    override val id = "console"
    override val name = "Console"
    override var isEnabled = true
    private var lastActivityTime = 0L

    private val _messageFlow = MutableSharedFlow<ChannelMessage>()

    override suspend fun sendMessage(message: ChannelMessage): Result<Unit> {
        return runCatching {
            lastActivityTime = System.currentTimeMillis()
            _messageFlow.emit(message)
        }
    }

    override suspend fun receiveMessages(): Flow<ChannelMessage> = _messageFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect(): Result<Unit> = Result.success(Unit)

    override suspend fun isConnected(): Boolean = isEnabled

    override suspend fun healthCheck(): ChannelHealthStatus = ChannelHealthStatus(
        channelId = id,
        isHealthy = isEnabled,
        lastActivityTime = lastActivityTime
    )

    override suspend fun restart(): Result<Unit> = Result.success(Unit)
}

class ChannelManager {
    private val channels = ConcurrentHashMap<String, Channel>()

    fun register(channel: Channel) {
        channels[channel.id] = channel
    }

    fun unregister(channelId: String) {
        channels.remove(channelId)
    }

    fun getChannel(channelId: String): Channel? = channels[channelId]

    fun getAllChannels(): List<Channel> = channels.values.toList()

    suspend fun broadcast(message: ChannelMessage, excludeChannel: String? = null) {
        channels.forEach { (id, channel) ->
            if (id != excludeChannel && channel.isEnabled) {
                channel.sendMessage(message)
            }
        }
    }

    suspend fun checkAllHealth(): List<ChannelHealthStatus> {
        return channels.values.map { channel ->
            try {
                channel.healthCheck()
            } catch (e: Exception) {
                ChannelHealthStatus(
                    channelId = channel.id,
                    isHealthy = false,
                    error = e.message
                )
            }
        }
    }

    suspend fun checkHealth(channelId: String): ChannelHealthStatus? {
        val channel = channels[channelId] ?: return null
        return try {
            channel.healthCheck()
        } catch (e: Exception) {
            ChannelHealthStatus(
                channelId = channelId,
                isHealthy = false,
                error = e.message
            )
        }
    }

    suspend fun restartChannel(channelId: String): Result<Unit> {
        val channel = channels[channelId] ?: return Result.failure(IllegalArgumentException("Channel not found: $channelId"))
        return channel.restart()
    }

    suspend fun restartAllChannels(): Map<String, Result<Unit>> {
        return channels.mapValues { (_, channel) ->
            try {
                channel.restart()
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}

