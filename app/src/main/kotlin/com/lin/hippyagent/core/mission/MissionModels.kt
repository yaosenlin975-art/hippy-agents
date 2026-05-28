package com.lin.hippyagent.core.mission

import kotlinx.serialization.Serializable

/**
 * Mission 阶段枚举
 */
enum class MissionPhase {
    PRD_GENERATION,    // PRD 生成阶段
    EXECUTION_LOOP,    // 执行循环阶段
    COMPLETED,         // 已完成
    FAILED,            // 失败
    CANCELLED          // 已取消
}

/**
 * Mission 状态枚举
 */
enum class MissionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Mission 配置
 */
@Serializable
data class MissionConfig(
    val taskId: String,
    val taskDescription: String,
    val verifyCommand: String? = null,
    val maxIterations: Int = 20,
    val workspaceDir: String,
    val sessionId: String
)

/**
 * PRD 文档结构
 */
@Serializable
data class PrdDocument(
    val title: String,
    val description: String,
    val userStories: List<UserStory> = emptyList()
)

/**
 * User Story
 */
@Serializable
data class UserStory(
    val id: String,
    val title: String,
    val description: String,
    val acceptanceCriteria: List<String> = emptyList()
)

/**
 * User Story 进度
 */
@Serializable
data class UserStoryProgress(
    val storyId: String,
    val status: StoryStatus = StoryStatus.PENDING,
    val currentIteration: Int = 0,
    val lastResult: String? = null,
    val passedChecks: List<String> = emptyList()
)

/**
 * Story 状态
 */
enum class StoryStatus {
    PENDING,      // 待执行
    IN_PROGRESS,  // 执行中
    COMPLETED,    // 已完成
    FAILED        // 失败
}

/**
 * Mission 状态
 */
@Serializable
data class MissionState(
    val config: MissionConfig,
    val currentPhase: MissionPhase = MissionPhase.PRD_GENERATION,
    val currentIteration: Int = 0,
    val prd: PrdDocument? = null,
    val progress: List<UserStoryProgress> = emptyList(),
    val status: MissionStatus = MissionStatus.RUNNING
) {
    val completedStories: Int get() = progress.count { it.status == StoryStatus.COMPLETED }
    val totalStories: Int get() = prd?.userStories?.size ?: progress.size
}

