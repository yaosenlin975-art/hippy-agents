package com.lin.hippyagent.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PhoneControlAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneControlA11y"

        @Volatile
        var instance: PhoneControlAccessibilityService? = null
            private set

        var screenEventBus: ScreenEventBus? = null

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "PhoneControlAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val screenEventBus = screenEventBus ?: return
        val screenEvent = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                ScreenEvent.WindowChanged(
                    timestamp = event.eventTime,
                    packageName = event.packageName?.toString(),
                    className = event.className?.toString()
                )
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                ScreenEvent.ContentChanged(
                    timestamp = event.eventTime,
                    packageName = event.packageName?.toString(),
                    changeTypes = detectChangeTypes(event)
                )
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                ScreenEvent.ViewClicked(
                    timestamp = event.eventTime,
                    packageName = event.packageName?.toString(),
                    viewId = event.source?.viewIdResourceName,
                    text = event.text?.firstOrNull()?.toString()
                )
            else -> return
        }
        screenEventBus.emit(screenEvent)
    }

    private fun detectChangeTypes(event: AccessibilityEvent): Set<String> {
        val types = mutableSetOf<String>()
        val changeTypes = event.contentChangeTypes
        if (changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0) {
            types.add("text")
        }
        if (changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION != 0) {
            types.add("content_desc")
        }
        if (changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0) {
            types.add("subtree")
        }
        return types
    }

    override fun onInterrupt() {
        Log.w(TAG, "PhoneControlAccessibilityService interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.i(TAG, "PhoneControlAccessibilityService destroyed")
    }

    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    fun executeGlobalAction(action: Int): Boolean {
        return performGlobalAction(action)
    }

    suspend fun dispatchGesture(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { cont ->
            callbackDispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
        }
    }

    private fun callbackDispatchGesture(
        gesture: GestureDescription,
        callback: GestureResultCallback,
        handler: android.os.Handler?
    ) {
        dispatchGesture(gesture, callback, handler)
    }
}

