package com.lin.hippyagent.core.tools.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import timber.log.Timber

/**
 * 页面内容提取器 — 从 WebView 提取结构化内容
 *
 * 参考: OpenClaw agent-browser/extract.ts extractPageContent 方法
 */
class PageContentExtractor(
    private val webViewController: WebViewController
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 提取可见文本 */
    suspend fun extractVisibleText(): String {
        return webViewController.evaluateJs(BrowserActionScripts.GET_VISIBLE_TEXT) ?: ""
    }

    /** 提取页面 HTML */
    suspend fun extractHtml(): String {
        return webViewController.evaluateJs(BrowserActionScripts.GET_HTML) ?: ""
    }

    /** 提取结构化内容 */
    suspend fun extractStructured(): StructuredContent {
        val raw = webViewController.evaluateJs(BrowserActionScripts.GET_STRUCTURED_CONTENT)
            ?: return StructuredContent(title = "", description = "")
        return try {
            val element = json.parseToJsonElement(raw).jsonObject
            StructuredContent(
                title = element["title"]?.jsonPrimitive?.content ?: "",
                description = element["description"]?.jsonPrimitive?.content ?: "",
                headings = element["headings"]?.jsonArray?.map {
                    it.jsonPrimitive.content
                } ?: emptyList(),
                links = element["links"]?.jsonArray?.map {
                    it.jsonPrimitive.content
                } ?: emptyList()
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse structured content")
            StructuredContent(title = "", description = "")
        }
    }

    /** 获取页面标题 */
    suspend fun getTitle(): String {
        return webViewController.evaluateJs(BrowserActionScripts.GET_TITLE) ?: ""
    }

    /** 获取可交互元素列表 */
    suspend fun getInteractableElements(): List<InteractableElement> {
        val raw = webViewController.evaluateJs(BrowserActionScripts.GET_INTERACTABLE_ELEMENTS)
            ?: return emptyList()
        return try {
            val array = json.parseToJsonElement(raw).jsonArray
            array.map { element ->
                val obj = element.jsonObject
                InteractableElement(
                    index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    tag = obj["tag"]?.jsonPrimitive?.content ?: "",
                    text = obj["text"]?.jsonPrimitive?.content ?: "",
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    className = obj["className"]?.jsonPrimitive?.content ?: "",
                    rect = obj["rect"]?.jsonObject?.let { rect ->
                        ElementRect(
                            x = rect["x"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                            y = rect["y"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                            w = rect["w"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                            h = rect["h"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse interactable elements")
            emptyList()
        }
    }

    /** 格式化页面摘要（用于 LLM 预览） */
    suspend fun formatPageSummary(maxLength: Int = 3000): String {
        val structured = extractStructured()
        val text = extractVisibleText()
        val truncated = text.take(maxLength)

        val sb = StringBuilder()
        if (structured.title.isNotBlank()) {
            sb.appendLine("## Title: ${structured.title}")
        }
        if (structured.description.isNotBlank()) {
            sb.appendLine("## Description: ${structured.description}")
        }
        if (structured.headings.isNotEmpty()) {
            sb.appendLine("## Headings")
            structured.headings.forEach { sb.appendLine("- $it") }
        }
        if (structured.links.isNotEmpty()) {
            sb.appendLine("## Links (${structured.links.size})")
            structured.links.take(10).forEach { sb.appendLine("- $it") }
        }
        sb.appendLine()
        sb.appendLine("## Page Text")
        sb.appendLine(truncated)
        if (text.length > maxLength) {
            sb.appendLine("\n[... ${text.length - maxLength} more chars]")
        }
        return sb.toString()
    }
}
