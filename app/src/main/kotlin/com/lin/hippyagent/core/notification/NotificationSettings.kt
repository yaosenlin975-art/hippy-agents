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
    fun shouldNotify(type: UserNotificationType, currentHour: Int): Boolean {
        if (!enabled) return false
        if (isInQuietHours(currentHour)) return false

        return when (type) {
            UserNotificationType.HEARTBEAT -> heartbeatReminder
            UserNotificationType.TASK_COMPLETE -> taskComplete
            UserNotificationType.ERROR -> errorAlert
            UserNotificationType.AGENT_MESSAGE -> agentMessage
        }
    }
}

enum class UserNotificationType {
    HEARTBEAT,
    TASK_COMPLETE,
    ERROR,
    AGENT_MESSAGE
}

