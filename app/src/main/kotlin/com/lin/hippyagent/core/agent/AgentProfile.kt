package com.lin.hippyagent.core.agent

import com.lin.hippyagent.core.agent.config.HeartbeatConfig
import com.lin.hippyagent.core.agent.config.MCPConfig
import com.lin.hippyagent.core.agent.config.RunningConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class CollaborationConfig(
    val mentionable: Boolean = true,
    val delegatable: Boolean = false,
    @SerialName("preferred_topics")
    val preferredTopics: List<String> = emptyList(),
    val notes: String = ""
)

@Serializable
data class AgentProfile(
    @SerialName("agent_id")
    val agentId: String,

    val name: String = "",

    @SerialName("model_name")
    val modelName: String = "",

    @SerialName("model_provider")
    val modelProvider: String = "",

    @SerialName("fallback_model_name")
    val fallbackModelName: String = "",

    @SerialName("fallback_model_provider")
    val fallbackModelProvider: String = "",

    @SerialName("complex_model_name")
    val complexModelName: String = "",

    @SerialName("complex_model_provider")
    val complexModelProvider: String = "",

    /**
     * 模式决策专用模型 — 留空时由 ModeOrchestrator 降级到 [modelName]。
     * 用于 ModeRouter.decideMode 决定 Chat / Work 时的 LLM 调用。
     */
    @SerialName("decision_model_name")
    val decisionModelName: String = "",

    @SerialName("decision_model_provider")
    val decisionModelProvider: String = "",

    @SerialName("vlm_model_name")
    val vlmModelName: String = "",

    @SerialName("vlm_model_provider")
    val vlmModelProvider: String = "",

    @SerialName("is_default")
    val isDefault: Boolean = false,

    val enabled: Boolean = true,

    @SerialName("avatar_url")
    val avatarUrl: String? = null,

    val running: RunningConfig = RunningConfig(),

    val heartbeat: HeartbeatConfig = HeartbeatConfig(),

    val mcp: MCPConfig = MCPConfig(),

    @SerialName("core_files")
    val coreFiles: List<String> = listOf(
        "RULES.md",
        "AGENTS.md",
        "SOUL.md",
        "PROFILE.md",
        "HEARTBEAT.md"
    ),

    val skills: List<String> = emptyList(),

    /** 被禁用的技能 ID 列表（仍保留在 skills 中但标记为不可用） */
    @SerialName("disabled_skills")
    val disabledSkills: List<String> = emptyList(),

    /** 被禁用的工具名称列表（全局工具通过 denyList 控制该智能体不可使用） */
    @SerialName("disabled_tools")
    val disabledTools: List<String> = emptyList(),

    /**
     * 默认工具包 — 该 Agent 始终可见的工具列表。
     * null 或 empty = 全部工具可见（向后兼容）。
     * 非空时只有包内工具 + 硬编码核心工具始终发送给 LLM，
     * 包外工具走 deferred，可通过 tool_search 发现。
     */
    @SerialName("default_tool_kit")
    val defaultToolKit: List<String>? = null,

    @SerialName("identity")
    val identity: String = "",

    @SerialName("responsibilities")
    val responsibilities: List<String> = emptyList(),

    @SerialName("boundaries")
    val boundaries: List<String> = emptyList(),

    @SerialName("collaboration")
    val collaboration: CollaborationConfig = CollaborationConfig(),

    @SerialName("deferred_tool_groups")
    val deferredToolGroups: List<String> = emptyList(),

    @SerialName("default_mode")
    val defaultMode: String = "AUTO",

    @SerialName("mode_locked")
    val modeLocked: Boolean = false,

    // --- Legacy fields (mirrored for backward compatibility) ---
    // These fields are kept for a transition period to allow downgrading
    // to older versions that expect the original field names.

    @SerialName("workspace")
    @Deprecated("Use agentId-based workspaces instead", level = DeprecationLevel.HIDDEN)
    val workspace: String? = null,

    @SerialName("config")
    @Deprecated("Use running/heartbeat/mcp fields instead", level = DeprecationLevel.HIDDEN)
    val config: Map<String, String>? = null
) {
    /**
     * Create a legacy-compatible JSON representation that includes
     * both new and old field names for backward compatibility.
     */
    fun toLegacyJson(): String {
        // The kotlinx.serialization encoder handles this automatically
        // via @SerialName annotations. This method exists for manual
        // construction if needed.
        return kotlinx.serialization.json.Json.encodeToString(serializer(), this)
    }

    companion object {
        /**
         * Create an AgentProfile from legacy format, mapping old fields to new ones.
         */
        fun fromLegacy(
            agentId: String,
            name: String = "",
            modelName: String = "",
            modelProvider: String = "",
            workspace: String? = null,
            config: Map<String, String>? = null
        ): AgentProfile {
            return AgentProfile(
                agentId = agentId,
                name = name,
                modelName = modelName,
                modelProvider = modelProvider,
                workspace = workspace,
                config = config
            )
        }
    }
}

