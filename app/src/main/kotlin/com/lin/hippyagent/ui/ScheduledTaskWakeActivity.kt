package com.lin.hippyagent.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.lin.hippyagent.R
import timber.log.Timber

/**
 * 定时任务唤醒用的透明 Activity.
 *
 * 特性:
 * - showWhenLocked: 锁屏时显示
 * - turnScreenOn: 自动亮屏
 * - 3500ms 后自动 finish, 不阻塞用户
 *
 * 设计依据: Android 8+ 推荐用 turnScreenOn + setShowWhenLocked 替代
 * 已废弃的 FLAG_SHOW_WHEN_LOCKED / FLAG_TURN_SCREEN_ON / FLAG_DISMISS_KEYGUARD.
 *
 * 注: 此 Activity 不解除锁屏 (有密码不会绕过), 仅亮屏.
 */
class ScheduledTaskWakeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scheduled_task_wake)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val token = intent.getStringExtra(EXTRA_DISPATCH_TOKEN) ?: "unknown"
        Timber.i("WakeActivity created, token=$token")

        // 3500ms 后自动 finish
        handler.postDelayed({
            if (!isFinishing) {
                Timber.d("WakeActivity auto-finishing, token=$token")
                finish()
            }
        }, 3500L)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val EXTRA_DISPATCH_TOKEN = "dispatch_token"
    }
}
