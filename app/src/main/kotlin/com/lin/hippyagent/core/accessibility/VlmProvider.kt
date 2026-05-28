package com.lin.hippyagent.core.accessibility

import android.graphics.Bitmap
import android.util.Base64
import com.lin.hippyagent.core.model.ContentBlock
import com.lin.hippyagent.core.model.ImageUrlDetail
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.pool.ByteArrayOutputStreamPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

@Serializable
data class VlmAnalysisResult(
    val pageType: String = "",
    val pageDescription: String = "",
    val elements: List<VlmElement> = emptyList(),
    val confidence: Float = 0f
)

@Serializable
data class VlmElement(
    val role: String = "",
    val description: String = "",
    val bounds: String? = null,
    val text: String? = null
)

class VlmProvider(
    internal val modelClient: ModelClient,
    internal val modelName: String
) {
    companion object {
        private const val VLM_SYSTEM_PROMPT = """你是一个Android屏幕分析专家。请分析这张手机截图。

屏幕尺寸：{width}x{height}
已知节点树信息（可能不完整）：
{nodeTreeHint}

请补充节点树中缺失的信息，输出JSON：
{
  "page_type": "页面类型(chat/feed/settings/game/...)",
  "page_description": "一句话描述当前页面",
  "elements": [
    {
      "role": "元素角色(button/input/tab/card/icon/text)",
      "description": "元素描述",
      "bounds": "[x1,y1][x2,y2]",
      "text": "元素文字(如有)"
    }
  ],
  "confidence": 0.8
}

注意：
1. 只补充节点树中缺失的交互元素，不要重复已有信息
2. bounds坐标必须是屏幕绝对像素坐标
3. 如果无法确定坐标，bounds设为null"""
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun analyze(
        screenshotBase64: String,
        screenSize: ScreenSize,
        nodeTreeHint: String? = null
    ): VlmAnalysisResult = withContext(Dispatchers.IO) {
        val prompt = VLM_SYSTEM_PROMPT
            .replace("{width}", screenSize.width.toString())
            .replace("{height}", screenSize.height.toString())
            .replace("{nodeTreeHint}", nodeTreeHint ?: "(无节点树信息)")

        try {
            val request = ModelCallRequest(
                model = modelName,
                messages = listOf(
                    ModelMessage(role = "system", content = prompt),
                    ModelMessage(
                        role = "user",
                        content = "请分析这张截图",
                        contentBlocks = listOf(
                            ContentBlock.Text("请分析这张截图"),
                            ContentBlock.ImageUrl(ImageUrlDetail(url = "data:image/jpeg;base64,$screenshotBase64"))
                        )
                    )
                ),
                maxTokens = 2048
            )

            val response = modelClient.chatCompletion(request)
            val content = response.choices?.firstOrNull()?.message?.content ?: ""
            parseVlmResponse(content)
        } catch (e: Exception) {
            Timber.w(e, "VLM analysis failed for model $modelName")
            VlmAnalysisResult(confidence = 0f)
        }
    }

    private fun parseVlmResponse(content: String): VlmAnalysisResult {
        return try {
            val jsonStr = extractJson(content)
            json.decodeFromString<VlmAnalysisResult>(jsonStr)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse VLM response")
            VlmAnalysisResult(pageDescription = content.take(200), confidence = 0.3f)
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) return text.substring(start, end + 1)
        return text
    }
}

class ScreenshotCapturer {

    private val streamPool = ByteArrayOutputStreamPool(initialBufferSize = 64 * 1024, maxSize = 2)

    suspend fun captureForVlm(service: PhoneControlAccessibilityService): Pair<String, ScreenSize>? {
        val screenshot = captureScaled(service, maxWidth = 480) ?: return null
        val base64 = Base64.encodeToString(screenshot, Base64.NO_WRAP)
        val screenSize = getScreenSize(service)
        return Pair(base64, screenSize)
    }

    private suspend fun captureScaled(service: PhoneControlAccessibilityService, maxWidth: Int): ByteArray? {
        return try {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            service.getSystemService(android.view.WindowManager::class.java)
                .defaultDisplay.getMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels

            val imageReader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            val virtualDisplay = service.getSystemService(android.hardware.display.DisplayManager::class.java)
                .createVirtualDisplay(
                    "VlmCapture",
                    width, height, metrics.densityDpi,
                    imageReader.surface,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    null, handler
                )

            kotlinx.coroutines.delay(300)

            val image = imageReader.acquireLatestImage()
            val bytes = image?.let { img ->
                val buffer = img.planes[0].buffer
                val pixelStride = img.planes[0].pixelStride
                val rowStride = img.planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.RGB_565)
                bitmap.copyPixelsFromBuffer(buffer)

                val scaleFactor = maxWidth.toFloat() / width
                val scaledWidth = (width * scaleFactor).toInt()
                val scaledHeight = (height * scaleFactor).toInt()
                val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

                val stream = streamPool.acquire()
                val compressed: ByteArray
                try {
                    scaled.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                    compressed = stream.toByteArray()
                } finally {
                    streamPool.release(stream)
                    bitmap.recycle()
                    if (scaled !== bitmap) scaled.recycle()
                }
                compressed
            }

            virtualDisplay.release()
            imageReader.close()
            image?.close()
            bytes
        } catch (e: Exception) {
            Timber.w(e, "captureScaled failed")
            null
        }
    }

    private fun getScreenSize(service: PhoneControlAccessibilityService): ScreenSize {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        service.getSystemService(android.view.WindowManager::class.java)
            .defaultDisplay.getMetrics(metrics)
        return ScreenSize(metrics.widthPixels, metrics.heightPixels)
    }
}
