package com.lin.hippyagent.core.agent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber
import java.lang.ref.WeakReference

data class AgentMessage(
    val fromAgentId: String,
    val toAgentId: String,
    val content: String,
    val sessionId: String,
    val channelId: String,
    val timestamp: Long = System.currentTimeMillis()
)

class AgentMessageRouter {
    private val _messageChannel = Channel<AgentMessage>(Channel.UNLIMITED)
    val messageFlow: Flow<AgentMessage> = _messageChannel.receiveAsFlow()

    private var onMessageDelivered: WeakReference<(AgentMessage) -> Unit>? = null

    private val atMentionRegex = Regex("@(\\w+)")

    private val messageBuffer = mutableListOf<AgentMessage>()

    fun setDeliveryCallback(callback: (AgentMessage) -> Unit) {
        onMessageDelivered = WeakReference(callback)
    }

    fun routeMessage(fromAgentId: String, toAgentId: String, content: String, sessionId: String, channelId: String) {
        val message = AgentMessage(fromAgentId, toAgentId, content, sessionId, channelId)
        _messageChannel.trySend(message)
        synchronized(messageBuffer) { messageBuffer.add(message) }
        onMessageDelivered?.get()?.invoke(message)
        Timber.i("Message routed: $fromAgentId -> $toAgentId")
    }

    fun parseAtMentions(content: String): List<String> {
        return atMentionRegex.findAll(content).map { it.groupValues[1] }.toList()
    }

    fun getMessagesForAgent(agentId: String): List<AgentMessage> {
        synchronized(messageBuffer) { return messageBuffer.filter { it.toAgentId == agentId } }
    }

    fun clearQueue() {
        synchronized(messageBuffer) { messageBuffer.clear() }
    }
}

