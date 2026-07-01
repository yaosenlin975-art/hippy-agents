package com.lin.hippyagent.core.ondevice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * 端侧模型 tool_call 解析器。
 *
 * 端侧模型（Gemma3 1B/Qwen2.5 1.5B 等）原生不支持 function calling，
 * 通过 prompt engineering 让模型输出 ```tool_call JSON 块```，
 * 再用正则解析提取为 [OnDeviceToolCall]。
 *
 * 支持两种输出格式：
 * 1. 代码块格式：```tool_call\n{"name":"...","arguments":{...}}\n```
 * 2. 内联标签格式：<tool_call>{"name":"...","arguments":{...}}</tool_call>
 */
object OnDeviceToolCallParser {

    /** 代码块格式正则 — 顶层 private val，符合 coding.md */
    private val TOOL_CALL_BLOCK = Regex(
        """```tool_call\s*\n(\{[\s\S]*?\})\s*\n```"""
    )

    /** 内联标签格式正则 — 顶层 private val，符合 coding.md */
    private val TOOL_CALL_INLINE = Regex(
        """<tool_call>\s*(\{[\s\S]*?\})\s*</tool_call>"""
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 构造工具描述 prompt，注入到 systemInstruction。
     *
     * @param tools 可用工具列表
     * @return 工具描述文本，若 tools 为空返回空字符串
     */
    fun buildToolDescriptionPrompt(tools: List<com.lin.hippyagent.core.model.ModelToolDefinition>): String {
        if (tools.isEmpty()) return ""
        return buildString {
            append("\n\n## 可用工具\n")
            append("如需调用工具，请输出以下格式（仅输出该代码块，不要输出其他内容）：\n")
            append("```tool_call\n")
            append("""{"name": "工具名", "arguments": {"参数名": "参数值"}}""")
            append("\n```\n\n")
            append("可用工具列表：\n")
            for (tool in tools) {
                append("- ${tool.name}: ${tool.description}\n")
                if (tool.parameters.isNotEmpty()) {
                    val params = tool.parameters["properties"] as? Map<*, *>
                    if (params != null) {
                        append("  参数: ")
                        append(params.keys.joinToString(", "))
                        append("\n")
                    }
                }
            }
        }
    }

    /**
     * 从模型输出中解析 tool_call。
     *
     * @param output 模型响应文本
     * @return 解析出的 tool_calls 列表，可能为空
     */
    fun parseToolCalls(output: String): List<OnDeviceToolCall> {
        val results = mutableListOf<OnDeviceToolCall>()

        // 优先匹配代码块格式
        for (match in TOOL_CALL_BLOCK.findAll(output)) {
            val jsonStr = match.groupValues[1]
            parseJson(jsonStr)?.let { results.add(it) }
        }

        // 代码块未匹配到，尝试内联标签格式
        if (results.isEmpty()) {
            for (match in TOOL_CALL_INLINE.findAll(output)) {
                val jsonStr = match.groupValues[1]
                parseJson(jsonStr)?.let { results.add(it) }
            }
        }

        return results
    }

    /**
     * 去除模型输出中的 tool_call 代码块，返回纯文本内容。
     */
    fun stripToolCallBlocks(output: String): String {
        var result = TOOL_CALL_BLOCK.replace(output, "")
        result = TOOL_CALL_INLINE.replace(result, "")
        return result.trim()
    }

    private fun parseJson(jsonStr: String): OnDeviceToolCall? = try {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val arguments = obj["arguments"]?.let { argEl ->
            (argEl as? JsonObject) ?: run {
                Timber.w("arguments is not JsonObject: ${argEl::class.simpleName}")
                JsonObject(emptyMap())
            }
        } ?: JsonObject(emptyMap())
        OnDeviceToolCall(name = name, arguments = arguments)
    } catch (e: Exception) {
        null
    }
}

/**
 * 端侧 tool_call 解析结果。
 * 与 [com.lin.hippyagent.core.model.ToolCallInfo] 不同：arguments 是 JsonObject 而非 String。
 */
data class OnDeviceToolCall(
    val name: String,
    val arguments: JsonObject
)
