package com.lin.hippyagent.core.tools.builtin

import android.content.Context
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.model.sharedOkHttpClient
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.util.Base64

class ImageGenerateTool(
    private val context: Context,
    private val modelProviderStore: ModelProviderStore
) : Tool() {
    companion object {
        private val urlRegex = Regex("""https?://[^\s"'<>]+\.(?:png|jpg|jpeg|webp|gif)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
        private val markdownImageRegex = Regex("""!\[.*?\]\((https?://[^\)]+)\)""")
    }


    override val definition = ToolDefinition(
        name = "image_generate",
        description = "根据文本描述生成图片。支持多种生图模型，默认使用 OpenRouter 的生图模型。",
        parameters = mapOf(
            "prompt" to ToolParameter(
                name = "prompt",
                type = "string",
                description = "图片内容的详细描述，建议使用英文以获得更好效果",
                required = true
            ),
            "model" to ToolParameter(
                name = "model",
                type = "string",
                description = "生图模型，默认 google/gemini-2.5-flash-image。可选: openai/gpt-5-image-mini, bytedance-seed/seedream-4.5 等",
                required = false,
                defaultValue = "google/gemini-2.5-flash-image"
            ),
            "aspect_ratio" to ToolParameter(
                name = "aspect_ratio",
                type = "string",
                description = "图片比例: landscape(横屏), square(方形), portrait(竖屏)",
                required = false,
                defaultValue = "square"
            )
        ),
        isAndroidSpecific = true
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val callId = args["callId"] as? String ?: ""
        val prompt = getRequiredArgument(args, "prompt")
        val model = getOptionalArgument(args, "model", "google/gemini-2.5-flash-image") ?: "google/gemini-2.5-flash-image"
        val aspectRatio = getOptionalArgument(args, "aspect_ratio", "square") ?: "square"

        val apiKey = findOpenRouterApiKey()
            ?: return ToolResult(
                callId = callId,
                success = false,
                error = "未配置 OpenRouter API Key。请在模型设置中添加 OpenRouter 提供商并填入 API Key。"
            )

        return try {
            val imageUrl = callImageGenerationApi(apiKey, model, prompt, aspectRatio)
            val localPath = downloadAndSaveImage(imageUrl, ctx.agentId)

            ToolResult(
                callId = callId,
                success = true,
                output = "图片已生成并保存: $localPath",
                forLLM = "图片已生成。路径: $localPath\n请在回复中包含 [附件: $localPath] 以便用户看到图片。你也可以对图片进行描述。",
                media = listOf(localPath)
            )
        } catch (e: Exception) {
            Timber.e(e, "Image generation failed")
            ToolResult(
                callId = callId,
                success = false,
                error = "图片生成失败: ${e.message}"
            )
        }
    }

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        return execute(ToolContext(), arguments)
    }

    private suspend fun findOpenRouterApiKey(): String? {
        val providers = modelProviderStore.providers.first()
        val openRouter = providers.find { it.id == "openrouter" }
        return openRouter?.apiKey?.takeIf { it.isNotBlank() }
    }

    private suspend fun callImageGenerationApi(
        apiKey: String,
        model: String,
        prompt: String,
        aspectRatio: String
    ): String = withContext(Dispatchers.IO) {
        val aspectRatioInstruction = when (aspectRatio) {
            "landscape" -> "Generate a landscape (16:9) image."
            "portrait" -> "Generate a portrait (9:16) image."
            else -> "Generate a square image."
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "$prompt\n\n$aspectRatioInstruction")
                })
            })
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://hippyagent.app")
            .addHeader("X-Title", "HippyAgent")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = sharedOkHttpClient.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty response from OpenRouter")

        if (!response.isSuccessful) {
            throw RuntimeException("OpenRouter API error (${response.code}): $responseBody")
        }

        val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
        val choices = jsonResponse["choices"]?.jsonArray
        if (choices.isNullOrEmpty()) {
            throw RuntimeException("No choices in response: $responseBody")
        }

        val message = choices[0].jsonObject["message"]?.jsonObject
            ?: throw RuntimeException("No message in response")

        val content = message["content"]
        return@withContext extractImageUrlFromContent(content)
    }

    private fun extractImageUrlFromContent(content: JsonElement?): String {
        if (content == null) {
            throw RuntimeException("No content in response")
        }

        if (content is JsonArray) {
            for (part in content) {
                val obj = part.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content
                if (type == "image_url") {
                    val url = obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                    if (!url.isNullOrBlank()) return url
                }
            }
        }

        if (content is JsonPrimitive) {
            val text = content.content
            val urlMatch = urlRegex.find(text)
            if (urlMatch != null) return urlMatch.value

            val mdMatch = markdownImageRegex.find(text)
            if (mdMatch != null) return mdMatch.groupValues[1]
        }

        throw RuntimeException("Could not extract image URL from response content: $content")
    }

    private suspend fun downloadAndSaveImage(imageUrl: String, agentId: String): String =
        withContext(Dispatchers.IO) {
            val mediaDir = File(
                File(context.filesDir, "agents"),
                if (agentId.isNotBlank()) "$agentId/media" else "shared/media"
            ).also { it.mkdirs() }

            val timestamp = System.currentTimeMillis()
            val file = File(mediaDir, "image_$timestamp.png")

            if (imageUrl.startsWith("data:")) {
                val base64Data = imageUrl.substringAfter("base64,")
                val bytes = Base64.getDecoder().decode(base64Data)
                file.writeBytes(bytes)
            } else {
                val request = Request.Builder().url(imageUrl).build()
                val response = sharedOkHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw RuntimeException("Failed to download image: ${response.code}")
                }
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw RuntimeException("Empty response body when downloading image")
            }

            file.absolutePath
        }
}
