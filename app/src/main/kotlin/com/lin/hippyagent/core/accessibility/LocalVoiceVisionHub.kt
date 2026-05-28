package com.lin.hippyagent.core.accessibility

import android.util.Base64
import com.lin.hippyagent.core.model.ContentBlock
import com.lin.hippyagent.core.model.ImageUrlDetail
import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

enum class VoiceRoute {
    AGENT_TASK,
    FORWARD_TEXT,
    VISUAL_QUERY
}

@Serializable
data class JsonCommand(
    val action: String = "",
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class VoiceVlmResult(
    val reply: String = "",
    val directAction: String? = null,
    val jsonCommand: JsonCommand? = null
)

class LocalVoiceVisionHub {

    companion object {
        private val VISUAL_KEYWORDS = listOf("看到", "屏幕", "画面", "显示", "界面", "图片", "图标", "按钮", "颜色", "长什么样", "是什么样子", "能看到", "截图")
        private val TASK_KEYWORDS = listOf("帮我", "打开", "关闭", "点击", "滑动", "操作", "执行", "运行", "启动", "停止", "发送", "删除", "复制", "粘贴")
        private val JSON_BLOCK_REGEX = Regex("""```json\s*([\s\S]*?)```""")
        private val JSON_OBJECT_REGEX = Regex("""\{[\s\S]*\}""")
        private val VLM_VISUAL_PROMPT = """你是一个Android屏幕视觉分析助手。用户通过语音提问，请根据提供的屏幕截图回答问题。

如果用户的问题涉及操作指令，请输出JSON格式的命令：
{"action": "操作名称", "params": {"key": "value"}}

如果只是视觉查询，请用自然语言回答。"""
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun routeDecision(sttText: String, hasVisualMode: Boolean, hasCamera: Boolean): VoiceRoute {
        val isVisualQuery = VISUAL_KEYWORDS.any { sttText.contains(it) }
        val isTaskQuery = TASK_KEYWORDS.any { sttText.contains(it) }

        return when {
            isVisualQuery && hasVisualMode -> VoiceRoute.VISUAL_QUERY
            isTaskQuery -> VoiceRoute.AGENT_TASK
            else -> VoiceRoute.FORWARD_TEXT
        }
    }

    suspend fun processVisualQuery(
        sttText: String,
        frames: List<FrameData>,
        vlmProvider: VlmProvider
    ): VoiceVlmResult = withContext(Dispatchers.IO) {
        if (frames.isEmpty()) {
            Timber.w("No frames available for visual query")
            return@withContext VoiceVlmResult(reply = "当前没有可用的屏幕画面信息")
        }

        val latestFrame = frames.first()
        val base64 = Base64.encodeToString(latestFrame.jpegBytes, Base64.NO_WRAP)

        try {
            val request = ModelCallRequest(
                model = vlmProvider.modelName,
                messages = listOf(
                    ModelMessage(role = "system", content = VLM_VISUAL_PROMPT),
                    ModelMessage(
                        role = "user",
                        content = sttText,
                        contentBlocks = listOf(
                            ContentBlock.Text(sttText),
                            ContentBlock.ImageUrl(ImageUrlDetail(url = "data:image/jpeg;base64,$base64"))
                        )
                    )
                ),
                maxTokens = 2048
            )

            val response = vlmProvider.modelClient.chatCompletion(request)
            val content = response.choices?.firstOrNull()?.message?.content ?: ""

            val jsonCommand = extractJsonCommand(content)
            val directAction = jsonCommand?.action
            val cleanReply = stripJsonLikeBlocks(content)

            Timber.d("Visual query result: reply=%s, action=%s", cleanReply.take(50), directAction)
            VoiceVlmResult(reply = cleanReply, directAction = directAction, jsonCommand = jsonCommand)
        } catch (e: Exception) {
            Timber.w(e, "VLM visual query failed")
            VoiceVlmResult(reply = "视觉分析暂时不可用")
        }
    }

    fun processTextQuery(sttText: String): String {
        return sttText.trim()
    }

    fun extractJsonCommand(vlmOutput: String): JsonCommand? {
        val jsonStr = JSON_BLOCK_REGEX.find(vlmOutput)?.groupValues?.get(1)
            ?: JSON_OBJECT_REGEX.find(vlmOutput)?.value
            ?: return null

        return try {
            json.decodeFromString<JsonCommand>(jsonStr)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse JsonCommand from VLM output")
            null
        }
    }

    fun stripJsonLikeBlocks(text: String): String {
        var result = JSON_BLOCK_REGEX.replace(text) { match ->
            val inner = match.groupValues[1].trim()
            try {
                json.decodeFromString<JsonCommand>(inner)
                ""
            } catch (_: Exception) {
                match.value
            }
        }
        result = JSON_OBJECT_REGEX.replace(result) { match ->
            try {
                json.decodeFromString<JsonCommand>(match.value)
                ""
            } catch (_: Exception) {
                match.value
            }
        }
        return result.trim()
    }
}
