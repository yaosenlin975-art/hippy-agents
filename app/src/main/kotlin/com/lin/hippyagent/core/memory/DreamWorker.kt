package com.lin.hippyagent.core.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import timber.log.Timber
import java.util.Calendar

/**
 * Dream Memory WorkManager Worker — 支持三阶段
 *
 * 阶段通过 inputData 的 "phase" 字段传入：
 * - LIGHT: 去重 + 近期冗余清理（6h 间隔）
 * - DEEP: 记忆优化 + 健康恢复（24h 间隔）
 * - REM: 模式识别 + 知识图谱更新（168h 间隔）
 */
class DreamWorker(
    context: Context,
    params: WorkerParameters,
    private val database: com.lin.hippyagent.core.agent.session.AppDatabase? = null,
    private val dreamEntityBridge: com.lin.hippyagent.core.knowledge.DreamEntityBridge? = null
) : CoroutineWorker(context, params) {

    private val dreamManager by lazy {
        DreamMemoryManager(
            context,
            com.lin.hippyagent.core.agent.AgentMdManager(context),
            database = database,
            dreamEntityBridge = dreamEntityBridge
        )
    }

    override suspend fun doWork(): Result {
        val phaseName = inputData.getString("phase") ?: DreamPhase.DEEP.name
        val phase = try {
            DreamPhase.valueOf(phaseName)
        } catch (e: Exception) {
            DreamPhase.DEEP
        }

        if (phase == DreamPhase.REM) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (hour < 1 || hour >= 5) {
                Timber.i("DreamWorker: REM deferred — current hour $hour not in 01:00-05:00 window")
                return Result.retry()
            }
        }

        return try {
            Timber.i("DreamWorker: Starting $phase dream optimization")
            val result = when (phase) {
                DreamPhase.LIGHT -> dreamManager.triggerLightDream()
                DreamPhase.DEEP -> dreamManager.triggerDeepDream()
                DreamPhase.REM -> dreamManager.triggerRemDream()
            }

            result.fold(
                onSuccess = {
                    Timber.i("DreamWorker: $phase dream completed, optimized ${it.optimizedMemories} memories")
                    Result.success()
                },
                onFailure = { e ->
                    Timber.e(e, "DreamWorker: $phase dream failed")
                    Result.retry()
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "DreamWorker: Unexpected error in $phase dream")
            Result.retry()
        }
    }
}

