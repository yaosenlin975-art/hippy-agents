package com.lin.hippyagent.core.notification

import kotlinx.serialization.Serializable

/**
 * 通知设置数据模型
 */
@Serializable
data class NotificationSettings(
    val enabled: Boolean = true,
    val heartbeatReminder: Boolean = true,
    val taskComplete: Boolean = true,
    val errorAlert: Boolean = true,
    val agentMessage: Boolean = true,
    val quietHoursStart: Int = 22,
    val quietHoursEnd: Int = 8
) {
    /**
     * 检查当前是否在静默时段
     */
    fun isInQuietHours(hour: Int): Boolean {
        return if (quietHoursStart > quietHoursEnd) {
            hour >= quietHoursStart || hour < quietHoursEnd
        } else {
            hour in quietHoursStart until quietHoursEnd
        }
    }

    /**
     * 检查是否应该发送通知
     */
    fun shouldNotify(type: NotificationType, currentHour: Int): Boolean {
        if (!enabled) return false
        if (isInQuietHours(currentHour)) return false

        return when (type) {
            NotificationType.HEARTBEAT -> heartbeatReminder
            NotificationType.TASK_COMPLETE -> taskComplete
            NotificationType.ERROR -> errorAlert
            NotificationType.AGENT_MESSAGE -> agentMessage
        }
    }
}

enum class NotificationType {
    HEARTBEAT,
    TASK_COMPLETE,
    ERROR,
    AGENT_MESSAGE
}

