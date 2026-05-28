package com.lin.hippyagent.core.agent.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MCPConfig(
    val clients: Map<String, MCPClientConfig> = emptyMap()
)

@Serializable
data class MCPClientConfig(
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val transport: String = "stdio",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String = "",
    val readTimeoutSeconds: Float = 300f,
    @SerialName("execution_timeout_ms")
    val executionTimeoutMs: Long = 60_000L
)

