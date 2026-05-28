package com.lin.hippyagent.core.tools.builtin

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class GetCurrentTimeTool(
    private val userTimezone: String = "Asia/Shanghai"
) : Tool() {
    override val definition = ToolDefinition(
        name = "get_current_time",
        description = "获取当前日期和时间",
        parameters = mapOf(
            "timezone" to ToolParameter(
                name = "timezone",
                type = "string",
                description = "IANA 时区名称 (如 Asia/Shanghai)",
                required = false,
                defaultValue = "Asia/Shanghai"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val tz = (arguments["timezone"] as? String) ?: userTimezone
        return try {
            val zone = ZoneId.of(tz)
            val now = ZonedDateTime.now(zone)
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            ToolResult(callId, true, output = "Current time: ${now.format(formatter)}")
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Invalid timezone: $tz. Error: ${e.message}")
        }
    }
}

