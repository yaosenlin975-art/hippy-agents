package com.lin.hippyagent.core.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking

class ToolApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra("request_id") ?: return
        val actionStr = intent.getStringExtra("action") ?: return
        val action = when (actionStr) {
            "allow_once" -> ApprovalAction.ALLOW_ONCE
            "allow_always" -> ApprovalAction.ALLOW_ALWAYS
            "deny_once" -> ApprovalAction.DENY_ONCE
            "deny_always" -> ApprovalAction.DENY_ALWAYS
            else -> return
        }
        runBlocking { manager?.resolveApproval(requestId, action) }
    }

    companion object {
        var manager: ToolApprovalManager? = null
    }
}
