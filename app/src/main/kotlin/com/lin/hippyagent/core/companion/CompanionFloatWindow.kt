package com.lin.hippyagent.core.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.lin.hippyagent.R

object CompanionFloatWindow {
    private var windowManager: WindowManager? = null
    private var floatView: LinearLayout? = null
    private var statusText: TextView? = null
    private var isShowing = false

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (isShowing) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0xE0222222.toInt())
        }

        statusText = TextView(context).apply {
            text = context.getString(R.string.companion_status_ready)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
        }
        container.addView(statusText)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        wm.addView(container, params)
        floatView = container
        isShowing = true
    }

    fun dismiss() {
        if (!isShowing) return
        try {
            floatView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        floatView = null
        statusText = null
        isShowing = false
    }

    fun updateStatus(state: CompanionController.CompanionUiState) {
        statusText?.text = state.statusText
    }
}
