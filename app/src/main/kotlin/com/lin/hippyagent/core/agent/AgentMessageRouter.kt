package com.lin.hippyagent.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val _messageQueue = MutableStateFlow<List<AgentMessage>>(emptyList())
    val messageQueue: StateFlow<List<AgentMessage>> = _messageQueue.asStateFlow()

    // 使用 WeakReference 防止内存泄漏
    private var onMessageDelivered: WeakReference<(AgentMessage) -> Unit>? = null

    // 预编译 Regex，避免每次调用都重新编译
    private val atMentionRegex = Regex("@(\\w+)")

    fun setDeliveryCallback(callback: (AgentMessage) -> Unit) {
        onMessageDelivered = WeakReference(callback)
    }

    fun routeMessage(fromAgentId: String, toAgentId: String, content: String, sessionId: String, channelId: String) {
        val message = AgentMessage(fromAgentId, toAgentId, content, sessionId, channelId)
        _messageQueue.update { it + message }
        onMessageDelivered?.get()?.invoke(message)
        Timber.i("Message routed: $fromAgentId -> $toAgentId")
    }

    fun parseAtMentions(content: String): List<String> {
        return atMentionRegex.findAll(content).map { it.groupValues[1] }.toList()
    }

    fun getMessagesForAgent(agentId: String): List<AgentMessage> {
        return _messageQueue.value.filter { it.toAgentId == agentId }
    }

    fun clearQueue() {
        _messageQueue.update { emptyList() }
    }
}

