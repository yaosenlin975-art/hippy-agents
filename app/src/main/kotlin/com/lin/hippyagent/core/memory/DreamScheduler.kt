package com.lin.hippyagent.core.memory

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Data
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 三阶段 Dream 配置 — Light(浅睡)/Deep(深睡)/REM(快速眼动)
 *
 * | 阶段   | 频率     | 目标              | 约束条件              |
 * |-------|---------|-------------------|--------------------|
 * | Light | 每 6 小时 | 去重 + 近期冗余清理      | 空闲                |
 * | Deep  | 每日 1 次 | 记忆优化 + 健康恢复     | 空闲+充电+WiFi       |
 * | REM   | 每周 1 次 | 模式识别 + 知识图谱更新   | 空闲+充电+WiFi+深夜   |
 */

/** 浅睡配置 — 去重清理 */
data class LightDreamConfig(
    val enabled: Boolean = true,
    val intervalHours: Int = 6,
    val dedupSimilarityThreshold: Float = 0.92f,
    val maxAgeDays: Int = 90
)

/** 深睡配置 — 优化 + 健康恢复 */
data class DeepDreamConfig(
    val enabled: Boolean = true,
    val intervalHours: Int = 24,
    val minScore: Float = 0.3f,
    val maxAgeDays: Int? = null,
    val recoveryEnabled: Boolean = true,
    val triggerBelowHealth: Float = 0.4f
)

/** REM 配置 — 模式识别 */
data class RemDreamConfig(
    val enabled: Boolean = true,
    val intervalHours: Int = 168, // 7 天
    val patternMinOccurrences: Int = 3,
    val patternLookbackWeeks: Int = 4,
    val maxPatterns: Int = 20
)

/** 通用执行配置 */
data class DreamExecutionConfig(
    val maxTokens: Int = 4000,
    val timeoutSeconds: Int = 300,
    val requireCharging: Boolean = false,
    val requireWifi: Boolean = false,
    val requireIdle: Boolean = true
)

/** 合并配置 */
data class DreamConfig(
    val light: LightDreamConfig = LightDreamConfig(),
    val deep: DeepDreamConfig = DeepDreamConfig(),
    val rem: RemDreamConfig = RemDreamConfig(),
    val execution: DreamExecutionConfig = DreamExecutionConfig()
)

/** Dream 阶段枚举 */
enum class DreamPhase {
    LIGHT, DEEP, REM
}

object DreamScheduler {
    private const val LIGHT_WORK = "dream_light_periodic"
    private const val DEEP_WORK = "dream_deep_periodic"
    private const val REM_WORK = "dream_rem_periodic"
    private const val ONETIME_WORK = "dream_now"

    fun schedule(context: Context, config: DreamConfig = DreamConfig()) {
        // Light — 每 6 小时，仅要求空闲
        if (config.light.enabled) {
            val lightConstraints = Constraints.Builder()
                .setRequiresDeviceIdle(true)
                .build()

            val lightRequest = PeriodicWorkRequestBuilder<DreamWorker>(
                config.light.intervalHours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(lightConstraints)
                .setInputData(Data.Builder().putString("phase", DreamPhase.LIGHT.name).build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                LIGHT_WORK, ExistingPeriodicWorkPolicy.KEEP, lightRequest
            )
            Timber.i("DreamScheduler: Light scheduled every ${config.light.intervalHours}h")
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(LIGHT_WORK)
        }

        // Deep — 每 24 小时，要求充电+WiFi+空闲
        if (config.deep.enabled) {
            val deepConstraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val deepRequest = PeriodicWorkRequestBuilder<DreamWorker>(
                config.deep.intervalHours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(deepConstraints)
                .setInputData(Data.Builder().putString("phase", DreamPhase.DEEP.name).build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                DEEP_WORK, ExistingPeriodicWorkPolicy.KEEP, deepRequest
            )
            Timber.i("DreamScheduler: Deep scheduled every ${config.deep.intervalHours}h")
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(DEEP_WORK)
        }

        // REM — 每 168 小时(7天)，要求充电+WiFi+空闲
        if (config.rem.enabled) {
            val remConstraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val remRequest = PeriodicWorkRequestBuilder<DreamWorker>(
                config.rem.intervalHours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(remConstraints)
                .setInputData(Data.Builder().putString("phase", DreamPhase.REM.name).build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                REM_WORK, ExistingPeriodicWorkPolicy.KEEP, remRequest
            )
            Timber.i("DreamScheduler: REM scheduled every ${config.rem.intervalHours}h")
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(REM_WORK)
        }
    }

    fun triggerNow(context: Context, phase: DreamPhase = DreamPhase.DEEP) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val request = OneTimeWorkRequestBuilder<DreamWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString("phase", phase.name).build())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "$ONETIME_WORK-${phase.name}", ExistingWorkPolicy.REPLACE, request
        )

        Timber.i("DreamScheduler: triggered $phase now")
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LIGHT_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(DEEP_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(REM_WORK)
        Timber.i("DreamScheduler: all cancelled")
    }
}

