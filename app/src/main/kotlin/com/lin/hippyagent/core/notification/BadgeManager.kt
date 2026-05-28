package com.lin.hippyagent.core.notification

import android.content.Context
import android.content.Intent
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.agent.session.UnreadSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

class BadgeManager(
    private val context: Context,
    private val sessionStore: SessionStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startObserving() {
        scope.launch {
            sessionStore.observeUnreadSummary().collectLatest { summary ->
                updateLauncherBadge(summary)
            }
        }
    }

    private fun updateLauncherBadge(summary: UnreadSummary) {
        val count = summary.totalUnreadCount
        try {
            val intent = Intent("android.intent.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", getLauncherClassName())
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Timber.d(e, "Launcher badge not supported")
        }
    }

    private fun getLauncherClassName(): String {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(context.packageName)
        return intent?.component?.className ?: ""
    }
}

