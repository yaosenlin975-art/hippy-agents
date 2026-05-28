package com.lin.hippyagent.core.companion

import android.app.Application
import com.lin.hippyagent.core.accessibility.ScreenFrameSampler
import com.lin.hippyagent.core.accessibility.VisionFrameBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

object CompanionController {

    data class CompanionUiState(
        val isActive: Boolean = false,
        val isListening: Boolean = false,
        val isProcessing: Boolean = false,
        val isAgentRunning: Boolean = false,
        val statusText: String = "屏内替身 · 就绪"
    )

    private val _uiState = MutableStateFlow(CompanionUiState())
    val uiState: StateFlow<CompanionUiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ttsManager: CompanionTtsManager? = null
    private var frameBuffer: VisionFrameBuffer? = null
    private var frameSampler: ScreenFrameSampler? = null
    private var sessionId: String? = null

    fun enterCompanionMode(application: Application, sessionId: String): Boolean {
        if (_uiState.value.isActive) return true

        try {
            CompanionFloatWindow.show(application)
            ttsManager = CompanionTtsManager(application).also { it.initialize() }
            frameBuffer = VisionFrameBuffer()
            frameSampler = ScreenFrameSampler(frameBuffer!!)

            this.sessionId = sessionId
            _uiState.value = CompanionUiState(
                isActive = true,
                statusText = "屏内替身 · 就绪"
            )
            Timber.i("CompanionController: entered companion mode")
            return true
        } catch (e: Exception) {
            Timber.e(e, "CompanionController: failed to enter companion mode")
            exitCompanionMode()
            return false
        }
    }

    fun startVoiceCapture() {
        _uiState.value = _uiState.value.copy(
            isListening = true,
            statusText = "屏内替身 · 听写中..."
        )
    }

    fun stopVoiceCapture() {
        _uiState.value = _uiState.value.copy(
            isListening = false,
            statusText = "屏内替身 · 处理中..."
        )
    }

    fun onAgentStarted() {
        _uiState.value = _uiState.value.copy(
            isAgentRunning = true,
            statusText = "屏内替身 · 执行中..."
        )
    }

    fun onAgentFinished(reply: String?) {
        _uiState.value = _uiState.value.copy(
            isProcessing = false,
            isAgentRunning = false,
            statusText = "屏内替身 · 就绪"
        )
        if (!reply.isNullOrBlank()) {
            ttsManager?.speak(reply)
        }
    }

    fun exitCompanionMode() {
        CompanionFloatWindow.dismiss()
        ttsManager?.shutdown()
        ttsManager = null
        frameSampler?.stop()
        frameSampler = null
        frameBuffer?.clear()
        frameBuffer = null
        sessionId = null
        _uiState.value = CompanionUiState()
        Timber.i("CompanionController: exited companion mode")
    }
}
