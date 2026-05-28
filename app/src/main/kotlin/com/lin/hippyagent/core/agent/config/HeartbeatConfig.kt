package com.lin.hippyagent.core.agent.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class HeartbeatConfig(
    val enabled: Boolean = false,
    val every: String = "6h",
    val target: String = "main",

    @SerialName("active_hours")
    val activeHours: ActiveHoursConfig? = null
)

@Serializable
data class ActiveHoursConfig(
    val start: String = "08:00",
    val end: String = "22:00"
)

