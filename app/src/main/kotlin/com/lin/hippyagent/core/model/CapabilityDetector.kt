package com.lin.hippyagent.core.model

import com.lin.hippyagent.core.pool.ByteArrayOutputStreamPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class CapabilityDetector(
    private val modelClient: ModelClient
) {
    companion object {
        private val visionRegex = Regex("(?i)(vision|vl|gemini|qwen-vl|gpt-4o|claude-3|gpt-5-image|flash-image)")
    }

    private val streamPool = ByteArrayOutputStreamPool(initialBufferSize = 4 * 1024, maxSize = 2)
    suspend fun detectVisionCapability(modelId: String): Boolean = withContext(Dispatchers.IO) {
        if (modelId.contains(visionRegex)) {
            Timber.d("Model $modelId detected as VISION by name heuristic")
            return@withContext true
        }

        try {
            val testImage = createTestImageBase64()
            val request = ModelCallRequest(
                model = modelId,
                messages = listOf(
                    ModelMessage(role = "user", content = "Describe this image in one word."),
                    ModelMessage(role = "user", content = "[image:$testImage]")
                ),
                maxTokens = 10
            )
            val response = modelClient.chatCompletion(request)
            val hasContent = !response.choices.isNullOrEmpty()
            Timber.d("Model $modelId VISION probe: $hasContent")
            hasContent
        } catch (e: Exception) {
            Timber.w(e, "Model $modelId VISION probe failed")
            false
        }
    }

    private fun createTestImageBase64(): String {
        val width = 4
        val height = 4
        val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        val bitmap = android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val stream = streamPool.acquire()
        try {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        } finally {
            streamPool.release(stream)
            bitmap.recycle()
        }
    }
}
