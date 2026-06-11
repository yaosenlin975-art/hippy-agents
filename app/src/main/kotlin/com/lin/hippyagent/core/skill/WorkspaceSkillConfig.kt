package com.lin.hippyagent.core.skill

import com.lin.hippyagent.core.util.FileUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class AgentMode { AUTO, CHAT, WORK, NONE }

/**
 * 技能/工具在 agent 内的可见范围(UI 控件 4 态)。
 * - OFF: 完全禁用,任何模式都不注入
 * - CHAT: 仅在 Chat 模式注入
 * - WORK: 仅在 Work 模式注入
 * - ALL: 所有模式都注入(包括 AUTO / NONE)
 */
enum class SkillVisibility { OFF, CHAT, WORK, ALL }

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
            // enabledSkills 优先级低于 disabledSkills: 若同 ID 同时出现,disabled 胜出
            if (id in disabledSkills) continue
            if (id !in merged) {
                merged[id] = SkillWorkspaceEntry(enabled = true)
            }
        }
        for (id in disabledSkills) {
            val existing = merged[id]
            if (existing == null) {
                merged[id] = SkillWorkspaceEntry(enabled = false)
            } else {
                // v1 语义: disabled 显式覆盖 enabled,即使 enabledSkills 含该 ID
                merged[id] = existing.copy(enabled = false)
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

/**
 * 单个 agent 的 skill_config.json 读写器。
 * 必须通过 [WorkspaceSkillConfigManagerRegistry.forAgent] 获取实例,
 * 不再允许直接 new,避免出现不同 agent 共享同一 workspaceDir 的隐患。
 */
class WorkspaceSkillConfigManager(
    private val workspaceDir: File,
    val agentId: String,
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
        // NONE 模式：不进行模式区分，加载所有已启用的工具（与 skills 一致语义）
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
        // tools 没有 profile 默认兜底,空集即空集(由调用方决定是否禁用 LLM 工具调用)
        resolved
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean) = synchronized(lock) {
        val config = loadConfig()
        val entry = config.skills[skillId] ?: SkillWorkspaceEntry()
        val newSkills = config.skills.toMutableMap()
        newSkills[skillId] = entry.copy(enabled = enabled)
        saveConfig(config.copy(skills = newSkills))
    }

    fun setSkillModes(skillId: String, modes: Set<AgentMode>) = synchronized(lock) {
        val config = loadConfig()
        val entry = config.skills[skillId] ?: SkillWorkspaceEntry()
        val newSkills = config.skills.toMutableMap()
        newSkills[skillId] = entry.copy(modes = modes)
        saveConfig(config.copy(skills = newSkills))
    }

    fun setToolEnabled(toolId: String, enabled: Boolean) = synchronized(lock) {
        val config = loadConfig()
        val entry = config.tools[toolId] ?: ToolWorkspaceEntry()
        val newTools = config.tools.toMutableMap()
        newTools[toolId] = entry.copy(enabled = enabled)
        saveConfig(config.copy(tools = newTools))
    }

    fun setToolModes(toolId: String, modes: Set<AgentMode>) = synchronized(lock) {
        val config = loadConfig()
        val entry = config.tools[toolId] ?: ToolWorkspaceEntry()
        val newTools = config.tools.toMutableMap()
        newTools[toolId] = entry.copy(modes = modes)
        saveConfig(config.copy(tools = newTools))
    }

    /**
     * 读出技能当前 4 态:无 entry 或 enabled=true 且 modes 为空 → ALL(默认全可见)。
     */
    fun getSkillVisibility(skillId: String): SkillVisibility = synchronized(lock) {
        val entry = loadConfig().skills[skillId] ?: return SkillVisibility.ALL
        entry.toVisibility()
    }

    /**
     * 读出工具当前 4 态。
     */
    fun getToolVisibility(toolId: String): SkillVisibility = synchronized(lock) {
        val entry = loadConfig().tools[toolId] ?: return SkillVisibility.ALL
        entry.toVisibility()
    }

    /**
     * 设置技能 4 态(等价于写入 enabled + modes 组合)。
     */
    fun setSkillVisibility(skillId: String, visibility: SkillVisibility) = synchronized(lock) {
        val (enabled, modes) = visibility.toEntry()
        val config = loadConfig()
        val entry = config.skills[skillId] ?: SkillWorkspaceEntry()
        val newSkills = config.skills.toMutableMap()
        newSkills[skillId] = entry.copy(enabled = enabled, modes = modes)
        saveConfig(config.copy(skills = newSkills))
    }

    /**
     * 设置工具 4 态。
     */
    fun setToolVisibility(toolId: String, visibility: SkillVisibility) = synchronized(lock) {
        val (enabled, modes) = visibility.toEntry()
        val config = loadConfig()
        val entry = config.tools[toolId] ?: ToolWorkspaceEntry()
        val newTools = config.tools.toMutableMap()
        newTools[toolId] = entry.copy(enabled = enabled, modes = modes)
        saveConfig(config.copy(tools = newTools))
    }

    private fun SkillWorkspaceEntry.toVisibility(): SkillVisibility = when {
        !enabled -> SkillVisibility.OFF
        modes.isEmpty() -> SkillVisibility.ALL
        modes == setOf(AgentMode.CHAT) -> SkillVisibility.CHAT
        modes == setOf(AgentMode.WORK) -> SkillVisibility.WORK
        else -> SkillVisibility.ALL // 老数据 {CHAT,WORK} 或其它组合 → 视作 ALL
    }

    private fun ToolWorkspaceEntry.toVisibility(): SkillVisibility = when {
        !enabled -> SkillVisibility.OFF
        modes.isEmpty() -> SkillVisibility.ALL
        modes == setOf(AgentMode.CHAT) -> SkillVisibility.CHAT
        modes == setOf(AgentMode.WORK) -> SkillVisibility.WORK
        else -> SkillVisibility.ALL
    }

    private fun SkillVisibility.toEntry(): Pair<Boolean, Set<AgentMode>> = when (this) {
        SkillVisibility.OFF -> false to emptySet()
        SkillVisibility.CHAT -> true to setOf(AgentMode.CHAT)
        SkillVisibility.WORK -> true to setOf(AgentMode.WORK)
        SkillVisibility.ALL -> true to emptySet()
    }
}
