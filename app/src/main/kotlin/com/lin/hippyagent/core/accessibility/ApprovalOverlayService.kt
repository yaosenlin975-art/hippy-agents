package com.lin.hippyagent.core.accessibility

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.lin.hippyagent.ui.MainActivity
import com.lin.hippyagent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ApprovalOverlayService : Service(), KoinComponent {

    companion object {
        var isRunning = false
            private set

        private const val CHANNEL_ID = "approval_overlay_channel"
        private const val NOTIFICATION_ID = 2001

        /** 启动审批覆盖层服务（前台服务，可在其他应用上方显示） */
        fun start(context: Context) {
            val intent = Intent(context, ApprovalOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止审批覆盖层服务 */
        fun stop(context: Context) {
            context.stopService(Intent(context, ApprovalOverlayService::class.java))
        }
    }

    private val actionApprover: ActionApprover by inject()
    // Service 中的 UI 操作（添加/移除浮窗）必须在主线程执行
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlayView: android.view.View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // 前台服务必须先显示通知，否则 Android 12+ 会抛出 ForegroundServiceStartNotAllowedException
        startForeground(NOTIFICATION_ID, createNotification())
        scope.launch {
            actionApprover.pendingRequest.collectLatest { request ->
                if (request != null) {
                    showApprovalOverlay(request)
                } else {
                    removeOverlay()
                }
            }
        }
    }

    override fun onDestroy() {
        removeOverlay()
        isRunning = false
        super.onDestroy()
    }

    /**
     * 创建前台服务通知 — 用户可见的常驻通知，表明审批覆盖层正在运行
     */
    private fun createNotification(): Notification {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "审批覆盖层",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Agent 操作审批悬浮窗服务"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Agent 审批服务运行中")
            .setContentText("等待操作审批...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    @SuppressLint("SetTextI18n")
    private fun showApprovalOverlay(request: ApprovalRequest) {
        removeOverlay()

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ctx = this

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0xE6333333.toInt())
        }

        val riskEmoji = when (request.riskLevel) {
            RiskLevel.HIGH -> "🔴"
            RiskLevel.MEDIUM -> "🟡"
            else -> "🟢"
        }

        val titleView = TextView(ctx).apply {
            text = "$riskEmoji Agent 请求操作: ${request.action}${request.target?.let { " → $it" } ?: ""}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }
        container.addView(titleView)

        if (request.value != null) {
            val detailView = TextView(ctx).apply {
                text = "内容: ${request.value.take(50)}"
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 12f
                setPadding(0, 4, 0, 0)
            }
            container.addView(detailView)
        }

        val buttonBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
            gravity = Gravity.END
        }

        val denyBtn = Button(ctx).apply {
            text = "拒绝"
            setTextColor(0xFFFF6666.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                actionApprover.respond(ApprovalResult(approved = false))
            }
        }
        buttonBar.addView(denyBtn)

        val allowOnceBtn = Button(ctx).apply {
            text = "允许本次"
            setTextColor(0xFF66FF66.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                actionApprover.respond(ApprovalResult(approved = true, duration = ApprovalDuration.ONCE))
            }
        }
        buttonBar.addView(allowOnceBtn)

        if (request.riskLevel == RiskLevel.MEDIUM) {
            val allowTempBtn = Button(ctx).apply {
                text = "允许5分钟"
                setTextColor(0xFF66CCFF.toInt())
                setBackgroundColor(0x00000000)
                setOnClickListener {
                    actionApprover.respond(ApprovalResult(approved = true, duration = ApprovalDuration.MINUTES_5))
                }
            }
            buttonBar.addView(allowTempBtn)
        }

        container.addView(buttonBar)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }

        windowManager.addView(container, params)
        overlayView = container
    }

    private fun removeOverlay() {
        overlayView?.let {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.removeView(it)
            overlayView = null
        }
    }
}

