package com.lin.hippyagent.core.mission

import android.content.Context
import com.lin.hippyagent.core.agent.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Mission 管理器
 * 负责 Mission 的创建、执行、状态管理
 */
class MissionManager(
    private val context: Context,
    private val sessionStore: SessionStore
) {
    private val missionsDir by lazy {
        File(context.filesDir, "missions").apply { mkdirs() }
    }

    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * 启动 Mission
     */
    suspend fun startMission(
        sessionId: String,
        agentId: String,
        taskDescription: String,
        verifyCommand: String? = null,
        maxIterations: Int = 20
    ): Result<MissionState> = withContext(Dispatchers.IO) {
        runCatching {
            val taskId = System.currentTimeMillis().toString()
            val workspaceDir = File(context.filesDir, "workspace/$agentId").absolutePath
            
            val config = MissionConfig(
                taskId = taskId,
                taskDescription = taskDescription,
                verifyCommand = verifyCommand,
                maxIterations = maxIterations,
                workspaceDir = workspaceDir,
                sessionId = sessionId
            )

            val missionState = MissionState(config = config)
            saveMissionState(taskId, missionState)
            
            Timber.i("Mission started: $taskId - $taskDescription")
            missionState
        }
    }

    /**
     * 生成 PRD
     */
    suspend fun generatePrd(
        taskId: String,
        prdContent: String
    ): Result<PrdDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val prd = json.decodeFromString<PrdDocument>(prdContent)
            val missionState = loadMissionState(taskId) 
                ?: throw IllegalStateException("Mission not found: $taskId")
            
            val updatedState = missionState.copy(
                prd = prd,
                currentPhase = MissionPhase.EXECUTION_LOOP,
                progress = prd.userStories.map { 
                    UserStoryProgress(storyId = it.id) 
                }
            )
            
            saveMissionState(taskId, updatedState)
            Timber.i("PRD generated for mission: $taskId with ${prd.userStories.size} user stories")
            prd
        }
    }

    /**
     * 更新 User Story 进度
     */
    suspend fun updateStoryProgress(
        taskId: String,
        storyId: String,
        status: StoryStatus,
        iteration: Int,
        result: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val missionState = loadMissionState(taskId)
                ?: throw IllegalStateException("Mission not found: $taskId")
            
            val updatedProgress = missionState.progress.map { progress ->
                if (progress.storyId == storyId) {
                    progress.copy(
                        status = status,
                        currentIteration = iteration,
                        lastResult = result
                    )
                } else {
                    progress
                }
            }
            
            val allCompleted = updatedProgress.all { it.status == StoryStatus.COMPLETED }
            val anyFailed = updatedProgress.any { it.status == StoryStatus.FAILED }
            
            val newState = missionState.copy(
                progress = updatedProgress,
                currentIteration = iteration,
                currentPhase = when {
                    allCompleted -> MissionPhase.COMPLETED
                    anyFailed -> MissionPhase.FAILED
                    else -> missionState.currentPhase
                },
                status = when {
                    allCompleted -> MissionStatus.COMPLETED
                    anyFailed -> MissionStatus.FAILED
                    else -> missionState.status
                }
            )
            
            saveMissionState(taskId, newState)
        }
    }

    /**
     * 取消 Mission
     */
    suspend fun cancelMission(taskId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val missionState = loadMissionState(taskId)
                ?: throw IllegalStateException("Mission not found: $taskId")
            
            val newState = missionState.copy(
                currentPhase = MissionPhase.CANCELLED,
                status = MissionStatus.CANCELLED
            )
            
            saveMissionState(taskId, newState)
            Timber.i("Mission cancelled: $taskId")
        }
    }

    /**
     * 获取 Mission 状态
     */
    suspend fun getMissionState(taskId: String): MissionState? = withContext(Dispatchers.IO) {
        loadMissionState(taskId)
    }

    /**
     * 列出所有 Mission
     */
    suspend fun listMissions(): List<MissionState> = withContext(Dispatchers.IO) {
        missionsDir.listFiles()?.mapNotNull { file ->
            runCatching {
                val content = file.readText()
                json.decodeFromString<MissionState>(content)
            }.getOrNull()
        } ?: emptyList()
    }

    suspend fun deleteMission(taskId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(missionsDir, "$taskId.json")
            if (file.exists()) file.delete()
        }
    }

    /**
     * 保存 Mission 状态
     */
    private fun saveMissionState(taskId: String, state: MissionState) {
        val file = File(missionsDir, "$taskId.json")
        file.writeText(json.encodeToString(state))
    }

    /**
     * 加载 Mission 状态
     */
    private fun loadMissionState(taskId: String): MissionState? {
        val file = File(missionsDir, "$taskId.json")
        return if (file.exists()) {
            runCatching {
                val content = file.readText()
                json.decodeFromString<MissionState>(content)
            }.getOrNull()
        } else {
            null
        }
    }

    /**
     * 获取当前活跃的 Mission
     */
    suspend fun getActiveMission(): MissionState? = withContext(Dispatchers.IO) {
        listMissions().firstOrNull { 
            it.status == MissionStatus.RUNNING 
        }
    }
}

