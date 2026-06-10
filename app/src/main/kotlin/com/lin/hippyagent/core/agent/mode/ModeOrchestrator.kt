package com.lin.hippyagent.core.agent.mode

import com.lin.hippyagent.core.agent.AgentProfile
import com.lin.hippyagent.core.skill.AgentMode
import com.lin.hippyagent.core.tools.ToolRegistry
import timber.log.Timber

/**
 * 模式编排器 — 串联 ModeRouter / ModeSystemPromptInjector / ModeAwareSkillActivator / ModeAwareToolFilter,
 * 为 Agent.processMessage / processMessageStream 提供"模式决策 → 过滤 → 注入 prompt"的一站式能力。
 *
 * 决策规则:
 * 1. profile.modeLocked == true → 强制使用 profile.defaultMode
 * 2. 否则:
 *    a. sessionSelectedMode (用户手动选择) 存在 → 优先使用
 *    b. 否则:
 *       - profile.defaultMode != AUTO → 使用 profile.defaultMode
 *       - 否则由 ModeRouter 决定 (CHAT / WORK)
 */
class ModeOrchestrator(
    private val modeRouter: ModeRouter,
    private val promptInjector: ModeSystemPromptInjector,
    private val skillActivator: ModeAwareSkillActivator,
    private val toolFilter: ModeAwareToolFilter,
    private val toolRegistry: ToolRegistry,
) {
    data class ModeResolution(
        val mode: AgentMode,
        val source: ModeSource,
        val reasoning: String? = null,
        val decidedAt: Long = System.currentTimeMillis(),
        val useComplexModel: Boolean = false,
    )

    enum class ModeSource { USER_LOCKED, USER_SELECTED, PROFILE_DEFAULT, AUTO_DECIDED, GROUP_DECIDED, FALLBACK }

    /**
     * 解析当前会话的有效模式。
     * 供 Agent 在 processMessage 早期调用,获得 effectiveMode。
     *
     * 群聊场景下 [overrideMode] 优先：GroupPreDecisionMaker 已经为整轮群聊做出决策,
     * 共享决策避免每个 agent 各自触发一次 ModeRouter LLM 调用,同时保证模式一致。
     */
    suspend fun resolveMode(
        agentId: String,
        profile: AgentProfile,
        userMessage: String,
        sessionSelectedMode: AgentMode? = null,
        overrideMode: AgentMode? = null,
    ): ModeResolution {
        if (overrideMode == AgentMode.NONE) {
            return ModeResolution(
                mode = AgentMode.NONE,
                source = ModeSource.USER_SELECTED,
                reasoning = "NONE 模式：不进行模式区分",
            )
        }
        if (overrideMode != null && overrideMode != AgentMode.AUTO) {
            return ModeResolution(
                mode = overrideMode,
                source = ModeSource.GROUP_DECIDED,
                reasoning = "群聊集中决策",
            )
        }
        val resolved = when {
            profile.modeLocked -> ModeResolution(
                mode = parseMode(profile.defaultMode),
                source = ModeSource.USER_LOCKED,
                reasoning = "profile.modeLocked=true",
            )
            sessionSelectedMode != null && sessionSelectedMode != AgentMode.AUTO -> ModeResolution(
                mode = sessionSelectedMode,
                source = ModeSource.USER_SELECTED,
            )
            !profile.defaultMode.equals("AUTO", ignoreCase = true) -> ModeResolution(
                mode = parseMode(profile.defaultMode),
                source = ModeSource.PROFILE_DEFAULT,
            )
            else -> {
                val decisionModelName = profile.decisionModelName.takeIf { it.isNotBlank() }
                val complexModel = profile.complexModelName.takeIf { it.isNotBlank() }
                val decision = modeRouter.decideMode(
                    userMessage = userMessage,
                    agentId = agentId,
                    overrideModelName = decisionModelName
                        ?: profile.modelName.takeIf { it.isNotBlank() },
                    overrideProviderId = if (decisionModelName != null) profile.decisionModelProvider.takeIf { it.isNotBlank() }
                        else profile.modelProvider.takeIf { it.isNotBlank() },
                    complexModelName = complexModel
                )
                ModeResolution(
                    mode = decision.mode,
                    source = ModeSource.AUTO_DECIDED,
                    reasoning = decision.reasoning,
                    useComplexModel = decision.useComplexModel,
                )
            }
        }
        Timber.d("ModeOrchestrator: resolved mode=$resolved")
        return resolved
    }

    /**
     * 应用模式过滤:
     * 1. 激活 mode-aware 技能
     * 2. 过滤 mode-aware 工具
     * 3. 生成 mode-specific system prompt 后缀
     */
    fun applyForMode(
        agentId: String,
        profile: AgentProfile,
        resolution: ModeResolution,
    ): String {
        // AUTO / NONE：不走模式过滤 (AUTO 由 chatViewModel 走另一条路径处理, NONE 不过滤)
        if (resolution.mode == AgentMode.AUTO || resolution.mode == AgentMode.NONE) {
            return ""
        }
        val skillIds = profile.skills.toSet()
        skillActivator.activateForMode(agentId, resolution.mode, skillIds)
        val allToolIds = toolRegistry.getDefinitionsForAgent(agentId).map { it.name }.toSet()
        toolFilter.applyForMode(agentId, resolution.mode, allToolIds)
        return promptInjector.promptFor(resolution.mode)
    }

    private fun parseMode(value: String): AgentMode = when (value.uppercase()) {
        "CHAT" -> AgentMode.CHAT
        "WORK" -> AgentMode.WORK
        else -> AgentMode.AUTO
    }
}
