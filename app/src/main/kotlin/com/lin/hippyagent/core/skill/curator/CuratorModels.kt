package com.lin.hippyagent.core.skill.curator

import com.lin.hippyagent.core.memory.DreamPhase
import com.lin.hippyagent.core.pool.FastId
import kotlinx.serialization.Serializable

/**
 * Curator 系统数据模型
 *
 * 参考: Hermes curator/models.py — SkillManifest / TriggerCondition / WorkflowStep
 */
@Serializable
data class CuratorSkillManifest(
    val id: String = "auto_${FastId.nextShort()}",
    val name: String,
    val description: String,
    val triggers: CuratorTriggerCondition = CuratorTriggerCondition(),
    val tools: List<CuratorToolBinding> = emptyList(),
    val workflow: List<CuratorWorkflowStep> = emptyList(),
    val usageCount: Int = 0,
    val lastUsedAt: Long? = null,
    val confidence: Float = 0.5f,
    val createdAt: Long = System.currentTimeMillis(),
    val version: Int = 1
)

@Serializable
data class CuratorTriggerCondition(
    val keywords: List<String> = emptyList(),
    val intentMatch: String = "",
    val toolPattern: List<String> = emptyList()
)

@Serializable
data class CuratorToolBinding(
    val name: String,
    val parameterMapping: Map<String, String> = emptyMap(),
    val required: Boolean = true
)

@Serializable
data class CuratorWorkflowStep(
    val order: Int,
    val tool: String,
    val description: String = "",
    val parameterTemplate: Map<String, String> = emptyMap()
)

/** 执行历史 */
data class ExecutionHistory(
    val id: String = FastId.next(),
    val agentId: String = "",
    val sessionId: String = "",
    val query: String = "",
    val tools: List<ToolCallRecord> = emptyList(),
    val success: Boolean = true,
    val durationMs: Long = 0L,
    val tokenUsage: Long = 0L,
    val isOneOff: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/** 工具调用记录 */
data class ToolCallRecord(
    val toolName: String,
    val arguments: Map<String, Any> = emptyMap(),
    val result: String? = null,
    val order: Int = 0,
    val durationMs: Long = 0L,
    val success: Boolean = true
)

/** Curator 阶段报告 */
data class CuratorReport(
    val phase: DreamPhase,
    val extracted: Int = 0,
    val merged: Int = 0,
    val optimized: Int = 0,
    val archived: Int = 0,
    val details: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

/** 合并结果 */
data class MergeResult(
    val sourceIds: List<String>,
    val merged: CuratorSkillManifest,
    val similarity: Float
)

/** 错误模式 */
data class ErrorPattern(
    val toolName: String,
    val errorMessage: String,
    val frequency: Int = 1
)
