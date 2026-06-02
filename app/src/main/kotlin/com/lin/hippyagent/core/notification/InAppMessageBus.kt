package com.lin.hippyagent.core.notification

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object InAppMessageBus {

    data class InAppMessage(
        val agentName: String,
        val sessionName: String,
        val message: String,
        val sessionId: String,
        val agentId: String?
    )

    private val _events = MutableSharedFlow<InAppMessage>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<InAppMessage> = _events.asSharedFlow()

    fun emit(message: InAppMessage) {
        _events.tryEmit(message)
    }
}
