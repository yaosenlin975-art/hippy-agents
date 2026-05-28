package com.lin.hippyagent.core.tools.web

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class WebFetchTool(private val context: Context) : Tool() {

    override val definition = ToolDefinition(
        name = "web_fetch",
        description = "访问URL获取网页内容。默认使用轻量HTTP请求（速度快），当render_js=true时使用WebView渲染（支持JavaScript动态页面）。适合获取网页文本、API数据、文档内容。",
        parameters = mapOf(
            "url" to ToolParameter("url", "string", "目标URL", true),
            "render_js" to ToolParameter("render_js", "boolean", "是否需要JS渲染（SPA/动态页面设为true），默认false", false, "false"),
            "extract_links" to ToolParameter("extract_links", "boolean", "是否提取页面中的链接列表，默认false", false, "false")
        )
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val url = getRequiredArgument(arguments, "url")
        val renderJs = getOptionalArgument(arguments, "render_js", "false")?.toBooleanStrictOrNull() ?: false
        val extractLinks = getOptionalArgument(arguments, "extract_links", "false")?.toBooleanStrictOrNull() ?: false

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult(callId, false, error = "URL must start with http:// or https://")
        }

        return try {
            if (renderJs) {
                fetchWithWebView(url, extractLinks, callId)
            } else {
                fetchWithOkHttp(url, extractLinks, callId)
            }
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to fetch $url: ${e.message}")
        }
    }

    private suspend fun fetchWithOkHttp(url: String, extractLinks: Boolean, callId: String): ToolResult {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (body == null) {
                return@withContext ToolResult(callId, false, error = "Empty response (HTTP ${response.code})")
            }

            if (!response.isSuccessful) {
                return@withContext ToolResult(callId, false, error = "HTTP ${response.code}: ${response.message}")
            }

            val contentType = response.header("Content-Type", "") ?: ""
            val result = when {
                contentType.contains("json", ignoreCase = true) -> body
                contentType.contains("text/plain", ignoreCase = true) -> body
                else -> {
                    val text = extractTextFromHtml(body)
                    if (extractLinks) {
                        val links = extractLinksFromHtml(body)
                        if (links.isNotEmpty()) {
                            "$text\n\n--- 页面链接 ---\n${links.joinToString("\n")}"
                        } else {
                            text
                        }
                    } else {
                        text
                    }
                }
            }

            ToolResult(callId, true, output = result)
        }
    }

    private suspend fun fetchWithWebView(url: String, extractLinks: Boolean, callId: String): ToolResult {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<ToolResult> { cont ->
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = DESKTOP_UA
                webView.settings.loadWithOverviewMode = true
                webView.settings.useWideViewPort = true

                var timeoutRunnable: Runnable? = null
                val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }

                        view?.evaluateJavascript(
                            if (extractLinks) JS_EXTRACT_TEXT_AND_LINKS else JS_EXTRACT_TEXT
                        ) { result ->
                            val output = parseJsResult(result)
                            webView.destroy()
                            if (cont.isActive) {
                                cont.resume(ToolResult(callId, true, output = output))
                            }
                        }
                    }

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
                        webView.destroy()
                        if (cont.isActive) {
                            cont.resume(ToolResult(callId, false, error = "WebView error: $description"))
                        }
                    }
                }

                timeoutRunnable = Runnable {
                    webView.evaluateJavascript(
                        if (extractLinks) JS_EXTRACT_TEXT_AND_LINKS else JS_EXTRACT_TEXT
                    ) { result ->
                        val output = parseJsResult(result)
                        webView.destroy()
                        if (cont.isActive) {
                            cont.resume(ToolResult(callId, true, output = output))
                        }
                    }
                }
                timeoutHandler.postDelayed(timeoutRunnable!!, 20_000)

                webView.loadUrl(url)

                cont.invokeOnCancellation {
                    timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
                    webView.destroy()
                }
            }
        }
    }

    private fun extractTextFromHtml(html: String): String {
        var text = html
            .replace(SCRIPT_STYLE_REGEX, "")
            .replace(HTML_TAG_REGEX, " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        if (text.length > MAX_OUTPUT) {
            text = text.substring(0, MAX_OUTPUT) + "\n\n[... 内容过长，已截断，共 ${text.length} 字符]"
        }
        return text
    }

    private fun extractLinksFromHtml(html: String): List<String> {
        val links = mutableListOf<String>()
        val matcher = LINK_PATTERN.matcher(html)
        var count = 0
        while (matcher.find() && count < 30) {
            val href = matcher.group(1) ?: continue
            val text = matcher.group(2)?.trim() ?: ""
            if (href.startsWith("http") && text.length > 2) {
                links.add("- $text → $href")
                count++
            }
        }
        return links
    }

    private fun parseJsResult(result: String?): String {
        if (result.isNullOrBlank() || result == "null" || result == "undefined") {
            return "[页面无文本内容]"
        }
        var text = result
            .trimStart('"')
            .trimEnd('"')
            .replace("\\n", "\n")
            .replace("\\t", " ")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

        if (text.length > MAX_OUTPUT) {
            text = text.substring(0, MAX_OUTPUT) + "\n\n[... 内容过长，已截断，共 ${text.length} 字符]"
        }
        return text
    }

    companion object {
        private const val MAX_OUTPUT = 50_000
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val HTML_TAG_REGEX = Regex("<[^>]+>")
        private val SCRIPT_STYLE_REGEX = Regex("<(script|style)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val LINK_PATTERN = java.util.regex.Pattern.compile("<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>([^<]+)</a>", java.util.regex.Pattern.DOTALL)

        private const val JS_EXTRACT_TEXT = """
            (function() {
                var body = document.body;
                if (!body) return '';
                var clone = body.cloneNode(true);
                var scripts = clone.querySelectorAll('script, style, noscript');
                scripts.forEach(function(s) { s.remove(); });
                return clone.innerText || '';
            })()
        """

        private const val JS_EXTRACT_TEXT_AND_LINKS = """
            (function() {
                var body = document.body;
                if (!body) return '';
                var clone = body.cloneNode(true);
                var scripts = clone.querySelectorAll('script, style, noscript');
                scripts.forEach(function(s) { s.remove(); });
                var text = clone.innerText || '';
                var links = Array.from(document.querySelectorAll('a[href]'))
                    .filter(function(a) { return a.href.startsWith('http'); })
                    .slice(0, 30)
                    .map(function(a) { return '- ' + (a.textContent.trim() || a.href) + ' → ' + a.href; })
                    .join('\n');
                return text + '\n\n--- 页面链接 ---\n' + links;
            })()
        """
    }
}
