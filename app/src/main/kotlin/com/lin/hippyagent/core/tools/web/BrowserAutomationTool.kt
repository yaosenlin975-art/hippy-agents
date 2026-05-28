package com.lin.hippyagent.core.tools.web

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * 浏览器自动化工具 — 在 WebView 中控制网页交互
 *
 * 参考:
 * - OpenClaw agent-browser/index.ts (工具注册+路由)
 * - OpenClaw agent-browser/actions.ts (交互动作实现)
 * - Hermes BrowserSkill (工具命名参考)
 *
 * 设计原则:
 * - 单入口多 action，避免注册多个独立工具
 * - 每个 action 返回结构化内容供 LLM 决策
 * - 复用 WebFetchTool 的 WebView 初始化模式
 */
class BrowserAutomationTool(
    private val webViewController: WebViewController,
    private val pageExtractor: PageContentExtractor
) : Tool() {

    override val definition: ToolDefinition = ToolDefinition(
        name = "browser",
        description = """
            浏览器自动化工具。在应用中嵌入式 WebView 中控制网页交互。
            支持导航、点击、输入、截图、获取内容等操作，返回结构化页面信息供 LLM 决策。

            子命令:
            - browser_navigate(url): 导航到 URL，返回页面标题、描述、文本预览
            - browser_click(selector|text|index): 点击元素（CSS选择器/文本内容/元素索引）
            - browser_type(selector, text): 在输入框中输入文本
            - browser_get_text(): 获取页面可见文本
            - browser_get_html(): 获取页面 HTML
            - browser_screenshot(): 页面截图（返回图片路径）
            - browser_scroll(direction): 滚动 down/up（默认 500px）
            - browser_get_interactable(): 获取所有可交互元素列表（含索引、标签、文本、位置）
            - browser_back(): 返回上一页
            - browser_forward(): 前进
            - browser_wait(selector, timeout): 等待元素出现
            - browser_execute(script): 执行任意 JavaScript
            - browser_close(): 关闭浏览器，释放 WebView 资源
        """.trimIndent(),
        parameters = mapOf(
            "action" to ToolParameter(
                name = "action", type = "string",
                description = "操作类型: navigate/click/type/get_text/get_html/screenshot/scroll/get_interactable/back/forward/wait/execute/close",
                required = true
            ),
            "selector" to ToolParameter(
                name = "selector", type = "string",
                description = "CSS 选择器（click/type/wait 使用）",
                required = false
            ),
            "text" to ToolParameter(
                name = "text", type = "string",
                description = "文本内容（type 使用）或点击文本（click 使用）",
                required = false
            ),
            "index" to ToolParameter(
                name = "index", type = "number",
                description = "元素索引（click 使用，从 browser_get_interactable 获取）",
                required = false
            ),
            "url" to ToolParameter(
                name = "url", type = "string",
                description = "目标 URL（navigate 使用）",
                required = false
            ),
            "direction" to ToolParameter(
                name = "direction", type = "string",
                description = "滚动方向: down/up（scroll 使用）",
                required = false
            ),
            "script" to ToolParameter(
                name = "script", type = "string",
                description = "JS 代码（execute 使用）",
                required = false
            ),
            "timeout" to ToolParameter(
                name = "timeout", type = "number",
                description = "超时毫秒（wait 使用）",
                required = false,
                defaultValue = "5000"
            ),
            "amount" to ToolParameter(
                name = "amount", type = "number",
                description = "滚动像素数（scroll 使用）",
                required = false,
                defaultValue = "500"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val action = getRequiredArgument(arguments, "action")

        return try {
            val result = when (action) {
                "navigate" -> handleNavigate(arguments, callId)
                "click" -> handleClick(arguments, callId)
                "type" -> handleType(arguments, callId)
                "get_text" -> handleGetText(callId)
                "get_html" -> handleGetHtml(callId)
                "screenshot" -> handleScreenshot(callId)
                "scroll" -> handleScroll(arguments, callId)
                "get_interactable" -> handleGetInteractable(callId)
                "back" -> handleBack(callId)
                "forward" -> handleForward(callId)
                "wait" -> handleWait(arguments, callId)
                "execute" -> handleExecute(arguments, callId)
                "close" -> handleClose(callId)
                else -> ToolResult(callId, false, error = "Unknown browser action: $action")
            }
            result
        } catch (e: Exception) {
            Timber.e(e, "Browser action '$action' failed")
            ToolResult(callId, false, error = "Browser action '$action' failed: ${e.message}")
        }
    }

    /** 导航到 URL */
    private suspend fun handleNavigate(args: Map<String, Any>, callId: String): ToolResult {
        val url = getRequiredArgument(args, "url")
        Timber.d("Browser navigate: $url")

        val navResult = webViewController.navigate(url)
        if (!navResult.success) {
            return ToolResult(callId, false, error = navResult.error ?: "Navigation failed")
        }

        val summary = pageExtractor.formatPageSummary()
        return ToolResult(callId, true, output = """
            | ## Navigation Result
            | - **URL**: ${navResult.url}
            | - **Title**: ${navResult.title}
            |
            | $summary
        """.trimMargin())
    }

    /** 点击元素（支持 selector / text / index 三种模式） */
    private suspend fun handleClick(args: Map<String, Any>, callId: String): ToolResult {
        val selector = getOptionalArgument(args, "selector", null)
        val text = getOptionalArgument(args, "text", null)
        val indexStr = getOptionalArgument(args, "index", null)

        val script = when {
            !selector.isNullOrBlank() ->
                BrowserActionScripts.CLICK_BY_SELECTOR.format(escapeJs(selector))
            !text.isNullOrBlank() ->
                BrowserActionScripts.CLICK_BY_TEXT.format(escapeJs(text))
            indexStr != null -> {
                val idx = indexStr.toIntOrNull()
                if (idx == null) return ToolResult(callId, false, error = "Invalid index: $indexStr")
                BrowserActionScripts.CLICK_BY_INDEX.format(idx)
            }
            else -> return ToolResult(callId, false, error = "Need selector, text, or index")
        }

        val raw = webViewController.evaluateJs(script)
        val result = parseActionResult(raw)

        if (!result.success) {
            return ToolResult(callId, false, error = result.error ?: "Click failed")
        }

        // 等待页面可能的变化
        delay(800)

        val summary = pageExtractor.formatPageSummary(maxLength = 2000)
        return ToolResult(callId, true, output = """
            | ## Click Result
            | ${if (result.text != null) "- **Clicked**: ${result.text}" else ""}
            |
            | $summary
        """.trimMargin())
    }

    /** 输入文本 */
    private suspend fun handleType(args: Map<String, Any>, callId: String): ToolResult {
        val selector = getRequiredArgument(args, "selector")
        val text = getRequiredArgument(args, "text")

        val script = BrowserActionScripts.TYPE_TEXT.format(escapeJs(selector), escapeJs(text))
        val raw = webViewController.evaluateJs(script)
        val result = parseActionResult(raw)

        return if (result.success) {
            ToolResult(callId, true, output = "Text input successful")
        } else {
            ToolResult(callId, false, error = result.error ?: "Type failed")
        }
    }

    /** 获取页面文本 */
    private suspend fun handleGetText(callId: String): ToolResult {
        val text = pageExtractor.extractVisibleText()
        val title = pageExtractor.getTitle()
        return ToolResult(callId, true, output = """
            | ## $title
            |
            | $text
        """.trimMargin().take(30_000))
    }

    /** 获取页面 HTML */
    private suspend fun handleGetHtml(callId: String): ToolResult {
        val html = pageExtractor.extractHtml()
        return ToolResult(callId, true, output = html.take(50_000))
    }

    /** 截图 */
    private suspend fun handleScreenshot(callId: String): ToolResult {
        val bitmap = webViewController.screenshot()
        if (bitmap == null) {
            return ToolResult(callId, false, error = "Screenshot failed")
        }
        // 保存到缓存目录（用 context.getExternalCacheDir 兼容 Android 11+）
        val cacheDir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            ), "HippyAgent/browser_screenshots"
        ).also { it.mkdirs() }
        cacheDir.mkdirs()
        val file = java.io.File(cacheDir, "screenshot_${System.currentTimeMillis()}.png")
        file.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
        }
        return ToolResult(callId, true, output = "Screenshot saved", media = listOf(file.absolutePath))
    }

    /** 滚动 */
    private suspend fun handleScroll(args: Map<String, Any>, callId: String): ToolResult {
        val direction = getOptionalArgument(args, "direction", "down") ?: "down"
        val amountStr = getOptionalArgument(args, "amount", "500") ?: "500"
        val amount = amountStr.toIntOrNull() ?: 500

        val script = BrowserActionScripts.SCROLL.format(escapeJs(direction), amount)
        webViewController.evaluateJs(script)
        delay(300)

        val text = pageExtractor.extractVisibleText()
        return ToolResult(callId, true, output = text.take(3000))
    }

    /** 获取可交互元素 */
    private suspend fun handleGetInteractable(callId: String): ToolResult {
        val elements = pageExtractor.getInteractableElements()
        if (elements.isEmpty()) {
            return ToolResult(callId, true, output = "No interactable elements found on this page")
        }
        val sb = StringBuilder("## Interactable Elements (${elements.size})\n\n")
        sb.appendLine("| Index | Tag | Text | ID | Position |")
        sb.appendLine("|-------|-----|------|----|----------|")
        elements.forEach { el ->
            val pos = el.rect?.let { "(${it.x.toInt()},${it.y.toInt()})" } ?: ""
            sb.appendLine("| ${el.index} | ${el.tag} | ${el.text.take(40)} | ${el.id.take(20)} | $pos |")
        }
        sb.appendLine("\n使用 browser_click(index=N) 点击对应元素。")
        return ToolResult(callId, true, output = sb.toString())
    }

    /** 返回 */
    private suspend fun handleBack(callId: String): ToolResult {
        val success = webViewController.goBack()
        if (!success) return ToolResult(callId, false, error = "No previous page")
        delay(500)
        val summary = pageExtractor.formatPageSummary(maxLength = 2000)
        return ToolResult(callId, true, output = summary)
    }

    /** 前进 */
    private suspend fun handleForward(callId: String): ToolResult {
        val success = webViewController.goForward()
        if (!success) return ToolResult(callId, false, error = "No next page")
        delay(500)
        val summary = pageExtractor.formatPageSummary(maxLength = 2000)
        return ToolResult(callId, true, output = summary)
    }

    /** 等待元素 */
    private suspend fun handleWait(args: Map<String, Any>, callId: String): ToolResult {
        val selector = getRequiredArgument(args, "selector")
        val timeoutStr = getOptionalArgument(args, "timeout", "5000") ?: "5000"
        val timeout = timeoutStr.toIntOrNull() ?: 5000

        val script = BrowserActionScripts.WAIT_ELEMENT.format(escapeJs(selector), timeout)
        val raw = webViewController.evaluateJs(script)
        val result = parseActionResult(raw)

        return if (result.success) {
            ToolResult(callId, true, output = "Element found: $selector")
        } else {
            ToolResult(callId, false, error = "Element not found within ${timeout}ms: $selector")
        }
    }

    /** 执行任意 JS */
    private suspend fun handleExecute(args: Map<String, Any>, callId: String): ToolResult {
        val script = getRequiredArgument(args, "script")
        val result = webViewController.evaluateJs(script)
        return ToolResult(callId, true, output = result ?: "(no return value)")
    }

    /** 关闭浏览器 */
    private suspend fun handleClose(callId: String): ToolResult {
        webViewController.destroy()
        return ToolResult(callId, true, output = "Browser closed")
    }

    /** JS 字符串转义（防止注入） */
    private fun escapeJs(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /** 解析 JS 返回值 */
    private fun parseActionResult(raw: String?): ActionResult {
        if (raw.isNullOrBlank()) return ActionResult(false, error = "No response from page")
        return try {
            val element = Json.parseToJsonElement(raw).jsonObject
            val success = element["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val error = element["error"]?.jsonPrimitive?.content
            val text = element["text"]?.jsonPrimitive?.content
            ActionResult(success = success, error = error, text = text)
        } catch (e: Exception) {
            ActionResult(false, error = "Parse error: ${e.message}")
        }
    }
}
