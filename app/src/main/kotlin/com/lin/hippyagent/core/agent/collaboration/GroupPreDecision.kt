package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.pool.StringBuilderPool
import com.lin.hippyagent.core.skill.AgentMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * 群聊广播范围 — 由 GroupPreDecisionMaker 决定后写入 GroupPreDecision，
 * AgentGroup 据此过滤 targetAgents。
 */
enum class BroadcastScope {
    /** 推送给群内所有 agent */
    ALL,
    /** 仅推送给相关的 agent（由 BroadcastPreScorer 进一步打分） */
    RELEVANT,
    /** 不向任何 agent 推送（仅回复用户） */
    NONE
}

/**
 * 群聊单轮预决策结果 — 由 GroupPreDecisionMaker 在收到用户消息时产出，
 * 整个 group 这一轮共享：mode 决定所有被广播 agent 的工作模式，
 * broadcastScope 决定目标 agent 范围。
 */
data class GroupPreDecision(
    val mode: AgentMode,
    val broadcastScope: BroadcastScope,
    val reasoning: String? = null,
    val source: ModeSourceHint = ModeSourceHint.LLM_DECIDED
) {
    enum class ModeSourceHint { LLM_DECIDED, TIMEOUT_FALLBACK, PARSE_FALLBACK }
}

/**
 * 群聊预决策生成器 — 一次 LLM 调用同时决定 (mode, broadcastScope)。
 *
 * 超时 / 失败时回退到 (WORK, ALL) — 保证群聊不卡死。
 * 设计动机：避免每个 agent 各自跑一次 ModeRouter.decideMode 造成 N 次 LLM 调用 + N 份独立决策可能不一致。
 */
class GroupPreDecisionMaker(
    private val modelClients: Map<String, ModelClient>,
    private val getGroupConfig: (groupId: String) -> GroupPreDecisionConfig?,
    private val getAgentDescriptions: (groupId: String) -> List<Pair<String, String>>,
    private val timeoutMs: Long = 3000L
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val promptPool = StringBuilderPool()

    suspend fun decide(groupId: String, userMessage: String): GroupPreDecision {
        val trimmed = userMessage.trim()
        if (trimmed.isEmpty()) {
            return fallback("输入为空", GroupPreDecision.ModeSourceHint.PARSE_FALLBACK)
        }

        val config = getGroupConfig(groupId)
        val (client, modelName) = resolveClientAndModel(config)
            ?: return fallback("未配置模型", GroupPreDecision.ModeSourceHint.PARSE_FALLBACK)

        val agents = getAgentDescriptions(groupId)
        val prompt = buildPrompt(trimmed, agents)
        val request = ModelCallRequest(
            model = modelName,
            messages = listOf(
                ModelMessage(role = "system", content = DECISION_SYSTEM_PROMPT),
                ModelMessage(role = "user", content = prompt)
            ),
            temperature = 0.0f,
            maxTokens = 200,
            stream = false
        )

        return withContext(Dispatchers.IO) {
            val response = withTimeoutOrNull(timeoutMs) {
                runCatching { client.chatCompletion(request) }.getOrNull()
            }
            if (response == null) {
                Timber.w("GroupPreDecisionMaker: timeout after ${timeoutMs}ms, fallback")
                return@withContext fallback("LLM 调用超时", GroupPreDecision.ModeSourceHint.TIMEOUT_FALLBACK)
            }
            val content = response.choices.firstOrNull()?.message?.content
            if (content.isNullOrBlank()) {
                return@withContext fallback("LLM 返回为空", GroupPreDecision.ModeSourceHint.PARSE_FALLBACK)
            }
            parse(content) ?: fallback("LLM 输出无法解析", GroupPreDecision.ModeSourceHint.PARSE_FALLBACK)
        }
    }

    private fun resolveClientAndModel(config: GroupPreDecisionConfig?): Pair<ModelClient, String>? {
        val preferred = config?.decisionModelName?.takeIf { it.isNotBlank() }
        if (preferred != null) {
            val providerKey = config.decisionProviderId?.let { "$it/$preferred" }
            val client = modelClients[preferred]
                ?: (providerKey?.let { modelClients[it] })
                ?: modelClients.values.firstOrNull()
            if (client != null) {
                return client to preferred.substringAfterLast('/')
            }
        }
        val fallback = modelClients.entries.firstOrNull() ?: return null
        return fallback.value to fallback.key.substringAfterLast('/')
    }

    private fun fallback(reason: String, source: GroupPreDecision.ModeSourceHint): GroupPreDecision {
        Timber.d("GroupPreDecisionMaker: fallback to (WORK, ALL) — $reason")
        return GroupPreDecision(
            mode = AgentMode.WORK,
            broadcastScope = BroadcastScope.ALL,
            reasoning = reason,
            source = source
        )
    }

    private fun parse(content: String): GroupPreDecision? {
        val jsonStr = extractJsonObject(content) ?: return null
        val obj = runCatching { json.parseToJsonElement(jsonStr).jsonObject }.getOrNull() ?: return null
        val modeStr = obj["mode"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
        val scopeStr = obj["broadcast_scope"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
        val reasoning = obj["reasoning"]?.jsonPrimitive?.contentOrNull?.trim()
        val mode = when (modeStr) {
            "WORK" -> AgentMode.WORK
            "CHAT" -> AgentMode.CHAT
            else -> return null
        }
        val scope = when (scopeStr) {
            "ALL" -> BroadcastScope.ALL
            "RELEVANT" -> BroadcastScope.RELEVANT
            "NONE" -> BroadcastScope.NONE
            else -> return null
        }
        return GroupPreDecision(
            mode = mode,
            broadcastScope = scope,
            reasoning = reasoning,
            source = GroupPreDecision.ModeSourceHint.LLM_DECIDED
        )
    }

    private fun extractJsonObject(content: String): String? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return content.substring(start, end + 1)
    }

    private fun buildPrompt(userMessage: String, agents: List<Pair<String, String>>): String {
        val sb = promptPool.acquire()
        return try {
            sb.append("用户消息：").append(userMessage.take(400)).append('\n')
            sb.append("群成员：")
            if (agents.isEmpty()) {
                sb.append("(无)")
            } else {
                agents.forEachIndexed { i, (id, desc) ->
                    if (i > 0) sb.append("; ")
                    sb.append('@').append(id).append('(').append(desc.take(60)).append(')')
                }
            }
            sb.toString()
        } finally {
            promptPool.release(sb)
        }
    }

    companion object {
        private const val DECISION_SYSTEM_PROMPT = """你是群聊协调者，需要根据用户消息决定两件事：
1. mode — WORK（需要工具/多步骤执行）或 CHAT（闲聊/问答/简单查询）
2. broadcast_scope — ALL（推送给所有 agent）、RELEVANT（只推送给相关 agent）、NONE（不推送给任何 agent，仅回复用户）

只返回 JSON，例：
{"mode": "WORK", "broadcast_scope": "RELEVANT", "reasoning": "用户问代码问题，只需推送给编程 agent"}
"""
    }
}

/**
 * 群聊预决策的模型配置 — 由调用方（AgentGroupManager）注入。
 * decisionProviderId 可为空，回退到任意可用 modelClient。
 */
data class GroupPreDecisionConfig(
    val decisionProviderId: String? = null,
    val decisionModelName: String? = null
)
