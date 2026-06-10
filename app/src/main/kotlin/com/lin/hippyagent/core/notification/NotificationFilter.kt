package com.lin.hippyagent.core.notification

data class NotificationFilter(
    val types: Set<NotificationType>? = null,
    val priorityMin: NotificationPriority? = null,
    val onlyUnread: Boolean = false
) {
    fun matches(event: NotificationEvent): Boolean {
        types?.let { if (event.type !in it) return false }
        priorityMin?.let {
            val order = priorityRank(event.priority)
            val minOrder = priorityRank(it)
            if (order < minOrder) return false
        }
        if (onlyUnread && event.readAt != null) return false
        return true
    }

    private fun priorityRank(priority: NotificationPriority): Int = when (priority) {
        NotificationPriority.HIGH -> 3
        NotificationPriority.NORMAL -> 2
        NotificationPriority.LOW -> 1
        NotificationPriority.SILENT -> 0
    }
}
