package com.lin.hippyagent.core.tools.web

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 联网搜索工具 — 参考 picoclaw 的 Sogou 免费搜索实现
 * 无需 API Key，国内直接可用
 */
class WebSearchTool : Tool() {

    companion object {
        // 预编译 Regex，避免每次调用都重新编译
        private val HTML_TAG_REGEX = Regex("<[^>]+>")
        private val WHITESPACE_REGEX = Regex("\\s+")
    }

    override val definition = ToolDefinition(
        name = "web_search",
        description = "联网搜索，获取互联网上的最新信息。支持中英文搜索。",
        parameters = mapOf(
            "query" to ToolParameter(
                name = "query",
                type = "string",
                description = "搜索关键词",
                required = true
            ),
            "count" to ToolParameter(
                name = "count",
                type = "integer",
                description = "返回结果数量，默认5，最大10",
                required = false
            )
        )
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val sogouUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val query = getRequiredArgument(arguments, "query")
        val count = getOptionalArgument(arguments, "count", "5")?.toIntOrNull()?.coerceIn(1, 10) ?: 5

        return try {
            // 优先尝试搜狗搜索，失败时回退到 Bing 中文搜索
            var results = try {
                searchSogou(query, count)
            } catch (e: Exception) {
                emptyList()
            }

            if (results.isEmpty()) {
                results = try {
                    searchBing(query, count)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            if (results.isEmpty()) {
                ToolResult(callId = "", success = true, output = "未找到关于「$query」的搜索结果")
            } else {
                val formatted = buildString {
                    appendLine("搜索结果：$query")
                    appendLine()
                    results.forEachIndexed { index, result ->
                        appendLine("${index + 1}. ${result.title}")
                        appendLine("   ${result.url}")
                        if (result.snippet.isNotEmpty()) {
                            appendLine("   ${result.snippet}")
                        }
                        appendLine()
                    }
                }
                ToolResult(callId = "", success = true, output = formatted.trimEnd())
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName ?: "未知错误"
            ToolResult(callId = "", success = false, error = "搜索失败：$errorMsg")
        }
    }

    private fun searchSogou(query: String, count: Int): List<SearchResult> {
        val url = "https://wap.sogou.com/web/searchList.jsp?keyword=${java.net.URLEncoder.encode(query, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", sogouUserAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: return emptyList()

        val results = mutableListOf<SearchResult>()

        // 解析搜狗 WAP 页面的搜索结果
        val titlePattern = Pattern.compile("<a[^>]*href=\"([^\"]+)\"[^>]*>.*?<h3[^>]*>(.*?)</h3>", Pattern.DOTALL)
        val titleMatcher = titlePattern.matcher(html)

        val snippetPattern = Pattern.compile("<p[^>]*class=\"str_info\"[^>]*>(.*?)</p>", Pattern.DOTALL)
        val snippetMatcher = snippetPattern.matcher(html)

        val snippets = mutableListOf<String>()
        while (snippetMatcher.find()) {
            snippets.add(stripHtml(snippetMatcher.group(1) ?: ""))
        }

        var i = 0
        while (titleMatcher.find() && i < count) {
            val rawUrl = titleMatcher.group(1) ?: ""
            val title = stripHtml(titleMatcher.group(2) ?: "")
            val snippet = snippets.getOrElse(i) { "" }

            if (rawUrl.isNotEmpty() && title.isNotEmpty() && !rawUrl.contains("sogou.com")) {
                results.add(SearchResult(title = title, url = rawUrl, snippet = snippet))
                i++
            }
        }

        // 如果正则解析失败，尝试简单的链接提取
        if (results.isEmpty()) {
            val linkPattern = Pattern.compile("href=\"(https?://[^\"]+)\"[^>]*>([^<]+)<")
            val linkMatcher = linkPattern.matcher(html)
            while (linkMatcher.find() && i < count) {
                val linkUrl = linkMatcher.group(1) ?: ""
                val text = linkMatcher.group(2)?.trim() ?: ""
                if (text.length > 4 && !linkUrl.contains("sogou.com") && !linkUrl.contains("javascript")) {
                    results.add(SearchResult(title = text, url = linkUrl, snippet = ""))
                    i++
                }
            }
        }

        return results
    }

    /**
     * Bing 中文搜索 — 作为搜狗搜索的备用方案
     */
    private fun searchBing(query: String, count: Int): List<SearchResult> {
        val url = "https://cn.bing.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&setlang=zh-CN"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", sogouUserAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: return emptyList()

        val results = mutableListOf<SearchResult>()

        // Bing 搜索结果通常在 <li class="b_algo"> 块中
        val blockPattern = Pattern.compile("<li\\s+class=\"b_algo\"[^>]*>(.*?)</li>", Pattern.DOTALL)
        val blockMatcher = blockPattern.matcher(html)

        while (blockMatcher.find() && results.size < count) {
            val block = blockMatcher.group(1) ?: continue

            // 提取标题和链接
            val linkPattern = Pattern.compile("<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL)
            val linkMatcher = linkPattern.matcher(block)
            if (!linkMatcher.find()) continue

            val linkUrl = linkMatcher.group(1) ?: continue
            val title = stripHtml(linkMatcher.group(2) ?: "")
            if (title.isEmpty() || linkUrl.contains("bing.com")) continue

            // 提取摘要
            val snippetPattern = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL)
            val snippetMatcher = snippetPattern.matcher(block)
            val snippet = if (snippetMatcher.find()) stripHtml(snippetMatcher.group(1) ?: "") else ""

            results.add(SearchResult(title = title, url = linkUrl, snippet = snippet))
        }

        // 简单回退：提取所有外部链接
        if (results.isEmpty()) {
            val linkPattern = Pattern.compile("href=\"(https?://(?!cn\\.bing\\.com|microsoft\\.com)[^\"]+)\"[^>]*>([^<]{4,})<")
            val linkMatcher = linkPattern.matcher(html)
            while (linkMatcher.find() && results.size < count) {
                val linkUrl = linkMatcher.group(1) ?: ""
                val text = linkMatcher.group(2)?.trim() ?: ""
                if (text.length > 4 && !linkUrl.contains("bing.com") && !linkUrl.contains("javascript")) {
                    results.add(SearchResult(title = text, url = linkUrl, snippet = ""))
                }
            }
        }

        return results
    }

    private fun stripHtml(html: String): String {
        if (html.isEmpty()) return ""
        return html
            .replace(HTML_TAG_REGEX, "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String
    )
}

