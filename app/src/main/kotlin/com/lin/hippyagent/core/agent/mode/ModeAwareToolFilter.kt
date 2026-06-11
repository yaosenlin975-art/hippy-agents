package com.lin.hippyagent.core.agent.mode

import com.lin.hippyagent.core.skill.AgentMode
import com.lin.hippyagent.core.skill.WorkspaceSkillConfigManagerRegistry
import com.lin.hippyagent.core.tools.ToolRegistry

class ModeAwareToolFilter(
    private val skillConfigRegistry: WorkspaceSkillConfigManagerRegistry,
    private val toolRegistry: ToolRegistry
) {
    /**
     * 按当前 mode 计算该 agent 的有效工具集合,并更新 denyList。
     * 内部自行获取 tool 定义(避免 orchestrator 与 prepareMessageContext 重复调用)。
     *
     * [profileDisabledTools] 来自 [com.lin.hippyagent.core.agent.AgentProfile.disabledTools],
     * 视为"全局 OFF" — 先从候选集中剔除,再叠加 mode-aware 4 态过滤。
     * [extraDeniedTools] 来自调用方(群聊 GROUP_DENIED_TOOLS 等),与 visibility 解耦,
     * 强制追加到 denyList,确保被设计为禁用的工具不被任何 mode 重新放行。
     * 这两个集合一起叠加:[profileDisabledTools] 同时过滤候选集和 denyList,
     * [extraDeniedTools] 仅追加到 denyList(不影响 LLM 工具列表 vs 实际可调用的语义分层)。
     */
    fun applyForMode(
        agentId: String,
        mode: AgentMode,
        profileDisabledTools: Set<String> = emptySet(),
        extraDeniedTools: Set<String> = emptySet(),
    ): Set<String> {
        val allToolIds = toolRegistry.getDefinitionsForAgent(agentId).map { it.name }.toSet()
        val candidateToolIds = allToolIds - profileDisabledTools
        val effective = skillConfigRegistry.forAgent(agentId).resolveEffectiveTools(mode, candidateToolIds)
        val denied = ((allToolIds - effective) + extraDeniedTools).toList()
        toolRegistry.setAgentDenyList(agentId, denied)
        return effective
    }
}
