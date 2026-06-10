package com.lin.hippyagent.core.agent.task

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import timber.log.Timber

class TaskPlanningException(message: String, cause: Throwable? = null) : Exception(message, cause)

class TaskPlanner(
    modelClient: ModelClient,
    modelName: String,
    /** 同步兜底模型名 — 当上层未注入有效 modelName 时使用，必须由 AppModule 注入，不允许硬编码 */
    private val fallbackModelName: String = ""
) {
    @Volatile
    var modelClient: ModelClient = modelClient
        private set

    @Volatile
    var modelName: String = modelName
        private set

    @Synchronized
    fun configure(modelClient: ModelClient, modelName: String) {
        this.modelClient = modelClient
        this.modelName = modelName
    }

    suspend fun planTask(userMessage: String): List<TaskStep> = withContext(Dispatchers.IO) {
        val trimmed = userMessage.trim()
        if (trimmed.isEmpty()) {
            throw TaskPlanningException("用户输入为空,无法规划任务")
        }

        // modelName 为空时降级到 fallbackModelName（由 AppModule 注入的 default provider default model）
        val effectiveModel = if (modelName.isBlank()) fallbackModelName else modelName
        if (effectiveModel.isBlank()) {
            throw TaskPlanningException("未配置任何模型（modelName/fallbackModelName 都为空）")
        }

        runCatching {
            val request = ModelCallRequest(
                model = effectiveModel,
                messages = listOf(
                    ModelMessage(role = "system", content = PLANNING_SYSTEM_PROMPT),
                    ModelMessage(role = "user", content = trimmed.take(MAX_USER_LEN))
                ),
                temperature = 0.2f,
                maxTokens = PLAN_MAX_TOKENS,
                stream = false
            )
            val response = modelClient.chatCompletion(request)
            val content = response.choices.firstOrNull()?.message?.content
                ?: throw TaskPlanningException("LLM 返回内容为空")
            parseSteps(content)
        }.getOrElse { e ->
            Timber.e(e, "TaskPlanner: planTask failed")
            throw if (e is TaskPlanningException) e else TaskPlanningException("任务拆解失败: ${e.message}", e)
        }
    }

    suspend fun planTaskWithFallback(userMessage: String): List<TaskStep> {
        return runCatching { planTask(userMessage) }.getOrElse { e ->
            Timber.w(e, "TaskPlanner: planner failed, fallback to single step")
            listOf(buildSingleFallbackStep(userMessage))
        }
    }

    private fun parseSteps(content: String): List<TaskStep> {
        val jsonStr = extractJsonArray(content)
            ?: throw TaskPlanningException("LLM 输出非 JSON 数组")
        val arr = runCatching { JSONArray(jsonStr) }.getOrElse { e ->
            throw TaskPlanningException("JSON 解析失败: ${e.message}", e)
        }
        if (arr.length() == 0) {
            throw TaskPlanningException("LLM 返回了空步骤列表")
        }
        if (arr.length() > MAX_STEPS) {
            throw TaskPlanningException("步骤数量超过上限($MAX_STEPS)")
        }
        return (0 until arr.length()).map { i ->
            val obj = arr.optJSONObject(i) ?: throw TaskPlanningException("步骤格式错误: 索引 $i")
            val description = obj.optString("description").trim()
            if (description.isEmpty()) {
                throw TaskPlanningException("步骤描述为空: 索引 $i")
            }
            val requiresApproval = obj.optBoolean("requiresApproval", false)
            val payload = obj.optJSONObject("payload")?.toString() ?: EMPTY_JSON
            TaskStep(
                description = description,
                requiresApproval = requiresApproval,
                payload = payload,
                status = StepStatus.PENDING
            )
        }
    }

    private fun extractJsonArray(content: String): String? {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return content.substring(start, end + 1)
    }

    private fun buildSingleFallbackStep(userMessage: String): TaskStep = TaskStep(
        description = userMessage.trim().take(FALLBACK_DESCRIPTION_MAX_LEN),
        requiresApproval = false,
        payload = EMPTY_JSON,
        status = StepStatus.PENDING
    )

    companion object {
        private const val MAX_USER_LEN = 2000
        private const val PLAN_MAX_TOKENS = 1024
        private const val MAX_STEPS = 8
        private const val EMPTY_JSON = "{}"
        private const val FALLBACK_DESCRIPTION_MAX_LEN = 500
        private const val PLANNING_SYSTEM_PROMPT = """你是 HippyAgent 的任务规划助手,负责将用户需求拆解为可独立执行的步骤。

输出要求:
1. 只返回 JSON 数组,不要包含任何解释、Markdown 代码块或额外文字
2. 每个元素包含 description(必填,1 句话描述该步骤做什么)
3. 可选字段:requiresApproval(布尔,默认 false)、payload(对象,默认 {})
4. 步骤数量最多 8 个;若需求简单,直接返回 1 个步骤即可
5. description 必须使用中文

示例: [{"description":"查询北京今天的天气","requiresApproval":false}]"""
    }
}
