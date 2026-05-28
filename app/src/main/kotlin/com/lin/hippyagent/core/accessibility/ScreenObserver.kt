package com.lin.hippyagent.core.accessibility

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import com.lin.hippyagent.core.pool.ByteArrayOutputStreamPool
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ScreenshotAnalysis(
    val currentApp: String? = null,
    val currentPage: String? = null,
    val interactiveElements: List<ScreenshotElement> = emptyList(),
    val screenSummary: String? = null
)

@Serializable
data class ScreenshotElement(
    val type: String,
    val text: String? = null,
    val position: String? = null,
    val bounds: String? = null
)

class ScreenObserver(private val controller: AccessibilityController) {

    companion object {
        private const val TAG = "ScreenObserver"
        private const val VLM_PROMPT = """你是一个 Android 手机操控助手。请分析这张屏幕截图，返回以下 JSON 结构：
{
  "current_app": "应用名称",
  "current_page": "页面描述",
  "interactive_elements": [
    {"type": "button", "text": "发送", "position": "bottom-right", "bounds": "[x1,y1][x2,y2]"}
  ],
  "screen_summary": "当前页面描述..."
}"""
    }

    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = true }
    private val streamPool = ByteArrayOutputStreamPool(initialBufferSize = 128 * 1024, maxSize = 2)

    suspend fun observeScreenshot(request: ObserveRequest): ObserveResult {
        val service = PhoneControlAccessibilityService.instance
            ?: return ObserveResult(error = "AccessibilityService not running")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ObserveResult(error = "Screenshot requires Android 11+")
        }

        val screenshotBytes = takeScreenshot(service)
            ?: return ObserveResult(error = "Failed to take screenshot")

        val base64Image = Base64.encodeToString(screenshotBytes, Base64.NO_WRAP)

        val analysis = analyzeWithVLM(base64Image, service.getRootNode()?.packageName?.toString())
            ?: ScreenshotAnalysis(screenSummary = "Screenshot captured but VLM analysis unavailable")

        // Call observeNodes directly to avoid circular recursion through controller.observe()
        val nodeResult = controller.observeNodes(request)

        return ObserveResult(
            window = nodeResult.window,
            screenSize = nodeResult.screenSize,
            nodeTree = nodeResult.nodeTree,
            nodeCount = nodeResult.nodeCount,
            interactiveCount = nodeResult.interactiveCount,
            screenshotAnalysis = analysis,
            screenshotBase64 = base64Image.take(100) + "...(truncated)"
        )
    }

    suspend fun observeHybrid(request: ObserveRequest): ObserveResult {
        // Call observeNodes directly to avoid circular recursion through controller.observe()
        val nodeResult = controller.observeNodes(request)

        if (nodeResult.error != null) return nodeResult

        if ((nodeResult.interactiveCount ?: 0) < 5) {
            Log.d(TAG, "Interactive nodes < 5, supplementing with screenshot analysis")
            val screenshotResult = observeScreenshot(request)
            return screenshotResult.copy(
                nodeTree = nodeResult.nodeTree,
                nodeCount = nodeResult.nodeCount,
                interactiveCount = nodeResult.interactiveCount
            )
        }

        return nodeResult
    }

    @Suppress("DEPRECATION")
    private suspend fun takeScreenshot(service: PhoneControlAccessibilityService): ByteArray? {
        return captureViaMediaProjection(service)
    }

    private suspend fun captureViaMediaProjection(service: PhoneControlAccessibilityService): ByteArray? {
        return try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            service.getSystemService(android.view.WindowManager::class.java)
                .defaultDisplay.getMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels

            // 检查可用内存，避免 OOM
            val runtime = Runtime.getRuntime()
            val availableMemory = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
            val estimatedBitmapSize = width.toLong() * height.toLong() * 4 // ARGB_8888
            if (estimatedBitmapSize > availableMemory * 0.5) {
                Log.w(TAG, "Insufficient memory for screenshot: need ${estimatedBitmapSize / 1024 / 1024}MB, available ${availableMemory / 1024 / 1024}MB")
                return null
            }

            var image: Image? = null
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val handler = Handler(Looper.getMainLooper())

            val virtualDisplay = service.getSystemService(DisplayManager::class.java)
                .createVirtualDisplay(
                    "ScreenCapture",
                    width, height, metrics.densityDpi,
                    imageReader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    null, handler
                )

            kotlinx.coroutines.delay(500)

            image = imageReader.acquireLatestImage()
            val bytes = image?.let { img ->
                val planes = img.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                // 使用 RGB_565 替代 ARGB_8888，内存减半（不支持透明度，但截图不需要）
                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height,
                    Bitmap.Config.RGB_565
                )
                bitmap.copyPixelsFromBuffer(buffer)

                val stream = streamPool.acquire()
                val compressed: ByteArray
                try {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                    compressed = stream.toByteArray()
                } finally {
                    streamPool.release(stream)
                    bitmap.recycle()
                }
                compressed
            }

            virtualDisplay.release()
            imageReader.close()
            image?.close()

            bytes
        } catch (e: Exception) {
            Log.e(TAG, "captureViaMediaProjection failed", e)
            null
        }
    }

    private suspend fun analyzeWithVLM(base64Image: String, packageName: String?): ScreenshotAnalysis? {
        // VLM analysis requires a vision-capable model client
        // This will be integrated when vision support is added to ModelClient
        Log.d(TAG, "VLM analysis not yet integrated, packageName=$packageName")
        return ScreenshotAnalysis(
            currentApp = packageName,
            screenSummary = "Screenshot captured. VLM analysis pending integration with vision model."
        )
    }
}

