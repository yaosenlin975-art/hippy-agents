package com.lin.hippyagent.core.tools.builtin

import com.lin.hippyagent.core.memory.commonmemory.MemoryRepository
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import timber.log.Timber
import java.io.File

class MemorySearchTool(
    private val memoryRepository: MemoryRepository
) : Tool() {

    override val definition = ToolDefinition(
        name = "memory_search",
        description = "搜索长期记忆。搜索范围包括：智能体的 MEMORY.md、memory 文件夹下的所有 markdown 文件、CommonMemory 记忆库。根据关键词检索之前保存的记忆、决策、偏好、待办等信息。",
        parameters = mapOf(
            "query" to ToolParameter(
                name = "query",
                type = "string",
                description = "搜索关键词或问题",
                required = true
            ),
            "limit" to ToolParameter(
                name = "limit",
                type = "integer",
                description = "返回结果数量上限，默认5",
                required = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val query = getRequiredArgument(arguments, "query")
        val limit = (arguments["limit"] as? Number)?.toInt() ?: 5
        return search(callId, query, limit.coerceIn(1, 20), null)
    }

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val callId = args["callId"] as? String ?: ""
        val query = getRequiredArgument(args, "query")
        val limit = (args["limit"] as? Number)?.toInt() ?: 5
        return search(callId, query, limit.coerceIn(1, 20), ctx.workspace)
    }

    private suspend fun search(callId: String, query: String, limit: Int, workspace: File?): ToolResult {
        if (query.isBlank()) {
            return ToolResult(callId, false, error = "Query cannot be empty")
        }

        return try {
            val keywords = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
            val fileResults = mutableListOf<String>()
            val effectiveLimit = limit.coerceIn(1, 20)

            if (workspace != null && keywords.isNotEmpty()) {
                searchWorkspaceFiles(workspace, keywords, fileResults)
            }

            val sbResults = memoryRepository.searchHybrid(query, limit = effectiveLimit)
            Timber.d("MemorySearchTool: found ${sbResults.size} SB results, ${fileResults.size} file results for '$query'")

            val sb = StringBuilder()
            var totalResults = 0

            if (fileResults.isNotEmpty()) {
                sb.appendLine("=== 工作区记忆文件 ===")
                sb.appendLine()
                for (line in fileResults.take(effectiveLimit)) {
                    sb.appendLine(line)
                    totalResults++
                }
                sb.appendLine()
            }

            if (sbResults.isNotEmpty()) {
                sb.appendLine("=== CommonMemory 记忆库 ===")
                sb.appendLine()
                for ((entry, score) in sbResults.take(effectiveLimit - totalResults).takeIf { totalResults < effectiveLimit } ?: sbResults.take(effectiveLimit)) {
                    sb.appendLine("- [${entry.type.value}] ${entry.summary} (relevance: ${(score * 100).toInt()}%)")
                    if (!entry.detail.isNullOrBlank()) {
                        sb.appendLine("  ${entry.detail.take(200)}")
                    }
                    totalResults++
                }
            }

            if (totalResults == 0) {
                ToolResult(callId, true, output = "No memories found for: $query")
            } else {
                ToolResult(callId, true, output = "Found $totalResults memories:\n\n$sb")
            }
        } catch (e: Exception) {
            Timber.e(e, "MemorySearchTool: search failed")
            ToolResult(callId, false, error = "Memory search failed: ${e.message}")
        }
    }

    private fun searchWorkspaceFiles(workspace: File, keywords: List<String>, results: MutableList<String>) {
        val memoryMd = File(workspace, "MEMORY.md")
        if (memoryMd.exists()) {
            searchInFile("MEMORY.md", memoryMd, keywords, results)
        }

        val memoryDir = File(workspace, "memory")
        if (memoryDir.isDirectory) {
            memoryDir.walkTopDown()
                .filter { it.isFile && it.extension == "md" }
                .forEach { file ->
                    val relPath = "memory/${file.relativeTo(memoryDir).path}"
                    searchInFile(relPath, file, keywords, results)
                }
        }
    }

    private fun searchInFile(label: String, file: File, keywords: List<String>, results: MutableList<String>) {
        runCatching {
            val content = file.readText()
            val lines = content.lines()
            for (line in lines) {
                val lower = line.lowercase()
                if (keywords.any { lower.contains(it) }) {
                    val trimmed = line.trim()
                    if (trimmed.isNotBlank() && trimmed.length > 3) {
                        results.add("- [$label] ${trimmed.take(200)}")
                    }
                }
            }
        }.onFailure { e ->
            Timber.w(e, "MemorySearchTool: failed to read $label")
        }
    }
}
