package com.lin.hippyagent.core.scheduler

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ParseMethod { LLM, RULE, FALLBACK }

data class ScheduleParseResult(
    val success: Boolean,
    val cron: String? = null,
    val isoTimestamp: String? = null,
    val humanReadable: String? = null,
    val nextFireTime: Long? = null,
    val ambiguityCandidates: List<ScheduleParseResult> = emptyList(),
    val errorMessage: String? = null,
    val parseMethod: ParseMethod = ParseMethod.LLM,
    val isOneShot: Boolean = false,
    val delayMs: Long? = null,
    val rawText: String = ""
)

class NaturalLanguageScheduleParser(
    private val llmClient: ModelClient,
    private val modelName: String
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun parse(nlText: String): ScheduleParseResult {
        val text = nlText.trim()
        if (text.isEmpty()) {
            return ScheduleParseResult(
                success = false,
                errorMessage = "输入为空",
                parseMethod = ParseMethod.LLM,
                rawText = nlText
            )
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val systemPrompt = buildSystemPrompt()
                val userPrompt = buildUserPrompt(text)

                val request = ModelCallRequest(
                    model = modelName,
                    messages = listOf(
                        ModelMessage(role = "system", content = systemPrompt),
                        ModelMessage(role = "user", content = userPrompt)
                    ),
                    temperature = 0.1f,
                    maxTokens = 400,
                    stream = false
                )

                val response = llmClient.chatCompletion(request)
                val content = response.choices.firstOrNull()?.message?.content
                    ?: throw IllegalStateException("LLM 返回内容为空")
                parseLlmResponse(content, text)
            }.getOrElse { e ->
                Timber.w(e, "NaturalLanguageScheduleParser: LLM parse failed")
                ScheduleParseResult(
                    success = false,
                    errorMessage = "LLM 解析失败: ${e.message?.take(120)}",
                    parseMethod = ParseMethod.LLM,
                    rawText = nlText
                )
            }
        }
    }

    private fun buildSystemPrompt(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        val weekday = arrayOf("日", "一", "二", "三", "四", "五", "六")[cal.get(Calendar.DAY_OF_WEEK) - 1]

        return """
你是定时任务解析器。把用户输入的中文/英文自然语言转成 cron 表达式（5 字段：分 时 日 月 周）和 ISO 8601 时间戳。

当前时间：$now (周$weekday)
时区：Asia/Shanghai

输出严格的 JSON（不要加 markdown 包裹）：
{
  "cron": "0 15 * * *",
  "isoTimestamp": "2026-06-03T15:00:00+08:00",
  "humanReadable": "明天下午 3:00 触发",
  "nextFireTime": 1717994400000,
  "ambiguity": false,
  "candidates": []
}

字段说明：
- cron: 5 字段 cron，标准语法
- isoTimestamp: 下次触发的 ISO 8601 时间戳
- humanReadable: 中文人类可读描述
- nextFireTime: epoch ms
- ambiguity: true 表示输入有歧义（如"周一"可能是本周/下周）
- candidates: 歧义时给出 2 个候选，每个候选结构同顶层

规则：
- "明天下午3点" → 当天后一天的 15:00
- "每天8点" → cron "0 8 * * *"
- "每周一9点" → cron "0 9 * * 1"
- "X 分钟后" → 算成一次性，cron 留空 "" 或填一个对应的循环 cron（如果有）
- 解析失败时返回 {"error": "..."}
        """.trimIndent()
    }

    private fun buildUserPrompt(text: String): String = text.take(200)

    private fun parseLlmResponse(content: String, originalText: String): ScheduleParseResult {
        val jsonStr = extractJsonObject(content)
            ?: return ScheduleParseResult(
                success = false,
                errorMessage = "LLM 输出非 JSON",
                parseMethod = ParseMethod.LLM,
                rawText = originalText
            )

        val obj = runCatching { json.parseToJsonElement(jsonStr).jsonObject }.getOrNull()
            ?: return ScheduleParseResult(
                success = false,
                errorMessage = "LLM JSON 解析失败",
                parseMethod = ParseMethod.LLM,
                rawText = originalText
            )

        if (obj["error"] != null) {
            return ScheduleParseResult(
                success = false,
                errorMessage = obj["error"]?.jsonPrimitive?.contentOrNull ?: "未知错误",
                parseMethod = ParseMethod.LLM,
                rawText = originalText
            )
        }

        val ambiguity = obj["ambiguity"]?.jsonPrimitive?.booleanOrNull ?: false
        val cron = obj["cron"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val iso = obj["isoTimestamp"]?.jsonPrimitive?.contentOrNull
        val human = obj["humanReadable"]?.jsonPrimitive?.contentOrNull
        val nextMs = obj["nextFireTime"]?.jsonPrimitive?.longOrNull

        if (cron.isEmpty() && nextMs == null && !ambiguity) {
            return ScheduleParseResult(
                success = false,
                errorMessage = "LLM 未生成有效时间",
                parseMethod = ParseMethod.LLM,
                rawText = originalText
            )
        }

        if (ambiguity) {
            val candidates = parseCandidates(obj["candidates"], originalText)
            if (candidates.isNotEmpty()) {
                return ScheduleParseResult(
                    success = true,
                    cron = candidates.firstOrNull()?.cron,
                    isoTimestamp = candidates.firstOrNull()?.isoTimestamp,
                    humanReadable = "请选择触发时间",
                    nextFireTime = candidates.firstOrNull()?.nextFireTime,
                    ambiguityCandidates = candidates,
                    parseMethod = ParseMethod.LLM,
                    rawText = originalText
                )
            }
        }

        return ScheduleParseResult(
            success = true,
            cron = cron.takeIf { it.isNotEmpty() },
            isoTimestamp = iso,
            humanReadable = human ?: originalText,
            nextFireTime = nextMs,
            parseMethod = ParseMethod.LLM,
            rawText = originalText
        )
    }

    private fun parseCandidates(element: kotlinx.serialization.json.JsonElement?, originalText: String): List<ScheduleParseResult> {
        val arr = (element as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val cron = obj["cron"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val iso = obj["isoTimestamp"]?.jsonPrimitive?.contentOrNull
            val human = obj["humanReadable"]?.jsonPrimitive?.contentOrNull
            val nextMs = obj["nextFireTime"]?.jsonPrimitive?.longOrNull
            if (cron.isEmpty() && nextMs == null) return@mapNotNull null
            ScheduleParseResult(
                success = true,
                cron = cron.takeIf { it.isNotEmpty() },
                isoTimestamp = iso,
                humanReadable = human,
                nextFireTime = nextMs,
                parseMethod = ParseMethod.LLM,
                rawText = originalText
            )
        }
    }

    private fun extractJsonObject(content: String): String? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return content.substring(start, end + 1)
    }
}
