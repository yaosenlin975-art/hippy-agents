package com.lin.hippyagent.core.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * 后台权限请求通知的操作接收器
 * 用户点击通知按钮后，通过 ActionApprover 回应权限请求
 */
class PermissionActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PERMISSION_RESPONSE = "com.lin.hippyagent.PERMISSION_ACTION"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_ACTION = "action"
        const val EXTRA_ALWAYS_ALLOW = "always_allow"

        var actionApprover: ActionApprover? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PERMISSION_RESPONSE) return

        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val alwaysAllow = intent.getBooleanExtra(EXTRA_ALWAYS_ALLOW, false)

        Timber.i("Permission action received: $action, alwaysAllow=$alwaysAllow")

        try {
            val approver = actionApprover ?: return
            val result = when (action) {
                "allow_once" -> ApprovalResult(approved = true, duration = ApprovalDuration.ONCE)
                "always_allow" -> ApprovalResult(approved = true, duration = ApprovalDuration.SESSION)
                "deny" -> ApprovalResult(approved = false)
                else -> return
            }
            approver.respond(result)

            // 取消通知
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: ""
            val notificationId = com.lin.hippyagent.core.notification.HippyAgentNotificationService.NOTIFICATION_PERMISSION_REQUEST +
                    (requestId.hashCode())
            notificationManager.cancel(notificationId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to process permission action")
        }
    }
}

