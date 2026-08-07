package com.lin.hippyagent.core.scheduler.wake

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.lin.hippyagent.ui.ScheduledTaskWakeActivity
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 息屏唤醒协调器.
 *
 * 设计参考: X-OmniClaw ScreenWakeCoordinator + ScheduledTaskWakeActivity
 *
 * 流程:
 * 1. 幂等去重: dispatchToken = "$taskId:$alarmReceivedAtMs", 已派发过的 token 跳过
 * 2. 检查 isInteractive: 已亮屏直接执行 onReady
 * 3. 息屏: acquireScreenWakeLock → 启动透明 WakeActivity
 * 4. 延迟 1200ms 等无障碍服务恢复后执行 onReady
 * 5. 任务派发完成后释放 wake lock (WakeActivity 自身 3500ms 后 finish)
 *
 * 幂等去重: dispatchedTokens 上限 10000, 超限清空重建
 * (Set去重有上限 → 上限10k, 超限淘汰)
 */
class ScreenWakeCoordinator(
    private val context: Context,
    private val wakeLockManager: WakeLockManager
) {
    /** 已派发的 token 集合, 上限 10000, 超限淘汰 */
    private val dispatchedTokens: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val dispatchedTokensLimit = 10000

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 确保设备处于可执行任务的状态.
     *
     * @param dispatchToken 派发令牌, 格式 "$taskId:$alarmReceivedAtMs", 用于幂等去重
     * @param onReady 设备就绪后回调 (在 main 线程执行, 调用方需自行切协程)
     */
    fun ensureAwake(dispatchToken: String, onReady: () -> Unit) {
        // 1. 幂等去重
        if (!dispatchedTokens.add(dispatchToken)) {
            Timber.w("Duplicate dispatch token, skipping: $dispatchToken")
            return
        }
        // 淘汰: 超过 10000 个时清空重建
        if (dispatchedTokens.size > dispatchedTokensLimit) {
            dispatchedTokens.clear()
            dispatchedTokens.add(dispatchToken)
        }

        // 2. 已亮屏: 直接执行
        if (wakeLockManager.isInteractive()) {
            Timber.d("Screen already on, executing directly: $dispatchToken")
            onReady()
            return
        }

        // 3. 息屏: best-effort 唤醒
        Timber.i("Screen off, acquiring wake lock and launching WakeActivity: $dispatchToken")
        wakeLockManager.acquireScreenWakeLock(dispatchToken, timeoutMs = 8000L)

        // 启动透明 Activity (带 showWhenLocked + turnScreenOn)
        val intent = Intent(context, ScheduledTaskWakeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ScheduledTaskWakeActivity.EXTRA_DISPATCH_TOKEN, dispatchToken)
        }
        runCatching {
            mainHandler.post { context.startActivity(intent) }
        }.onFailure { e ->
            Timber.w(e, "Failed to start WakeActivity, executing without screen wake: $dispatchToken")
            // 降级: 仍然执行任务 (可能因无障碍未就绪失败, 但有重试)
            onReady()
            wakeLockManager.releaseScreenWakeLock(dispatchToken)
            return
        }

        // 4. 延迟 1200ms 等无障碍服务恢复后执行任务
        mainHandler.postDelayed({
            try {
                onReady()
            } finally {
                // 5. 任务派发完成后释放 wake lock (WakeActivity 自身 3500ms 后 finish)
                wakeLockManager.releaseScreenWakeLock(dispatchToken)
            }
        }, 1200L)
    }
}
