package com.lin.hippyagent.core.agent.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class RunningConfig(
    @SerialName("agent_language")
    val agentLanguage: String = "zh",

    @SerialName("user_timezone")
    val userTimezone: String = java.util.TimeZone.getDefault().id,

    @SerialName("max_iters")
    val maxIters: Int = 100,

    @SerialName("auto_continue_on_text_only")
    val autoContinueOnTextOnly: Boolean = false,

    @SerialName("llm_retry_enabled")
    val llmRetryEnabled: Boolean = true,

    @SerialName("llm_retry_max_retries")
    val llmRetryMaxRetries: Int = 3,

    @SerialName("llm_retry_backoff_base")
    val llmRetryBackoffBase: Float = 1.0f,

    @SerialName("llm_retry_backoff_cap")
    val llmRetryBackoffCap: Float = 10.0f,

    @SerialName("llm_max_concurrent")
    val llmMaxConcurrent: Int = 10,

    @SerialName("llm_max_qpm")
    val llmMaxQpm: Int = 600,

    @SerialName("llm_rate_limit_pause")
    val llmRateLimitPause: Float = 5.0f,

    @SerialName("llm_rate_limit_jitter")
    val llmRateLimitJitter: Float = 1.0f,

    @SerialName("llm_acquire_timeout")
    val llmAcquireTimeout: Float = 300.0f,

    @SerialName("max_input_length")
    val maxInputLength: Int = 131072,

    @SerialName("max_output_tokens")
    val maxOutputTokens: Int = 4096,

    @SerialName("history_max_length")
    val historyMaxLength: Int = 10000,

    @SerialName("context_manager_backend")
    val contextManagerBackend: String = "light",

    @SerialName("light_context_config")
    val lightContextConfig: LightContextConfig = LightContextConfig(),

    @SerialName("memory_manager_backend")
    val memoryManagerBackend: String = "remelight",

    @SerialName("reme_light_memory_config")
    val remeLightMemoryConfig: ReMeLightMemoryConfig = ReMeLightMemoryConfig(),

    @SerialName("daily_memory_dir")
    val dailyMemoryDir: String = "memory",

    @SerialName("shell_command_timeout_ms")
    val shellCommandTimeoutMs: Long = 60000,

    @SerialName("context_compaction_config")
    val contextCompactionConfig: ContextCompactionConfig = ContextCompactionConfig()
)

@Serializable
data class ContextCompactionConfig(
    @SerialName("dialog_storage_path")
    val dialogStoragePath: String = "dialog",

    @SerialName("byte_token_divisor")
    val byteTokenDivisor: Float = 4.0f,

    @SerialName("enabled")
    val enabled: Boolean = true,

    @SerialName("compression_threshold")
    val compressionThreshold: Float = 0.8f,

    @SerialName("retention_threshold")
    val retentionThreshold: Float = 0.1f,

    @SerialName("include_thoughts")
    val includeThoughts: Boolean = true,

    @SerialName("recent_tool_results_range")
    val recentToolResultsRange: Int = 2,

    @SerialName("early_message_threshold")
    val earlyMessageThreshold: Int = 3000,

    @SerialName("recent_message_threshold")
    val recentMessageThreshold: Int = 50000,

    @SerialName("file_retention_days")
    val fileRetentionDays: Int = 5,

    @SerialName("exempt_file_extensions")
    val exemptFileExtensions: List<String> = listOf(".md"),

    @SerialName("exempt_tool_names")
    val exemptToolNames: List<String> = listOf("chat_with_agent")
)

