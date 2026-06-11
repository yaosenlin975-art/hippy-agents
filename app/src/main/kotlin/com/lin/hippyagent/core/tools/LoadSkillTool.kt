package com.lin.hippyagent.core.tools

import com.lin.hippyagent.core.skill.SkillLifecycleManager
import com.lin.hippyagent.core.skill.SkillManager
import com.lin.hippyagent.data.repository.AgentRepository
import timber.log.Timber
import java.io.File

class LoadSkillTool(
    private val skillManager: SkillManager,
    private val agentRepository: AgentRepository? = null,
    private val skillLifecycleManager: SkillLifecycleManager? = null
) : Tool() {

    override val definition = ToolDefinition(
        name = "load_skill",
        description = "按需加载技能的完整说明。当某个已启用的技能与当前任务相关时，调用此工具获取该技能的完整使用指南。",
        parameters = mapOf(
            "skill_name" to ToolParameter(
                name = "skill_name",
                type = "string",
                description = "要加载的技能 ID（来自技能目录中的 skill id），仅限智能体已启用的技能",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        return ToolResult(callId, false, error = "此工具需要在智能体上下文中执行。请确保通过智能体调用此工具。")
    }

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val callId = args["callId"] as? String ?: ""
        val skillName = getRequiredArgument(args, "skill_name")

        val agentId = ctx.agentId
        if (agentId.isBlank()) {
            return ToolResult(callId, false, error = "Cannot determine agent identity")
        }

        val enabledSkills = getAgentEnabledSkills(agentId, ctx.workspace)
        if (skillName !in enabledSkills) {
            return ToolResult(callId, false, error = "Skill '$skillName' is not enabled for this agent. Enabled skills: ${enabledSkills.joinToString(", ")}")
        }

        val skillDir = skillManager.getSkillDir(skillName)
        if (skillDir == null || !skillDir.exists()) {
            return ToolResult(callId, false, error = "Skill not found: $skillName")
        }

        val skillMd = File(skillDir, "SKILL.md")
        if (!skillMd.exists()) {
            return ToolResult(callId, false, error = "SKILL.md not found for skill: $skillName")
        }

        return try {
            skillLifecycleManager?.activateSkill(skillName)
            if (skillMd.length() > 1_048_576) {
                return ToolResult(callId, false, error = "SKILL.md 文件过大（超过1MB），无法加载")
            }
            val content = skillMd.readText()
            Timber.d("LoadSkillTool: loaded skill $skillName (${content.length} chars)")
            ToolResult(callId, true, output = content)
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to read skill: ${e.message}")
        }
    }

    private fun getAgentEnabledSkills(agentId: String, workspace: File?): List<String> {
        if (workspace != null) {
            val skillJson = File(workspace, "skill.json")
            if (skillJson.exists()) {
                try {
                    val json = org.json.JSONObject(skillJson.readText())
                    val skillsObj = json.optJSONObject("skills")
                    if (skillsObj != null) {
                        val ids = mutableListOf<String>()
                        val keys = skillsObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val entry = skillsObj.optJSONObject(key)
                            if (entry != null && entry.optBoolean("enabled", true)) {
                                ids.add(key)
                            }
                        }
                        if (ids.isNotEmpty()) return ids
                    }
                } catch (_: Exception) {}
            }

            val config = com.lin.hippyagent.core.skill.WorkspaceSkillConfigManager(
                workspaceDir = workspace,
                agentId = agentId
            )
            val enabled = config.loadConfig().enabledSkills
            if (enabled.isNotEmpty()) return enabled
        }

        val profileSkills = agentRepository?.getEnabledSkillsFromJson(agentId)
        if (!profileSkills.isNullOrEmpty()) return profileSkills

        val globalEnabled = skillManager.listSkillsFromIndex().filter { skillManager.isSkillEnabled(it.id) }.map { it.id }
        if (globalEnabled.isNotEmpty()) return globalEnabled

        return emptyList()
    }
}
