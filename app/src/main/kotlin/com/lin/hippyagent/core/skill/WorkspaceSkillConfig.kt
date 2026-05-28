package com.lin.hippyagent.core.skill

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class WorkspaceSkillConfig(
    val enabledSkills: List<String> = emptyList(),
    val disabledSkills: List<String> = emptyList(),
    val skillOverrides: Map<String, SkillOverride> = emptyMap()
)

@Serializable
data class SkillOverride(
    val enabled: Boolean = true,
    val config: Map<String, String> = emptyMap()
)

class WorkspaceSkillConfigManager(
    private val workspaceDir: File
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    fun loadConfig(): WorkspaceSkillConfig = synchronized(lock) {
        val configFile = workspaceDir.resolve("skill_config.json")
        if (!configFile.exists()) return WorkspaceSkillConfig()
        return runCatching {
            json.decodeFromString<WorkspaceSkillConfig>(configFile.readText())
        }.getOrElse { WorkspaceSkillConfig() }
    }

    fun saveConfig(config: WorkspaceSkillConfig) = synchronized(lock) {
        val configFile = workspaceDir.resolve("skill_config.json")
        configFile.writeText(json.encodeToString(WorkspaceSkillConfig.serializer(), config))
    }

    fun resolveEffectiveSkillIds(globalSkillIds: List<String>): List<String> = synchronized(lock) {
        val config = loadConfig()
        val enabled = config.enabledSkills.toMutableList()
        for (id in globalSkillIds) {
            if (id !in enabled && id !in config.disabledSkills) {
                enabled.add(id)
            }
        }
        for (id in config.disabledSkills) {
            enabled.remove(id)
        }
        return enabled.distinct()
    }

    fun enableSkill(skillId: String) = synchronized(lock) {
        val config = loadConfig()
        val disabled = config.disabledSkills.toMutableList()
        disabled.remove(skillId)
        val enabled = (config.enabledSkills + skillId).distinct()
        saveConfig(config.copy(enabledSkills = enabled, disabledSkills = disabled))
    }

    fun disableSkill(skillId: String) = synchronized(lock) {
        val config = loadConfig()
        val enabled = config.enabledSkills.toMutableList()
        enabled.remove(skillId)
        val disabled = (config.disabledSkills + skillId).distinct()
        saveConfig(config.copy(enabledSkills = enabled, disabledSkills = disabled))
    }
}
