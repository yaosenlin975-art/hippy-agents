package com.lin.hippyagent.ui.notification

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.lin.hippyagent.ui.entry.AgentAction
import com.lin.hippyagent.ui.entry.AgentEntryRouter
import timber.log.Timber

/**
 * 处理通知栏 RemoteInput 直接回复。
 *
 * Intent extras 约定（由 HippyAgentNotificationService.sendAgentMessageNotification 注入）：
 * - EXTRA_SESSION_ID: String  — 路由到对应会话
 * - EXTRA_CHANNEL_ID: String  — 消息渠道（默认 "default"）
 * - EXTRA_NOTIFICATION_ID: Int — 用于 cancel
 *
 * RemoteInput key = [KEY_REPLY_TEXT]
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: run {
            Timber.w("NotificationReplyReceiver: missing EXTRA_SESSION_ID")
            return
        }
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: "default"
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        val replyText = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY_TEXT)?.toString()
        if (replyText.isNullOrBlank()) {
            Timber.w("NotificationReplyReceiver: empty reply text")
            return
        }

        AgentEntryRouter.route(
            context,
            AgentAction.SendQuickMessage(text = replyText, sessionId = sessionId, channelId = channelId)
        )

        if (notificationId != -1) {
            runCatching { NotificationManagerCompat.from(context).cancel(notificationId) }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.lin.hippyagent.action.REPLY"
        const val KEY_REPLY_TEXT = "reply_text"
        const val EXTRA_SESSION_ID = "reply_session_id"
        const val EXTRA_CHANNEL_ID = "reply_channel_id"
        const val EXTRA_NOTIFICATION_ID = "reply_notification_id"
    }
}
