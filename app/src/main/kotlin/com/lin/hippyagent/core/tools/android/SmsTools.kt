package com.lin.hippyagent.core.tools.android

import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.Locale

class SmsListTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "sms_list",
        description = "读取短信列表",
        parameters = mapOf(
            "limit" to ToolParameter(name = "limit", type = "integer", description = "条数", required = false, defaultValue = "10")
        ),
        requiredPermissions = listOf("READ_SMS"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val limit = (arguments["limit"] as? Number)?.toInt() ?: 10
        val callId = arguments["callId"] as? String ?: ""
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            null, null,
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )
        val smsList = buildString {
            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(0)
                    val body = it.getString(1)
                    val date = it.getLong(2)
                    val type = if (it.getInt(3) == 1) "Received" else "Sent"
                    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(date)
                    appendLine("[$type] $address ($dateStr): $body")
                }
            }
        }
        return ToolResult(callId, true, smsList.trimEnd().ifEmpty { "No SMS found" })
    }
}

class SmsSendTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "sms_send",
        description = "发送短信",
        parameters = mapOf(
            "to" to ToolParameter(name = "to", type = "string", description = "收件人号码", required = true),
            "text" to ToolParameter(name = "text", type = "string", description = "短信内容", required = true)
        ),
        requiredPermissions = listOf("SEND_SMS"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val to = getRequiredArgument(arguments, "to")
        val text = getRequiredArgument(arguments, "text")
        val callId = arguments["callId"] as? String ?: ""
        return try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(to, null, text, null, null)
            ToolResult(callId, true, output = "SMS sent to $to")
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to send SMS: ${e.message}")
        }
    }
}

