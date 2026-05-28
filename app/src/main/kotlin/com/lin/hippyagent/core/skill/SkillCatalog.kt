package com.lin.hippyagent.core.skill

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

data class SkillCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val triggers: List<String>,
    val toolNames: List<String>
)

class SkillCatalog(
    private val skillManager: SkillManager
) {
    private val toolToSkillMap = ConcurrentHashMap<String, String>()

    @Synchronized
    fun buildCatalogText(enabledSkillIds: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine("你可以使用以下技能, 当用户说出技能的触发词或语义相近的词时请使用对应的技能:")
        sb.appendLine()

        val index = skillManager.loadIndex()
        toolToSkillMap.clear()

        for (skillId in enabledSkillIds) {
            val entry = index.skills[skillId] ?: continue
            val triggers = entry.triggers.keywords.take(5)
            val line = buildString {
                append("- **${entry.name}** (`$skillId`)")
                if (triggers.isNotEmpty()) {
                    append(" — 触发词: ${triggers.joinToString(", ")}")
                }
            }
            sb.appendLine(line)

            for (toolName in entry.toolNames) {
                toolToSkillMap[toolName] = skillId
            }
        }

        sb.appendLine()
        return sb.toString()
    }

    @Synchronized
    fun buildProgressiveCatalogText(enabledSkillIds: List<String>): String {
        val sb = StringBuilder()
        val index = skillManager.loadIndex()
        toolToSkillMap.clear()

        for (skillId in enabledSkillIds) {
            val entry = index.skills[skillId] ?: continue
            val skillDir = skillManager.getSkillDir(skillId)
            val location = skillDir?.resolve("SKILL.md")?.absolutePath ?: ""

            sb.appendLine("<skill>")
            sb.appendLine("  <name>${entry.name}</name>")
            sb.appendLine("  <description>${entry.description}</description>")
            sb.appendLine("  <id>$skillId</id>")
            if (location.isNotBlank()) {
                sb.appendLine("  <location>$location</location>")
            }
            sb.appendLine("</skill>")

            for (toolName in entry.toolNames) {
                toolToSkillMap[toolName] = skillId
            }
        }

        return sb.toString()
    }

    fun getSkillForTool(toolName: String): String? = toolToSkillMap[toolName]

    fun getCatalogEntries(enabledSkillIds: List<String>): List<SkillCatalogEntry> {
        val index = skillManager.loadIndex()
        val entries = mutableListOf<SkillCatalogEntry>()
        for (skillId in enabledSkillIds) {
            val entry = index.skills[skillId] ?: continue
            entries.add(SkillCatalogEntry(
                id = entry.id,
                name = entry.name,
                description = entry.description,
                triggers = entry.triggers.keywords,
                toolNames = entry.toolNames
            ))
        }
        return entries
    }
}
