package com.lin.hippyagent.core.agent.config

import java.time.Instant

data class CoreFile(
    val filename: String,
    val size: Long = 0,
    val lastModified: Instant = Instant.now(),
    val enabled: Boolean = true,
    val exists: Boolean = true
)

enum class CoreFileType(val filename: String, val defaultEnabled: Boolean) {
    RULES("RULES.md", true),
    AGENTS("AGENTS.md", true),
    SOUL("SOUL.md", true),
    PROFILE("PROFILE.md", true),
    MEMORY("MEMORY.md", false),
    HEARTBEAT("HEARTBEAT.md", false),
    BOOTSTRAP("BOOTSTRAP.md", false);

    companion object {
        fun fromFilename(filename: String): CoreFileType? =
            entries.find { it.filename == filename }
    }
}

