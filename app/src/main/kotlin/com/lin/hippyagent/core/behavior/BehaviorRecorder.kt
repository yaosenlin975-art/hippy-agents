package com.lin.hippyagent.core.behavior

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

object BehaviorRecorder {

    data class RecordedEvent(
        val eventType: String,
        val packageName: String,
        val className: String,
        val text: String?,
        val contentDescription: String?,
        val timestampMs: Long
    )

    data class UiState(
        val isRecording: Boolean = false,
        val eventCount: Int = 0,
        val currentPageTitle: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val events = mutableListOf<RecordedEvent>()
    private val eventsLock = Any()

    fun start(application: Application): Boolean {
        if (_uiState.value.isRecording) return true
        synchronized(eventsLock) { events.clear() }
        _uiState.value = UiState(isRecording = true)
        Timber.i("BehaviorRecorder: started recording")
        return true
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!_uiState.value.isRecording) return
        val recorded = RecordedEvent(
            eventType = event.eventType.toString(),
            packageName = event.packageName?.toString() ?: "",
            className = event.className?.toString() ?: "",
            text = event.text?.joinToString(" ")?.ifBlank { null },
            contentDescription = event.contentDescription?.toString(),
            timestampMs = System.currentTimeMillis()
        )
        synchronized(eventsLock) {
            if (events.size >= MAX_RECORDED_EVENTS) {
                events.removeAt(0)
            }
            val previous = events.lastOrNull()
            val isDuplicate = previous != null &&
                previous.eventType == recorded.eventType &&
                previous.packageName == recorded.packageName &&
                previous.className == recorded.className &&
                previous.text == recorded.text &&
                previous.contentDescription == recorded.contentDescription &&
                (recorded.timestampMs - previous.timestampMs) < DEDUP_WINDOW_MS
            if (!isDuplicate) {
                events.add(recorded)
            }
        }
        _uiState.value = _uiState.value.copy(
            eventCount = synchronized(eventsLock) { events.size },
            currentPageTitle = recorded.text
        )
    }

    fun stop(): List<RecordedEvent> {
        _uiState.value = UiState()
        val result = synchronized(eventsLock) { events.toList().also { events.clear() } }
        Timber.i("BehaviorRecorder: stopped, recorded ${result.size} events")
        return result
    }

    companion object {
        private const val MAX_RECORDED_EVENTS = 200
        private const val DEDUP_WINDOW_MS = 300L
    }
}
