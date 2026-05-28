package com.lin.hippyagent.core.accessibility

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ScreenEvent {
    abstract val timestamp: Long
    abstract val packageName: String?

    @Serializable
    @SerialName("window_changed")
    data class WindowChanged(
        override val timestamp: Long,
        override val packageName: String?,
        val className: String? = null
    ) : ScreenEvent()

    @Serializable
    @SerialName("content_changed")
    data class ContentChanged(
        override val timestamp: Long,
        override val packageName: String?,
        val changeTypes: Set<String> = emptySet()
    ) : ScreenEvent()

    @Serializable
    @SerialName("view_clicked")
    data class ViewClicked(
        override val timestamp: Long,
        override val packageName: String?,
        val viewId: String? = null,
        val text: String? = null
    ) : ScreenEvent()
}

class ScreenEventBus {
    private val _events = MutableSharedFlow<ScreenEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ScreenEvent> = _events.asSharedFlow()

    private val eventBuffer = mutableListOf<ScreenEvent>()
    private val bufferLock = Any()

    fun emit(event: ScreenEvent) {
        _events.tryEmit(event)
        synchronized(bufferLock) {
            eventBuffer.add(event)
            if (eventBuffer.size > 100) {
                eventBuffer.removeAll { it.timestamp < System.currentTimeMillis() - 60_000 }
            }
        }
    }

    fun getRecentEvents(since: Long): List<ScreenEvent> {
        synchronized(bufferLock) {
            return eventBuffer.filter { it.timestamp >= since }.toList()
        }
    }

    fun clear() {
        synchronized(bufferLock) {
            eventBuffer.clear()
        }
    }
}
