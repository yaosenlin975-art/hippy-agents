package com.lin.hippyagent.core.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ForegroundSessionTracker {
    private val _foregroundSessionId = MutableStateFlow<String?>(null)
    val foregroundSessionId: StateFlow<String?> = _foregroundSessionId.asStateFlow()

    fun setForeground(sessionId: String?) {
        _foregroundSessionId.value = sessionId
    }

    fun isForeground(sessionId: String): Boolean = _foregroundSessionId.value == sessionId
}
