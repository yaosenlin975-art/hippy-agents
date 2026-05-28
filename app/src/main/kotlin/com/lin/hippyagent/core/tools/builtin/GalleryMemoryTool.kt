package com.lin.hippyagent.core.tools.builtin

import android.content.Context
import com.lin.hippyagent.core.memory.gallery.GalleryMemoryScanner
import com.lin.hippyagent.core.memory.gallery.GalleryMemoryStore
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import com.lin.hippyagent.core.storage.StorageManager
import java.io.File

class GalleryMemoryTool(
    private val context: Context,
    private val storageManager: StorageManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "gallery_memory",
        description = "管理相册记忆。支持同步最近照片到记忆、搜索图片记忆。",
        parameters = mapOf(
            "action" to ToolParameter(
                name = "action",
                type = "string",
                description = "操作: sync(同步最近N张照片到记忆), search(按关键词搜索图片记忆)",
                required = true
            ),
            "limit" to ToolParameter(
                name = "limit",
                type = "integer",
                description = "同步数量，默认50",
                required = false
            ),
            "keyword" to ToolParameter(
                name = "keyword",
                type = "string",
                description = "搜索关键词 (action=search时必填)",
                required = false
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val action = getRequiredArgument(args, "action")
        return when (action) {
            "sync" -> syncGallery(ctx, args)
            "search" -> searchGallery(ctx, args)
            else -> ToolResult(args["callId"] as? String ?: "", false, error = "Unknown action: $action")
        }
    }

    override suspend fun execute(arguments: Map<String, Any>): ToolResult = execute(ToolContext(), arguments)

    private suspend fun syncGallery(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val callId = args["callId"] as? String ?: ""
        val limit = (args["limit"] as? Number)?.toInt() ?: 50
        val scanner = GalleryMemoryScanner(context)
        val workspaceDir = File(storageManager.getWorkingDir(), "workspaces/${ctx.agentId}")
        val store = GalleryMemoryStore(workspaceDir)
        val entries = scanner.scanRecent(limit)
        store.syncEntries(entries)
        return ToolResult(callId, true, forLLM = "已同步 ${entries.size} 张照片到图片记忆", forUser = "已同步 ${entries.size} 张照片")
    }

    private suspend fun searchGallery(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val callId = args["callId"] as? String ?: ""
        val keyword = getRequiredArgument(args, "keyword")
        val workspaceDir = File(storageManager.getWorkingDir(), "workspaces/${ctx.agentId}")
        val store = GalleryMemoryStore(workspaceDir)
        val results = store.searchByKeyword(keyword)
        return if (results.isEmpty()) {
            ToolResult(callId, true, forLLM = "未找到与'$keyword'相关的图片记忆")
        } else {
            ToolResult(callId, true, forLLM = "找到 ${results.size} 张相关图片: ${results.take(5).joinToString { it.fileName }}")
        }
    }
}
