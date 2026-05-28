package com.lin.hippyagent.core.tools.android

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.*

class ReadCalendarTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "read_calendar",
        description = "读取日历事件",
        parameters = mapOf(
            "days" to ToolParameter(
                name = "days",
                type = "integer",
                description = "未来天数",
                required = false,
                defaultValue = "7"
            )
        ),
        requiredPermissions = listOf("READ_CALENDAR"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val days = (arguments["days"] as? Number)?.toInt() ?: 7
        val callId = arguments["callId"] as? String ?: ""

        val startCal = Calendar.getInstance()
        val endCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days) }

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION
        )

        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
            arrayOf(startCal.timeInMillis.toString(), endCal.timeInMillis.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        )

        val events = buildString {
            cursor?.use {
                while (it.moveToNext()) {
                    val title = it.getString(0)
                    val start = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it.getLong(1)))
                    val end = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.getLong(2)))
                    appendLine("[$start - $end] $title")
                }
            }
        }

        return ToolResult(callId, true, events.trimEnd().ifEmpty { "No events in next $days days" })
    }
}

class WriteCalendarTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "write_calendar",
        description = "创建日历事件",
        parameters = mapOf(
            "title" to ToolParameter(name = "title", type = "string", description = "标题", required = true),
            "start_time" to ToolParameter(name = "start_time", type = "string", description = "开始时间 (yyyy-MM-dd HH:mm)", required = true)
        ),
        requiredPermissions = listOf("WRITE_CALENDAR"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val title = getRequiredArgument(arguments, "title")
        val startTimeStr = getRequiredArgument(arguments, "start_time")
        val callId = arguments["callId"] as? String ?: ""

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val startTime = fmt.parse(startTimeStr)?.time ?: return ToolResult(callId, false, error = "Invalid date format")

        val calId = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "Local")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()

        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startTime)
            put(CalendarContract.Events.DTEND, startTime + 3600000)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.CALENDAR_ID, 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return if (uri != null) {
            ToolResult(callId, true, output = "Event created: $title at $startTimeStr")
        } else {
            ToolResult(callId, false, error = "Failed to create event")
        }
    }
}

