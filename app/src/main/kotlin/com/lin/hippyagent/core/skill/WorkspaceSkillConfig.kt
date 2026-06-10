package com.lin.hippyagent.core.skill

import com.lin.hippyagent.core.util.FileUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class AgentMode { AUTO, CHAT, WORK, NONE }

@Serializable
data class SkillWorkspaceEntry(
    val enabled: Boolean = true,
    val modes: Set<AgentMode> = emptySet(),
    val config: Map<String, String> = emptyMap()
)

@Serializable
data class ToolWorkspaceEntry(
    val enabled: Boolean = true,
    val modes: Set<AgentMode> = emptySet()
)

@Serializable
data class WorkspaceSkillConfig(
    val version: Int = 2,
    val skills: Map<String, SkillWorkspaceEntry> = emptyMap(),
    val tools: Map<String, ToolWorkspaceEntry> = emptyMap(),
    @Deprecated("Use skills map") val enabledSkills: List<String> = emptyList(),
    @Deprecated("Use skills map") val disabledSkills: List<String> = emptyList(),
    @Deprecated("Use skills map") val skillOverrides: Map<String, SkillOverride> = emptyMap()
) {
    fun migrateFromV1(): WorkspaceSkillConfig {
        if (version >= 2) return this
        val merged = LinkedHashMap<String, SkillWorkspaceEntry>(skills)
        for (id in enabledSkills) {
            if (id !in merged) {
                merged[id] = SkillWorkspaceEntry(enabled = true)
            }
        }
        for (id in disabledSkills) {
            val existing = merged[id]
            if (existing == null) {
                merged[id] = SkillWorkspaceEntry(enabled = false)
            }
        }
        for ((id, override) in skillOverrides) {
            val existing = merged[id] ?: SkillWorkspaceEntry()
            merged[id] = existing.copy(
                enabled = override.enabled,
                config = existing.config + override.config
            )
        }
        return copy(version = 2, skills = merged)
    }
}

@Serializable
data class SkillOverride(
    val enabled: Boolean = true,
    val config: Map<String, String> = emptyMap()
)

class WorkspaceSkillConfigManager(
    private val workspaceDir: File,
    private val agentId: String = "",
    private val profileSkillsProvider: () -> List<String> = { emptyList() }
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    fun loadConfig(): WorkspaceSkillConfig = synchronized(lock) {
        val configFile = workspaceDir.resolve("skill_config.json")
        if (!configFile.exists()) return WorkspaceSkillConfig()
        val raw = runCatching {
            json.decodeFromString<WorkspaceSkillConfig>(configFile.readText())
        }.getOrElse { return WorkspaceSkillConfig() }
        raw.migrateFromV1()
    }

    fun saveConfig(config: WorkspaceSkillConfig) = synchronized(lock) {
        val configFile = workspaceDir.resolve("skill_config.json")
        FileUtils.atomicWrite(configFile, json.encodeToString(WorkspaceSkillConfig.serializer(), config))
    }

    fun resolveEffectiveSkillIds(globalSkillIds: List<String>): List<String> = synchronized(lock) {
        val config = loadConfig()
        val effective = LinkedHashSet<String>()
        for (id in config.enabledSkills) effective.add(id)
        for (id in globalSkillIds) {
            if (id !in config.disabledSkills) effective.add(id)
        }
        for (id in config.disabledSkills) {
            effective.remove(id)
        }
        if (effective.isEmpty()) {
            val profileSkills = profileSkillsProvider()
            if (profileSkills.isNotEmpty()) return profileSkills
        }
        return effective.toList()
    }

    fun resolveEffectiveSkills(mode: AgentMode, ids: Set<String>): Set<String> = synchronized(lock) {
        val config = loadConfig()
        val resolved = LinkedHashSet<String>()
        // NONE 模式：不进行模式区分，加载所有已启用的技能
        val isUnfiltered = mode == AgentMode.NONE
        for (id in ids) {
            val entry = config.skills[id]
            if (entry == null) {
                if (id in config.disabledSkills) continue
                if (config.enabledSkills.isNotEmpty() && id !in config.enabledSkills) continue
                resolved.add(id)
            } else {
                if (!entry.enabled) continue
                if (!isUnfiltered && entry.modes.isNotEmpty() && mode !in entry.modes) continue
                resolved.add(id)
            }
        }
        if (resolved.isEmpty()) {
            val profileSkills = profileSkillsProvider()
            for (id in profileSkills) {
                if (id in ids) resolved.add(id)
            }
        }
        resolved
    }

    fun resolveEffectiveTools(mode: AgentMode, ids: Set<String>): Set<String> = synchronized(lock) {
        val config = loadConfig()
        val resolved = LinkedHashSet<String>()
        // NONE 模式：不进行模式区分，加载所有已启用的工具
        val isUnfiltered = mode == AgentMode.NONE
        for (id in ids) {
            val entry = config.tools[id]
            if (entry == null) {
                resolved.add(id)
            } else {
                if (!entry.enabled) continue
                if (!isUnfiltered && entry.modes.isNotEmpty() && mode !in entry.modes) continue
                resolved.add(id)
            }
        }
        resolved
    }

    fun enableSkill(skillId: String) = setSkillEnabled("", skillId, true)
    fun disableSkill(skillId: String) = setSkillEnabled("", skillId, false)

    fun setSkillEnabled(agentId: String, skillId: String, enabled: Boolean) = synchronized(lock) {
        val config = loadConfig()
        val entry = config.skills[skillId] ?: SkillWorkspaceEntry()
        val newSkills = config.skills.toMutableMap()
        newSkills[skillId] = entry.copy(enabled = enabled)
        saveConfig(config.copy(skills = newSkills))
    }

    fun setSkillModes(agentId: String, skillId: String, modes: Set<AgentMode>) = synchronized(lock) {
        val config = loadConfig()
        val entry = config.skills[skillId] ?: SkillWorkspaceEntry()
        val newSkills = config.skills.toMutableMap()
        newSkills[skillId] = entry.copy(modes = modes)
        saveConfig(config.copy(skills = newSkills))
    }

    fun setToolEnabled(agentId: String, toolId: String, enabled: Boolean) = synchronized(lock) {
        val config = loadConfig()
        val entry = config.tools[toolId] ?: ToolWorkspaceEntry()
        val newTools = config.tools.toMutableMap()
        newTools[toolId] = entry.copy(enabled = enabled)
        saveConfig(config.copy(tools = newTools))
    }

    fun setToolModes(agentId: String, toolId: String, modes: Set<AgentMode>) = synchronized(lock) {
        val config = loadConfig()
        val entry = config.tools[toolId] ?: ToolWorkspaceEntry()
        val newTools = config.tools.toMutableMap()
        newTools[toolId] = entry.copy(modes = modes)
        saveConfig(config.copy(tools = newTools))
    }
}
