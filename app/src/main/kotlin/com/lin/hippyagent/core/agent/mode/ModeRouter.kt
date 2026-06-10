package com.lin.hippyagent.core.agent.mode

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.skill.AgentMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ModeDecision(
    val mode: AgentMode,
    val reasoning: String? = null,
    val decisionTimeMs: Long,
    val useComplexModel: Boolean = false
)

class ModeRouter(
    modelClients: Map<String, ModelClient>,
    defaultModelName: String,
    @Suppress("unused") private val applicationScope: CoroutineScope? = null
) {
    private val modelClients: ConcurrentHashMap<String, ModelClient> =
        ConcurrentHashMap(modelClients)

    @Volatile
    var modelName: String = defaultModelName
        private set

    /**
     * 决策调用入口的 [ModelClient] — 兼容旧 API：单 modelClient 模式下保留访问器。
     * 新逻辑统一走 [clientFor]，按 modelName 选 client。
     */
    @Deprecated("Use clientFor(modelName) — 单一 modelClient 字段不能跨 provider 复用")
    val modelClient: ModelClient?
        get() = clientFor(modelName)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val cache: LinkedHashMap<String, ModeDecision> =
        object : LinkedHashMap<String, ModeDecision>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ModeDecision>): Boolean =
                size > CACHE_MAX_SIZE
        }

    @Synchronized
    fun configure(modelClients: Map<String, ModelClient>, defaultModelName: String) {
        this.modelClients.clear()
        this.modelClients.putAll(modelClients)
        this.modelName = defaultModelName
    }

    @Synchronized
    fun clearCache() {
        cache.clear()
    }

    /**
     * 按 modelName 解析 [ModelClient]：
     * 1. 全名查 → 2. provider/model 查 → 3. 剥前缀查 → 4. 按 providerId 前缀匹配 → 5. 第一个可用 client。
     * mode 决策的 fallback 不再硬编码 OpenAI baseUrl — 改用「第一个可用 client」，并对 modelName 同步剥前缀。
     */
    private fun clientFor(modelName: String, providerId: String? = null): ModelClient? {
        if (modelName.isBlank()) return modelClients.values.firstOrNull()
        val normalized = modelName.substringAfterLast('/')
        // 1. 全名精确匹配
        modelClients[modelName]?.let { return it }
        // 2. provider/model 组合匹配
        if (providerId != null) {
            modelClients["$providerId/$normalized"]?.let { return it }
            modelClients["$providerId/$modelName"]?.let { return it }
        }
        // 3. 剥前缀后匹配
        modelClients[normalized]?.let { return it }
        // 4. 按 providerId 前缀模糊匹配 (如 key="deepseek/deepseek-v4-flash")
        if (providerId != null) {
            modelClients.entries.firstOrNull { it.key.startsWith("$providerId/") }?.let { return it.value }
        }
        // 5. fallback 到第一个可用 client，并记录警告
        val fallback = modelClients.values.firstOrNull()
        if (fallback != null) {
            Timber.w("ModeRouter: no client for model=$modelName (provider=$providerId), fallback to first available client")
        }
        return fallback
    }

    suspend fun decideMode(
        userMessage: String,
        agentId: String? = null,
        overrideModelName: String? = null,
        overrideProviderId: String? = null,
        complexModelName: String? = null,
    ): ModeDecision {
        val trimmed = userMessage.trim()
        if (trimmed.isEmpty()) {
            return ModeDecision(
                mode = AgentMode.CHAT,
                reasoning = "输入为空,默认 Chat",
                decisionTimeMs = 0L
            )
        }

        val effectiveModel = overrideModelName?.takeIf { it.isNotBlank() } ?: modelName
        val key = buildCacheKey(agentId, effectiveModel, trimmed)
        getCached(key)?.let { return it.copy(decisionTimeMs = 0L) }

        val client = clientFor(effectiveModel, overrideProviderId)
        if (client == null) {
            Timber.w("ModeRouter: no modelClient available for $effectiveModel, fallback to CHAT")
            return ModeDecision(
                mode = AgentMode.CHAT,
                reasoning = "无可用 modelClient,默认 Chat",
                decisionTimeMs = 0L
            )
        }

        val start = System.currentTimeMillis()
        val decision = runLlmDecision(trimmed, effectiveModel, client, start, complexModelName)
        putCache(key, decision)
        return decision
    }

    private suspend fun runLlmDecision(
        userMessage: String,
        effectiveModel: String,
        client: ModelClient,
        startMs: Long,
        complexModelName: String? = null,
    ): ModeDecision =
        withContext(Dispatchers.IO) {
            runCatching {
                val prompt = buildDecisionPrompt(complexModelName)
                val request = ModelCallRequest(
                    model = effectiveModel.substringAfterLast('/'),
                    messages = listOf(
                        ModelMessage(role = "system", content = prompt),
                        ModelMessage(role = "user", content = userMessage.take(400))
                    ),
                    temperature = 0.0f,
                    maxTokens = 150,
                    stream = false
                )
                val response = client.chatCompletion(request)
                val content = response.choices.firstOrNull()?.message?.content
                    ?: throw IllegalStateException("LLM 返回内容为空")
                parseDecision(content, startMs, complexModelName != null)
            }.getOrElse { e ->
                Timber.w(e, "ModeRouter: LLM routing failed, fallback to CHAT")
                ModeDecision(
                    mode = AgentMode.CHAT,
                    reasoning = "LLM 路由失败,默认 Chat",
                    decisionTimeMs = System.currentTimeMillis() - startMs
                )
            }
        }

    private fun parseDecision(content: String, startMs: Long, hasComplexModel: Boolean = false): ModeDecision {
        val jsonStr = extractJsonObject(content)
            ?: return ModeDecision(
                mode = AgentMode.CHAT,
                reasoning = "LLM 输出非 JSON,默认 Chat",
                decisionTimeMs = System.currentTimeMillis() - startMs
            )
        val obj = runCatching { json.parseToJsonElement(jsonStr).jsonObject }.getOrNull()
            ?: return ModeDecision(
                mode = AgentMode.CHAT,
                reasoning = "LLM JSON 解析失败,默认 Chat",
                decisionTimeMs = System.currentTimeMillis() - startMs
            )
        val modeStr = obj["mode"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
        val mode = parseMode(modeStr)
        val reasoning = obj["reasoning"]?.jsonPrimitive?.contentOrNull?.trim()
        val useComplex = hasComplexModel &&
            obj["use_complex_model"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        return ModeDecision(
            mode = mode,
            reasoning = reasoning,
            decisionTimeMs = System.currentTimeMillis() - startMs,
            useComplexModel = useComplex
        )
    }

    private fun parseMode(value: String?): AgentMode = when (value) {
        "WORK" -> AgentMode.WORK
        "CHAT" -> AgentMode.CHAT
        else -> AgentMode.CHAT
    }

    private fun extractJsonObject(content: String): String? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return content.substring(start, end + 1)
    }

    @Synchronized
    private fun getCached(key: String): ModeDecision? = cache[key]

    @Synchronized
    private fun putCache(key: String, value: ModeDecision) {
        cache[key] = value
    }

    private fun buildCacheKey(agentId: String?, effectiveModel: String, message: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(message.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "${agentId.orEmpty()}:$effectiveModel:$hex"
    }

    companion object {
        const val CACHE_MAX_SIZE = 100

        private const val BASE_DECISION_PROMPT = """你是 HippyAgent 的模式路由器,根据用户的输入决定应该用 Chat 还是 Work 模式:
- Chat 模式:闲聊、问答、咨询、解释、翻译、简单查询
- Work 模式:需要调用工具/技能/多步骤执行的复杂任务(写代码、做 PPT、操作文件、自动化操作、跨设备控制)

只返回 JSON,例如 {"mode": "WORK", "reasoning": "需要做 PPT,涉及文件操作和工具调用"}"""

        private fun buildDecisionPrompt(complexModelName: String?): String {
            if (complexModelName.isNullOrBlank()) return BASE_DECISION_PROMPT
            return """$BASE_DECISION_PROMPT

当前可用复杂任务模型: $complexModelName
若判定为 Work 模式且任务涉及大量代码生成、复杂分析或多步骤编排,请额外标记 "use_complex_model": true。
示例: {"mode": "WORK", "reasoning": "复杂代码重构", "use_complex_model": true}"""
        }
    }
}
