package com.lin.hippyagent.core.agent.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class ReMeLightMemoryConfig(
    @SerialName("summarize_when_compact")
    val summarizeWhenCompact: Boolean = true,

    @SerialName("auto_memory_interval")
    val autoMemoryInterval: Int? = null,

    @SerialName("dream_cron")
    val dreamCron: String = "0 23 * * *",

    @SerialName("auto_memory_search_config")
    val autoMemorySearchConfig: AutoMemorySearchConfig = AutoMemorySearchConfig(),

    @SerialName("embedding_model_config")
    val embeddingModelConfig: EmbeddingModelConfig = EmbeddingModelConfig(),

    @SerialName("rebuild_memory_index_on_start")
    val rebuildMemoryIndexOnStart: Boolean = false,

    @SerialName("recursive_file_watcher")
    val recursiveFileWatcher: Boolean = false,
)

@Serializable
data class AutoMemorySearchConfig(
    val enabled: Boolean = false,

    @SerialName("max_results")
    val maxResults: Int = 2,

    @SerialName("min_score")
    val minScore: Float = 0.3f,

    val timeout: Float = 10.0f,

    @SerialName("enhanced_search_enabled")
    val enhancedSearchEnabled: Boolean = false,

    @SerialName("reflection_enabled")
    val reflectionEnabled: Boolean = true,
)

@Serializable
data class EmbeddingModelConfig(
    val backend: String = "openai",

    @SerialName("api_key")
    val apiKey: String = "",

    @SerialName("base_url")
    val baseUrl: String = "",

    @SerialName("model_name")
    val modelName: String = "",

    val dimensions: Int = 1024,

    @SerialName("enable_cache")
    val enableCache: Boolean = true,

    @SerialName("use_dimensions")
    val useDimensions: Boolean = false,

    @SerialName("max_cache_size")
    val maxCacheSize: Int = 3000,

    @SerialName("max_input_length")
    val maxInputLength: Int = 8192,

    @SerialName("max_batch_size")
    val maxBatchSize: Int = 10,
)

