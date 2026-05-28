package com.lin.hippyagent.core.tools.builtin

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

class ReadLogcatTool : Tool() {
    override val definition = ToolDefinition(
        name = "read_logcat",
        description = "读取本应用的 logcat 日志，每次返回最近50条。可通过 level 参数过滤日志级别，通过 keyword 参数过滤关键词，通过 offset 参数跳过前面的条目以实现翻页。",
        parameters = mapOf(
            "level" to ToolParameter(
                name = "level",
                type = "string",
                description = "日志级别过滤: V(Verbose), D(Debug), I(Info), W(Warn), E(Error), F(Fatal)。默认为 D",
                required = false,
                defaultValue = "D"
            ),
            "keyword" to ToolParameter(
                name = "keyword",
                type = "string",
                description = "关键词过滤，只返回包含该关键词的日志",
                required = false,
                defaultValue = ""
            ),
            "offset" to ToolParameter(
                name = "offset",
                type = "integer",
                description = "跳过前N条匹配的日志，用于翻页读取更多。默认0",
                required = false,
                defaultValue = 0
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val level = (arguments["level"] as? String)?.uppercase()?.trim() ?: "D"
        val keyword = (arguments["keyword"] as? String)?.trim() ?: ""
        val offset = (arguments["offset"] as? Number)?.toInt() ?: 0
        val limit = 50

        val validLevels = setOf("V", "D", "I", "W", "E", "F")
        val effectiveLevel = if (level in validLevels) level else "D"

        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "*:$effectiveLevel")
            )
            val output = process.inputStream.bufferedReader().use { reader ->
                reader.readText()
            }
            process.waitFor()

            var lines = output.lines().filter { it.isNotBlank() }

            if (keyword.isNotEmpty()) {
                lines = lines.filter { it.contains(keyword, ignoreCase = true) }
            }

            val totalCount = lines.size
            val pagedLines = lines.drop(offset).take(limit)
            val hasMore = offset + limit < totalCount

            if (pagedLines.isEmpty()) {
                ToolResult(callId, true, output = "没有匹配的日志记录。")
            } else {
                val header = "日志 (级别>=$effectiveLevel${if (keyword.isNotEmpty()) ", 关键词=\"$keyword\"" else ""}), 显示 ${offset + 1}-${offset + pagedLines.size}/共${totalCount}条${if (hasMore) ", 还有更多(offset=${offset + limit}继续读取)" else ""}\n\n"
                ToolResult(callId, true, output = header + pagedLines.joinToString("\n"))
            }
        } catch (e: Exception) {
            ToolResult(callId, false, error = "读取 logcat 失败: ${e.message}")
        }
    }
}
