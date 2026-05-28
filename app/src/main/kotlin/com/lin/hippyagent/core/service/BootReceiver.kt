package com.lin.hippyagent.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Timber.d("Boot completed received")

            // 检查是否启用了开机自启
            val prefs = context.getSharedPreferences("hippy_settings", Context.MODE_PRIVATE)
            val autoStartEnabled = prefs.getBoolean("auto_start_on_boot", false)

            if (autoStartEnabled) {
                // 获取上次运行的 Agent ID
                val lastAgentId = prefs.getString("last_running_agent_id", null)
                if (lastAgentId != null) {
                    Timber.i("Auto-starting agent: $lastAgentId")
                    AgentForegroundService.start(context, lastAgentId)
                } else {
                    Timber.d("No agent ID found, skipping auto-start")
                }
            } else {
                Timber.d("Auto-start disabled, skipping")
            }
        }
    }
}

