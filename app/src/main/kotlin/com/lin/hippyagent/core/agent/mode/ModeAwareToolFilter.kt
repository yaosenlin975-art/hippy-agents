package com.lin.hippyagent.core.agent.mode

import com.lin.hippyagent.core.skill.AgentMode
import com.lin.hippyagent.core.skill.WorkspaceSkillConfigManager
import com.lin.hippyagent.core.tools.ToolRegistry

class ModeAwareToolFilter(
    private val skillConfig: WorkspaceSkillConfigManager,
    private val toolRegistry: ToolRegistry
) {
    fun applyForMode(agentId: String, mode: AgentMode, allToolIds: Set<String>): Set<String> {
        val effective = skillConfig.resolveEffectiveTools(mode, allToolIds)
        val denied = (allToolIds - effective).toList()
        toolRegistry.setAgentDenyList(agentId, denied)
        return effective
    }
}
