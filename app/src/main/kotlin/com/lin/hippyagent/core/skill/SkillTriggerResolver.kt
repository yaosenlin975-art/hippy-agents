package com.lin.hippyagent.core.skill

data class ResolvedSkill(
    val id: String,
    val name: String,
    val description: String,
    val manifest: SkillManifest,
    val matchReason: String
)

class SkillTriggerResolver(
    private val skillManager: SkillManager
) {
    /**
     * 解析用户输入, 找出触发的技能。
     *
     * @param userInput 用户输入文本
     * @param enabledSkillIds 显式启用的技能 ID 列表 (来自 profile.skills), 这些技能有最高优先级
     * @param filePaths 用户消息中提到的文件路径, 用于 file_extension 触发
     *
     * 扫描范围:
     * - enabledSkillIds: profile.skills 显式绑定的技能
     * - 索引中其余技能 (即"池中技能"): 用户从技能商店安装后未绑定到智能体,
     *   但当用户输入匹配其触发词/场景/扩展名时, 仍能被发现并注入到 prompt
     *
     * 与 promptBuilder 的协同: buildSkillInfoList / buildProgressiveCatalogText
     * 仅展示 enabledSkillIds, 池中技能经 trigger resolver 命中后通过
     * resolvedSkills 注入到 <skill_triggers> 段
     */
    fun resolve(
        userInput: String,
        enabledSkillIds: List<String>,
        filePaths: List<String> = emptyList()
    ): List<ResolvedSkill> {
        val index = skillManager.loadIndex()
        val results = mutableListOf<ResolvedSkill>()
        val inputLower = userInput.lowercase()

        val allSkillIds = (enabledSkillIds + index.skills.keys).distinct()

        for (skillId in allSkillIds) {
            val entry = index.skills[skillId] ?: continue
            val matchReason = matchTrigger(entry, inputLower, filePaths) ?: continue
            val manifest = skillManager.getManifest(skillId)
            results.add(ResolvedSkill(
                id = skillId,
                name = entry.name,
                description = entry.description,
                manifest = manifest ?: SkillManifest(
                    name = entry.name,
                    description = entry.description,
                    version = entry.version
                ),
                matchReason = matchReason
            ))
        }

        return results.sortedByDescending { scoreMatch(it) }
    }

    private fun matchTrigger(entry: SkillIndexEntry, inputLower: String, filePaths: List<String>): String? {
        val triggers = entry.triggers

        for (keyword in triggers.keywords) {
            if (inputLower.contains(keyword.lowercase())) {
                return "keyword: $keyword"
            }
        }

        for (ext in triggers.fileExtensions) {
            for (path in filePaths) {
                if (path.lowercase().endsWith(ext.lowercase())) {
                    return "file_extension: $ext"
                }
            }
            if (inputLower.contains(ext.lowercase())) {
                return "file_extension_mentioned: $ext"
            }
        }

        for (scenario in triggers.scenarios) {
            if (inputLower.contains(scenario.lowercase())) {
                return "scenario: $scenario"
            }
        }

        return null
    }

    private fun scoreMatch(resolved: ResolvedSkill): Int {
        return when {
            resolved.matchReason.startsWith("keyword") -> 3
            resolved.matchReason.startsWith("file_extension") -> 2
            resolved.matchReason.startsWith("scenario") -> 1
            else -> 0
        }
    }
}
