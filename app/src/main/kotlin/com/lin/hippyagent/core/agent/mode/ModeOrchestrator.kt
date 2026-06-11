package com.lin.hippyagent.core.agent.mode

import com.lin.hippyagent.core.agent.AgentProfile
import com.lin.hippyagent.core.skill.AgentMode
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
                // 决策模型未配置时回退到主模型, 主模型缺 provider 时尝试从 modelName 解析 ("provider/model" 形式)
                val fallbackModel = profile.modelName.takeIf { it.isNotBlank() }
                val fallbackProvider = profile.modelProvider.takeIf { it.isNotBlank() }
                    ?: fallbackModel?.substringBefore('/', "")?.takeIf { it.isNotBlank() && '/' in fallbackModel }
                val decision = modeRouter.decideMode(
                    userMessage = userMessage,
                    agentId = agentId,
                    overrideModelName = decisionModelName ?: fallbackModel,
                    overrideProviderId = if (decisionModelName != null) profile.decisionModelProvider.takeIf { it.isNotBlank() }
                        else fallbackProvider,
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
     * 2. 过滤 mode-aware 工具(可选叠加 [extraDeniedTools],用于群聊等场景强制禁用)
     * 3. 生成 mode-specific system prompt 后缀
     *
     * [extraDeniedTools] 与 mode/visibility 解耦,强制追加到 denyList。
     * 典型用法:群聊把 [GROUP_DENIED_TOOLS] 传入,确保被设计为禁用的工具不被任何 mode 重新放行。
     */
    fun applyForMode(
        agentId: String,
        profile: AgentProfile,
        resolution: ModeResolution,
        extraDeniedTools: Set<String> = emptySet(),
    ): String {
        // AUTO：跳过模式过滤, 由 chatViewModel 走另一条路径处理
        if (resolution.mode == AgentMode.AUTO) {
            return ""
        }
        // NONE 模式：不进行模式区分, 仍调用 activator/filter
        // (resolveEffectiveSkills/Tools 内 isUnfiltered 分支会加载所有已启用项)

        // 1. 候选技能 = profile.skills - profile.disabledSkills(全局禁用,等价于 OFF)
        val candidateSkillIds = (profile.skills - profile.disabledSkills.toSet()).toSet()
        skillActivator.activateForMode(agentId, resolution.mode, candidateSkillIds)
        // 2. 工具由 ToolFilter 内部获取 allToolIds,profile.disabledTools 视为"全局 OFF"
        toolFilter.applyForMode(agentId, resolution.mode, profile.disabledTools.toSet(), extraDeniedTools)

        // NONE 模式无 mode-specific system prompt 后缀
        if (resolution.mode == AgentMode.NONE) {
            return ""
        }
        return promptInjector.promptFor(resolution.mode)
    }

    private fun parseMode(value: String): AgentMode = when (value.uppercase()) {
        "CHAT" -> AgentMode.CHAT
        "WORK" -> AgentMode.WORK
        else -> AgentMode.AUTO
    }
}
