package com.lin.hippyagent.ui.floatwindow

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lin.hippyagent.core.behavior.BehaviorRecordingController
import com.lin.hippyagent.core.behavior.RecordingState
import com.lin.hippyagent.core.behavior.RecordingUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object BehaviorRecordingFloatWindow {

    private val floatScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    private var windowManager: WindowManager? = null
    private var containerView: LinearLayout? = null
    private var statusTextView: TextView? = null
    private var messageTextView: TextView? = null
    private var isShowing = false

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, controller: BehaviorRecordingController) {
        if (isShowing) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0xE0222222.toInt())
        }
        val statusText = TextView(context).apply {
            text = "录制中 · 事件 0"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
        }
        val messageText = TextView(context).apply {
            text = "最新消息：—"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 11f
            setPadding(0, 8, 0, 8)
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val bookmarkBtn = Button(context).apply {
            text = "收藏当前页"
            setOnClickListener { controller.bookmarkCurrentPage() }
        }
        val stopBtn = Button(context).apply {
            text = "停止录制"
            setOnClickListener {
                controller.stop()
                dismiss()
            }
        }
        buttonRow.addView(bookmarkBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(stopBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        container.addView(statusText)
        container.addView(messageText)
        container.addView(buttonRow)

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
        containerView = container
        statusTextView = statusText
        messageTextView = messageText
        isShowing = true

        collectJob?.cancel()
        collectJob = floatScope.launch {
            controller.uiState.collectLatest { state ->
                updateView(state)
            }
        }
    }

    private fun updateView(state: RecordingUiState) {
        val stateLabel = when (state.state) {
            RecordingState.IDLE -> "空闲"
            RecordingState.RECORDING -> "录制中"
            RecordingState.FINALIZING -> "落盘中"
        }
        val pageLabel = state.currentPageTitle?.let { " · 当前页：$it" } ?: ""
        statusTextView?.text = "$stateLabel · 事件 ${state.eventCount}$pageLabel"
        messageTextView?.text = "最新消息：${state.latestMessage ?: "—"}"
    }

    fun dismiss() {
        collectJob?.cancel()
        collectJob = null
        if (!isShowing) return
        try {
            containerView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        containerView = null
        statusTextView = null
        messageTextView = null
        windowManager = null
        isShowing = false
    }
}
