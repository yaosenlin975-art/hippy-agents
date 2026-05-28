package com.lin.hippyagent.core.agent.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class LightContextConfig(
    @SerialName("dialog_path")
    val dialogPath: String = "dialog",

    @SerialName("token_count_estimate_divisor")
    val tokenCountEstimateDivisor: Float = 4.0f,

    @SerialName("context_compact_config")
    val contextCompactConfig: ContextCompactConfig = ContextCompactConfig(),

    @SerialName("tool_result_pruning_config")
    val toolResultPruningConfig: ToolResultPruningConfig = ToolResultPruningConfig(),
)

@Serializable
data class ContextCompactConfig(
    val enabled: Boolean = true,

    @SerialName("compact_threshold_ratio")
    val compactThresholdRatio: Float = 0.8f,

    @SerialName("reserve_threshold_ratio")
    val reserveThresholdRatio: Float = 0.1f,

    @SerialName("compact_with_thinking_block")
    val compactWithThinkingBlock: Boolean = true,

    @SerialName("compaction_fallback_enabled")
    val compactionFallbackEnabled: Boolean = true,

    @SerialName("compaction_fallback_reserve_ratio")
    val compactionFallbackReserveRatio: Float = 0.6f,
)

@Serializable
data class ToolResultPruningConfig(
    val enabled: Boolean = true,

    @SerialName("pruning_recent_n")
    val pruningRecentN: Int = 2,

    @SerialName("pruning_old_msg_max_bytes")
    val pruningOldMsgMaxBytes: Int = 3000,

    @SerialName("pruning_recent_msg_max_bytes")
    val pruningRecentMsgMaxBytes: Int = 50000,

    @SerialName("offload_retention_days")
    val offloadRetentionDays: Int = 5,

    @SerialName("tool_results_cache")
    val toolResultsCache: String = "tool_results",

    @SerialName("exempt_file_extensions")
    val exemptFileExtensions: List<String> = listOf(".md"),

    @SerialName("exempt_tool_names")
    val exemptToolNames: List<String> = listOf("chat_with_agent"),
)

