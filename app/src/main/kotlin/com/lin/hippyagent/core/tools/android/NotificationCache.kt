package com.lin.hippyagent.core.tools.android

import android.app.Notification
import android.service.notification.StatusBarNotification
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

object NotificationCache {
    private val notifications = CopyOnWriteArrayList<NotificationEntry>()
    private const val MAX_CACHE_SIZE = 100

    fun addNotification(sbn: StatusBarNotification) {
        val entry = NotificationEntry(
            packageName = sbn.packageName,
            title = sbn.notification.extras.getString(Notification.EXTRA_TITLE) ?: "",
            text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "",
            timestamp = sbn.postTime,
            key = sbn.key
        )
        notifications.add(0, entry)
        if (notifications.size > MAX_CACHE_SIZE) {
            notifications.removeAt(notifications.lastIndex)
        }
        Timber.d("Notification cached: ${entry.packageName} - ${entry.title}")
    }

    fun getRecentNotifications(limit: Int = 10): List<NotificationEntry> {
        return notifications.take(limit)
    }

    fun searchNotifications(query: String): List<NotificationEntry> {
        if (query.isBlank()) return getRecentNotifications()
        return notifications.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.text.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
        }
    }

    fun clearCache() {
        notifications.clear()
    }
}

data class NotificationEntry(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val key: String
)

