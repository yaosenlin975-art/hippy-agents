package com.lin.hippyagent.core.tools.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

class MakeCallTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "make_call",
        description = "拨打电话",
        parameters = mapOf(
            "phone_number" to ToolParameter(name = "phone_number", type = "string", description = "电话号码", required = true)
        ),
        requiredPermissions = listOf("CALL_PHONE"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val phoneNumber = getRequiredArgument(arguments, "phone_number")
        val callId = arguments["callId"] as? String ?: ""
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ToolResult(callId, true, output = "Calling: $phoneNumber")
        } catch (e: SecurityException) {
            ToolResult(callId, false, error = "CALL_PHONE permission required")
        }
    }
}

class ReadCallLogTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "read_call_log",
        description = "读取通话记录",
        parameters = mapOf(
            "limit" to ToolParameter(name = "limit", type = "integer", description = "条数", required = false, defaultValue = "10")
        ),
        requiredPermissions = listOf("READ_CALL_LOG"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val limit = (arguments["limit"] as? Number)?.toInt() ?: 10
        val callId = arguments["callId"] as? String ?: ""
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
            null, null,
            "${CallLog.Calls.DATE} DESC"
        )
        val logs = buildString {
            var count = 0
            cursor?.use {
                while (it.moveToNext() && count < limit) {
                    val number = it.getString(0)
                    val date = it.getLong(1)
                    val type = when (it.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Unknown"
                    }
                    val duration = it.getInt(3)
                    appendLine("[$type] $number - ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(date)} (${duration}s)")
                    count++
                }
            }
        }
        return ToolResult(callId, true, logs.trimEnd().ifEmpty { "No call logs" })
    }
}

