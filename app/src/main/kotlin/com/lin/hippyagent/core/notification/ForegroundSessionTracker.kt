package com.lin.hippyagent.core.notification

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ForegroundSessionTracker {
    private val _foregroundSessionId = MutableStateFlow<String?>(null)
    val foregroundSessionId: StateFlow<String?> = _foregroundSessionId.asStateFlow()

    private val _isAppForeground = MutableStateFlow(true)
    val isAppForegroundFlow: StateFlow<Boolean> = _isAppForeground.asStateFlow()

    private var lastSetSessionId: String? = null

    fun init() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _isAppForeground.value = true
                _foregroundSessionId.value = lastSetSessionId
            }

            override fun onStop(owner: LifecycleOwner) {
                _isAppForeground.value = false
                _foregroundSessionId.value = null
            }
        })
    }

    fun setForeground(sessionId: String?) {
        lastSetSessionId = sessionId
        if (_isAppForeground.value) {
            _foregroundSessionId.value = sessionId
        }
    }

    fun isForeground(sessionId: String): Boolean = _foregroundSessionId.value == sessionId
}
