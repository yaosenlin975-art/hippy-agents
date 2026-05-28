package com.lin.hippyagent.core.tools.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

class SetAlarmTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "set_alarm",
        description = "设置闹钟",
        parameters = mapOf(
            "hour" to ToolParameter(name = "hour", type = "integer", description = "小时", required = true),
            "minute" to ToolParameter(name = "minute", type = "integer", description = "分钟", required = true)
        ),
        requiredPermissions = listOf("SCHEDULE_EXACT_ALARM"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val hour = (arguments["hour"] as? Number)?.toInt() ?: return ToolResult("", false, error = "Hour required")
        val minute = (arguments["minute"] as? Number)?.toInt() ?: return ToolResult("", false, error = "Minute required")
        val callId = arguments["callId"] as? String ?: ""

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            if (before(java.util.Calendar.getInstance())) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }

        val intent = Intent("com.lin.hippyagent.ALARM_TRIGGER").apply {
            putExtra("hour", hour)
            putExtra("minute", minute)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            hour * 100 + minute,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }
            ToolResult(callId, true, output = "Alarm set for ${String.format("%02d:%02d", hour, minute)}")
        } catch (e: SecurityException) {
            ToolResult(callId, false, error = "SCHEDULE_EXACT_ALARM permission required")
        }
    }
}

