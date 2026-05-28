package com.lin.hippyagent.core.tools

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

class ToolSearchTool(
    private val deferredRegistry: DeferredToolRegistry
) : Tool() {

    override val definition = ToolDefinition(
        name = "tool_search",
        description = "搜索延迟加载的工具。部分工具只显示名称但不包含参数schema，需要通过此工具获取完整定义后才能调用。\n" +
            "查询形式：\n" +
            "- \"select:read_file,write_file\" — 按名称精确选择\n" +
            "- \"+bluetooth connect\" — 名称必须包含bluetooth，按剩余关键词排序\n" +
            "- \"sensor temperature\" — 正则搜索名称和描述",
        parameters = mapOf(
            "query" to ToolParameter(
                name = "query",
                type = "string",
                description = "搜索查询。使用 select:精确选择，+关键词必含搜索，或通用正则搜索",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val query = getRequiredArgument(arguments, "query")

        if (deferredRegistry.size == 0) {
            return ToolResult(callId, true, output = "No deferred tools available.")
        }

        val matched = deferredRegistry.search(query)
        if (matched.isEmpty()) {
            return ToolResult(callId, true, output = "No tools found matching: $query")
        }

        val jsonArray = JSONArray()
        for (def in matched) {
            val toolObj = JSONObject()
            toolObj.put("name", def.name)
            toolObj.put("description", def.description)
            val paramsObj = JSONObject()
            for ((key, schema) in def.parameters) {
                paramsObj.put(key, schema)
            }
            toolObj.put("parameters", paramsObj)
            jsonArray.put(toolObj)
        }

        val result = jsonArray.toString(2)
        Timber.d("ToolSearchTool: query='$query' matched ${matched.size} tools")
        return ToolResult(callId, true, output = result)
    }
}
