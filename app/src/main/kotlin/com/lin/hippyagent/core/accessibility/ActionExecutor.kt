package com.lin.hippyagent.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log

class ActionExecutor(private val context: Context) {

    companion object {
        private const val TAG = "ActionExecutor"
    }

    fun pressBack(service: PhoneControlAccessibilityService): Boolean {
        return service.executeGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun pressHome(service: PhoneControlAccessibilityService): Boolean {
        return service.executeGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun pressRecents(service: PhoneControlAccessibilityService): Boolean {
        return service.executeGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    fun openNotifications(service: PhoneControlAccessibilityService): Boolean {
        return service.executeGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun openQuickSettings(service: PhoneControlAccessibilityService): Boolean {
        return service.executeGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }

    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                Log.w(TAG, "App not found: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: $packageName", e)
            false
        }
    }
}

