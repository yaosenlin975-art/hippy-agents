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
    fun resolve(
        userInput: String,
        enabledSkillIds: List<String>,
        filePaths: List<String> = emptyList()
    ): List<ResolvedSkill> {
        val index = skillManager.loadIndex()
        val results = mutableListOf<ResolvedSkill>()
        val inputLower = userInput.lowercase()

        for (skillId in enabledSkillIds) {
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
