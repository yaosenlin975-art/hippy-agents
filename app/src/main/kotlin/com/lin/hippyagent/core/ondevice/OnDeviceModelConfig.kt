package com.lin.hippyagent.core.ondevice

import kotlinx.serialization.Serializable

@Serializable
data class OnDeviceModelConfig(
    val id: String,
    val name: String,
    val description: String = "",
    val downloadUrl: String,
    val originalUrl: String = "",
    val fileSize: Long,
    val sha256: String = "",
    val capabilities: Set<OnDeviceCapability> = setOf(OnDeviceCapability.TEXT),
    val minRamMb: Int = 8192,
    val recommendedRamMb: Int = 12288,
    val contextWindow: Int = 128000,
    val maxTokens: Int = 8192,
)

@Serializable
enum class OnDeviceCapability {
    TEXT, VISION, AUDIO, THINKING, TOOL_CALL
}
