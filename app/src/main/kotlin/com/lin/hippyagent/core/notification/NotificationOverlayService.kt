package com.lin.hippyagent.core.notification

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
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotificationOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "notification_overlay_channel"
        private const val NOTIFICATION_ID = 2002
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_MESSAGE = "extra_message"
        private const val AUTO_DISMISS_MS = 30_000L

        fun show(context: Context, title: String, message: String) {
            if (!android.provider.Settings.canDrawOverlays(context)) return
            val intent = Intent(context, NotificationOverlayService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlayView: android.view.View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        if (intent != null) {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
            val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
            showOverlay(title, message)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_overlay_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_overlay_service))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showOverlay(title: String, message: String) {
        removeOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ctx = this

        val scrollView = ScrollView(ctx).apply {
            setBackgroundColor(0xE6333333.toInt())
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        val titleView = TextView(ctx).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
        }
        container.addView(titleView)

        val messageView = TextView(ctx).apply {
            text = message
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
            setPadding(0, 8, 0, 0)
        }
        container.addView(messageView)

        val buttonBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
            gravity = Gravity.END
        }

        val confirmBtn = Button(ctx).apply {
            text = getString(R.string.ok)
            setTextColor(0xFF66FF66.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { dismissAndStop() }
        }
        buttonBar.addView(confirmBtn)
        container.addView(buttonBar)

        scrollView.addView(container)

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

        windowManager.addView(scrollView, params)
        overlayView = scrollView

        scope.launch {
            delay(AUTO_DISMISS_MS)
            dismissAndStop()
        }
    }

    private fun dismissAndStop() {
        removeOverlay()
        stopSelf()
    }

    private fun removeOverlay() {
        overlayView?.let {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            runCatching { windowManager.removeView(it) }
            overlayView = null
        }
    }
}
