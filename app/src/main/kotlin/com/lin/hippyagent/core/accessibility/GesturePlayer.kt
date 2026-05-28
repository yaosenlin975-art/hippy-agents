package com.lin.hippyagent.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GesturePlayer {

    companion object {
        private const val TAG = "GesturePlayer"
        private const val CLICK_DURATION = 50L
        private const val LONG_CLICK_DURATION = 500L
        private const val SHORT_SWIPE_DURATION = 200L
        private const val LONG_SWIPE_DURATION = 400L
    }

    suspend fun click(service: PhoneControlAccessibilityService, x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, CLICK_DURATION)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture)
    }

    suspend fun longClick(service: PhoneControlAccessibilityService, x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, LONG_CLICK_DURATION)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture)
    }

    suspend fun swipe(
        service: PhoneControlAccessibilityService,
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        duration: Long = SHORT_SWIPE_DURATION
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture)
    }

    suspend fun scrollUp(service: PhoneControlAccessibilityService, screenWidth: Int, screenHeight: Int): Boolean {
        val centerX = screenWidth / 2
        return swipe(service, centerX, screenHeight * 2 / 3, centerX, screenHeight / 3, LONG_SWIPE_DURATION)
    }

    suspend fun scrollDown(service: PhoneControlAccessibilityService, screenWidth: Int, screenHeight: Int): Boolean {
        val centerX = screenWidth / 2
        return swipe(service, centerX, screenHeight / 3, centerX, screenHeight * 2 / 3, LONG_SWIPE_DURATION)
    }

    suspend fun scrollLeft(service: PhoneControlAccessibilityService, screenWidth: Int, screenHeight: Int): Boolean {
        val centerY = screenHeight / 2
        return swipe(service, screenWidth * 2 / 3, centerY, screenWidth / 3, centerY, LONG_SWIPE_DURATION)
    }

    suspend fun scrollRight(service: PhoneControlAccessibilityService, screenWidth: Int, screenHeight: Int): Boolean {
        val centerY = screenHeight / 2
        return swipe(service, screenWidth / 3, centerY, screenWidth * 2 / 3, centerY, LONG_SWIPE_DURATION)
    }
}

