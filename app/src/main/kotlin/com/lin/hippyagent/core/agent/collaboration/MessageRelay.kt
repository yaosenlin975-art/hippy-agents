package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.channel.Channel
import com.lin.hippyagent.core.channel.ChannelManager
import com.lin.hippyagent.core.channel.ChannelMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/**
 * Relays messages between channels and group chats.
 * Enables real-time message pushing from agent group responses to connected channels.
 */
class MessageRelay(
    private val channelManager: ChannelManager
) {
    private val _relayEvents = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 64)
    val relayEvents: SharedFlow<RelayEvent> = _relayEvents.asSharedFlow()

    /**
     * Relay an agent's response from a group chat to a target channel.
     */
    suspend fun relayToChannel(
        targetChannelId: String,
        agentId: String,
        groupName: String,
        content: String,
        sessionId: String = ""
    ): Result<Unit> {
        val channel = channelManager.getChannel(targetChannelId)
            ?: return Result.failure(IllegalStateException("Channel not found: $targetChannelId"))

        if (!channel.isEnabled) {
            return Result.failure(IllegalStateException("Channel is disabled: $targetChannelId"))
        }

        val formattedContent = "[$groupName] @$agentId: $content"
        val message = ChannelMessage(
            content = formattedContent,
            senderId = agentId,
            sessionId = sessionId,
            metadata = mapOf(
                "source" to "group_relay",
                "groupName" to groupName,
                "agentId" to agentId
            )
        )

        return channel.sendMessage(message).onSuccess {
            _relayEvents.emit(
                RelayEvent.MessageRelayed(
                    targetChannelId = targetChannelId,
                    agentId = agentId,
                    groupName = groupName,
                    timestamp = System.currentTimeMillis()
                )
            )
            Timber.d("Message relayed to $targetChannelId from $agentId in $groupName")
        }
    }

    /**
     * Broadcast a message to all enabled channels (except the source).
     */
    suspend fun broadcast(
        content: String,
        senderId: String,
        excludeChannelId: String? = null
    ): Result<Unit> {
        val message = ChannelMessage(
            content = content,
            senderId = senderId,
            metadata = mapOf("source" to "broadcast")
        )

        channelManager.broadcast(message, excludeChannelId)
        _relayEvents.emit(
            RelayEvent.BroadcastCompleted(
                senderId = senderId,
                timestamp = System.currentTimeMillis()
            )
        )
        return Result.success(Unit)
    }

    /**
     * Check health of all registered channels.
     */
    suspend fun checkChannelHealth(): Map<String, ChannelHealth> {
        val healthMap = mutableMapOf<String, ChannelHealth>()
        for (channel in channelManager.getAllChannels()) {
            healthMap[channel.id] = ChannelHealth(
                channelId = channel.id,
                channelName = channel.name,
                isEnabled = channel.isEnabled,
                isConnected = try { channel.isConnected() } catch (e: Exception) { false }
            )
        }
        return healthMap
    }
}

sealed class RelayEvent {
    data class MessageRelayed(
        val targetChannelId: String,
        val agentId: String,
        val groupName: String,
        val timestamp: Long
    ) : RelayEvent()

    data class BroadcastCompleted(
        val senderId: String,
        val timestamp: Long
    ) : RelayEvent()
}

data class ChannelHealth(
    val channelId: String,
    val channelName: String,
    val isEnabled: Boolean,
    val isConnected: Boolean
)

