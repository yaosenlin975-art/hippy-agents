package com.lin.hippyagent.core.agent.session

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_approvals",
    indices = [
        Index("status"),
        Index("agentId"),
        Index("createdAt"),
        Index(value = ["status", "createdAt"])
    ]
)
data class PendingApproval(
    @PrimaryKey val requestId: String,
    val sessionId: String,
    val agentId: String,
    val toolName: String,
    val severity: String = "medium",
    val findingsCount: Int = 0,
    val findingsSummary: String = "",
    val toolParams: String = "{}",
    val timeoutSeconds: Float = 300f,
    val status: String = "pending",
    val createdAt: Long
)
