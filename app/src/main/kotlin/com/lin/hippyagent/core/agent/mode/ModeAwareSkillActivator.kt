package com.lin.hippyagent.core.agent.mode

import com.lin.hippyagent.core.skill.AgentMode
import com.lin.hippyagent.core.skill.SkillLifecycleManager
import com.lin.hippyagent.core.skill.WorkspaceSkillConfigManager

class ModeAwareSkillActivator(
    private val skillConfig: WorkspaceSkillConfigManager,
    private val skillLoader: SkillLifecycleManager
) {
    fun activateForMode(agentId: String, mode: AgentMode, skillIds: Set<String>): Set<String> {
        val effective = skillConfig.resolveEffectiveSkills(mode, skillIds)
        for (id in effective) {
            skillLoader.activateSkill(id)
        }
        return effective
    }
}
