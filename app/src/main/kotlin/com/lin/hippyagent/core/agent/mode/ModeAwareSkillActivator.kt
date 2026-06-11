package com.lin.hippyagent.core.agent.mode

import com.lin.hippyagent.core.skill.AgentMode
import com.lin.hippyagent.core.skill.SkillLifecycleManager
import com.lin.hippyagent.core.skill.WorkspaceSkillConfigManagerRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * 模式感知的技能激活器。
 * - 激活本 mode 的 effective skills
 * - 切换 mode 时,deactivate 上一 mode 激活但本 mode 不再需要的 skills
 *   (避免 LLM 工具列表中残留旧 mode 的工具)
 * - 切到新 agent 时,deactivate 旧 agent 的全部 effective skills,防止跨 agent 残留激活
 *
 * 线程模型:整个方法用单一把锁串行化。skillLoader 的 activate/deactivate 较轻量,
 * 而本方法每 turn 仅调用 1-2 次,单锁是简单且正确的方案;同时保证多 session 并发时
 * per-agent 状态(上一 effective、上一 agent)的原子读写。
 */
class ModeAwareSkillActivator(
    private val skillConfigRegistry: WorkspaceSkillConfigManagerRegistry,
    private val skillLoader: SkillLifecycleManager
) {
    private val lastEffectiveByAgent = ConcurrentHashMap<String, Set<String>>()
    // 由 activateForMode 内 synchronized(lock) 保护,无需额外 @Volatile
    private var lastAgentId: String? = null
    private val lock = Any()

    fun activateForMode(agentId: String, mode: AgentMode, skillIds: Set<String>): Set<String> = synchronized(lock) {
        val effective = skillConfigRegistry.forAgent(agentId).resolveEffectiveSkills(mode, skillIds)

        // 跨 agent 切换: 上一 agent 的 effective 全部 deactivate
        val previousAgent = lastAgentId
        if (previousAgent != null && previousAgent != agentId) {
            val stale = lastEffectiveByAgent.remove(previousAgent) ?: emptySet()
            for (id in stale) {
                skillLoader.deactivateSkill(id)
            }
        }
        lastAgentId = agentId

        // 同 agent 内 mode 切换: deactivate 不再需要的
        val previousEffective = lastEffectiveByAgent[agentId] ?: emptySet()
        val toDeactivate = previousEffective - effective
        for (id in toDeactivate) {
            skillLoader.deactivateSkill(id)
        }
        for (id in effective) {
            skillLoader.activateSkill(id)
        }
        lastEffectiveByAgent[agentId] = effective
        effective
    }
}
