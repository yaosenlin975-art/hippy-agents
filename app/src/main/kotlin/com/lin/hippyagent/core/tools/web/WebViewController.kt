package com.lin.hippyagent.core.tools.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * WebView 实例管理器 — 管理 WebView 生命周期与导航
 *
 * 参考: OpenClaw agent-browser/browser.ts BrowserManager 类
 * 复用现有 WebFetchTool.kt 的 WebView 初始化模式
 * (core/tools/web/WebFetchTool.kt:108-145)
 */
class WebViewController(
    private val context: Context
) {
    private var webView: WebView? = null
    private val mutex = Mutex()

    /** 导航结果 */
    data class NavigationResult(
        val url: String = "",
        val title: String = "",
        val success: Boolean,
        val error: String? = null
    )

    /** 创建/获取 WebView 实例（懒初始化） */
    suspend fun getOrCreate(): WebView = mutex.withLock {
        webView ?: createWebView().also { webView = it }
    }

    /** 导航到 URL */
    suspend fun navigate(url: String): NavigationResult {
        val wv = getOrCreate()
        val deferred = CompletableDeferred<NavigationResult>()

        withContext(Dispatchers.Main) {
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    deferred.complete(
                        NavigationResult(
                            url = pageUrl ?: url,
                            title = wv.title ?: "",
                            success = true
                        )
                    )
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    deferred.complete(
                        NavigationResult(
                            success = false,
                            error = "WebView error ($errorCode): $description"
                        )
                    )
                }
            }
            wv.loadUrl(url)
        }

        return deferred.await()
    }

    /** 执行 JS 并返回结果 */
    suspend fun evaluateJs(script: String): String? {
        val wv = getOrCreate()
        val deferred = CompletableDeferred<String?>()

        withContext(Dispatchers.Main) {
            wv.evaluateJavascript(script) { result ->
                deferred.complete(parseJsResult(result))
            }
        }

        return deferred.await()
    }

    /** 截图 */
    suspend fun screenshot(): Bitmap? = withContext(Dispatchers.Main) {
        val wv = getOrCreate()
        val width = wv.width.coerceAtLeast(1080)
        val height = wv.height.coerceAtLeast(1920)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        wv.draw(canvas)
        bitmap
    }

    /** 返回 */
    suspend fun goBack(): Boolean {
        val wv = getOrCreate()
        return if (wv.canGoBack()) {
            withContext(Dispatchers.Main) { wv.goBack() }
            true
        } else false
    }

    /** 前进 */
    suspend fun goForward(): Boolean {
        val wv = getOrCreate()
        return if (wv.canGoForward()) {
            withContext(Dispatchers.Main) { wv.goForward() }
            true
        } else false
    }

    /** 关闭并销毁 WebView — 线程安全 */
    suspend fun destroy() {
        mutex.withLock {
            webView?.let { wv ->
                wv.stopLoading()
                wv.destroy()
            }
            webView = null
        }
    }

    private fun createWebView(): WebView {
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.settings.userAgentString = MOBILE_UA
        wv.layoutParams = ViewGroup.LayoutParams(1, 1)
        return wv
    }

    private fun parseJsResult(result: String?): String? {
        if (result.isNullOrBlank() || result == "null" || result == "undefined") return null
        return result
            .trimStart('"')
            .trimEnd('"')
            .replace("\\n", "\n")
            .replace("\\t", " ")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
