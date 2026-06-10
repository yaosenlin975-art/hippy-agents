package com.lin.hippyagent.core.notification

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * 通知设置管理器
 */
class NotificationSettingsManager(
    private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE)
    }

    private var currentSettings: NotificationSettings = loadSettings()

    /**
     * 获取当前设置
     */
    fun getSettings(): NotificationSettings = currentSettings

    /**
     * 更新设置
     */
    fun updateSettings(settings: NotificationSettings) {
        currentSettings = settings
        saveSettings(settings)
        Timber.i("Notification settings updated")
    }

    /**
     * 更新总开关
     */
    fun setEnabled(enabled: Boolean) {
        updateSettings(currentSettings.copy(enabled = enabled))
    }

    /**
     * 更新心跳提醒
     */
    fun setHeartbeatReminder(enabled: Boolean) {
        updateSettings(currentSettings.copy(heartbeatReminder = enabled))
    }

    /**
     * 更新任务完成通知
     */
    fun setTaskComplete(enabled: Boolean) {
        updateSettings(currentSettings.copy(taskComplete = enabled))
    }

    /**
     * 更新错误告警
     */
    fun setErrorAlert(enabled: Boolean) {
        updateSettings(currentSettings.copy(errorAlert = enabled))
    }

    /**
     * 更新静默时段
     */
    fun setQuietHours(start: Int, end: Int) {
        updateSettings(currentSettings.copy(quietHoursStart = start, quietHoursEnd = end))
    }

    /**
     * 检查是否应该发送通知
     */
    fun shouldNotify(type: UserNotificationType): Boolean {
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return currentSettings.shouldNotify(type, currentHour)
    }

    private fun saveSettings(settings: NotificationSettings) {
        try {
            val jsonString = json.encodeToString(NotificationSettings.serializer(), settings)
            prefs.edit().putString(KEY_SETTINGS, jsonString).apply()
        } catch (e: Exception) {
            Timber.e(e, "Failed to save notification settings")
        }
    }

    private fun loadSettings(): NotificationSettings {
        return try {
            val jsonString = prefs.getString(KEY_SETTINGS, null)
            if (jsonString != null) {
                json.decodeFromString(NotificationSettings.serializer(), jsonString)
            } else {
                NotificationSettings()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load notification settings")
            NotificationSettings()
        }
    }

    companion object {
        private const val KEY_SETTINGS = "notification_settings"
    }
}

