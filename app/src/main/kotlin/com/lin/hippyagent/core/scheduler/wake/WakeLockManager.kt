package com.lin.hippyagent.core.scheduler.wake

import android.content.Context
import android.os.PowerManager
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * 屏幕唤醒锁管理器.
 *
 * 设计:
 * - 按 token 持有 WakeLock, 同 token 重复 acquire 安全 (引用计数=false, 仅记录首次)
 * - 每次最多持有 timeoutMs, 超时自动 release (防泄漏)
 * - 使用 SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP (亮屏 + 唤醒)
 *   注: SCREEN_BRIGHT_WAKE_LOCK 自 API 17 已废弃, 但仍可用;
 *   现代替代方案是 Activity.setTurnScreenOn(true) (见 ScheduledTaskWakeActivity),
 *   WakeLock 作为 Activity 启动前的 early-wakeup 桥接.
 *
 * 合规: 严格遵守 coding.md "Map缓存无清理 → ✅ remove用完即清",
 * acquire 后必配对 release, 超时兜底.
 */
class WakeLockManager(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val locks = ConcurrentHashMap<String, PowerManager.WakeLock>()

    /**
     * 获取屏幕唤醒锁.
     * @param token 调用方唯一标识, 同 token 重复调用安全 (幂等)
     * @param timeoutMs 超时自动 release, 默认 5s
     */
    fun acquireScreenWakeLock(token: String, timeoutMs: Long = 5000L) {
        val existing = locks[token]
        if (existing != null) {
            Timber.d("WakeLock already held for token=$token")
            return
        }
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "HippyAgent::ScreenWake::$token"
        )
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(timeoutMs)  // 超时兜底, 防泄漏
        locks[token] = wakeLock
        Timber.i("Acquired screen wake lock: token=$token, timeout=${timeoutMs}ms")
    }

    /**
     * 释放唤醒锁. 必须与 acquire 配对. runCatching 各走各路.
     */
    fun releaseScreenWakeLock(token: String) {
        val wakeLock = locks.remove(token) ?: return
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
        }.onFailure { e ->
            Timber.w(e, "Failed to release wake lock: token=$token")
        }
        Timber.d("Released screen wake lock: token=$token")
    }

    /**
     * 判断屏幕是否处于交互状态 (亮屏).
     */
    fun isInteractive(): Boolean = powerManager.isInteractive
}
