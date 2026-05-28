package com.lin.hippyagent.core.accessibility

import android.media.projection.MediaProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

enum class CompanionState {
    IDLE,
    LISTENING,
    PROCESSING,
    EXECUTING
}

class ScreenCompanionController(
    private val frameBuffer: VisionFrameBuffer,
    private val voiceVisionHub: LocalVoiceVisionHub,
    private val vlmProvider: VlmProvider
) {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val _state = MutableStateFlow(CompanionState.IDLE)
    val state: StateFlow<CompanionState> = _state.asStateFlow()

    private var frameSampler: ScreenFrameSampler? = null
    private var mediaProjection: MediaProjection? = null

    private val overlayController = CompanionOverlayController()

    fun enterCompanionMode(projection: MediaProjection) {
        if (_state.value != CompanionState.IDLE) {
            Timber.w("Already in companion mode, state=%s", _state.value)
            return
        }

        mediaProjection = projection
        frameBuffer.clear()

        scope.launch {
            delay(800)

            val sampler = ScreenFrameSampler(frameBuffer, fps = 1, jpegQuality = 80)
            frameSampler = sampler
            sampler.start(projection)

            overlayController.show()
            _state.value = CompanionState.LISTENING
            Timber.i("Companion mode entered")
        }
    }

    fun exitCompanionMode() {
        frameSampler?.stop()
        frameSampler = null
        mediaProjection?.stop()
        mediaProjection = null
        overlayController.hide()
        frameBuffer.clear()
        _state.value = CompanionState.IDLE
        Timber.i("Companion mode exited")
    }

    fun handleVoiceResult(sttText: String) {
        if (_state.value != CompanionState.LISTENING) {
            Timber.w("handleVoiceResult called in state=%s, ignoring", _state.value)
            return
        }

        _state.value = CompanionState.PROCESSING

        scope.launch {
            try {
                val route = voiceVisionHub.routeDecision(
                    sttText,
                    hasVisualMode = true,
                    hasCamera = false
                )

                Timber.i("Voice route decision: %s for '%s'", route, sttText.take(30))

                when (route) {
                    VoiceRoute.VISUAL_QUERY -> {
                        val frames = frameBuffer.selectFramesForQuery(sttText)
                        val result = voiceVisionHub.processVisualQuery(sttText, frames, vlmProvider)
                        onVisualQueryResult(result)
                    }
                    VoiceRoute.AGENT_TASK -> {
                        launchAgentTask(sttText)
                    }
                    VoiceRoute.FORWARD_TEXT -> {
                        val reply = voiceVisionHub.processTextQuery(sttText)
                        onTextResult(reply)
                    }
                }
            } catch (e: CancellationException) {
                Timber.d("Voice result processing cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Voice result processing failed")
            } finally {
                if (_state.value == CompanionState.PROCESSING) {
                    _state.value = CompanionState.LISTENING
                }
            }
        }
    }

    fun launchAgentTask(taskDescription: String) {
        _state.value = CompanionState.EXECUTING
        Timber.i("Launching agent task: %s", taskDescription.take(50))

        scope.launch {
            try {
                onAgentTaskStarted(taskDescription)
            } catch (e: CancellationException) {
                Timber.d("Agent task cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Agent task failed")
            } finally {
                if (_state.value == CompanionState.EXECUTING) {
                    _state.value = CompanionState.LISTENING
                }
            }
        }
    }

    private fun onVisualQueryResult(result: VoiceVlmResult) {
        Timber.i("Visual query result: reply=%s, action=%s", result.reply.take(50), result.directAction)
        if (result.jsonCommand != null) {
            Timber.i("JsonCommand: action=%s, params=%s", result.jsonCommand.action, result.jsonCommand.params)
        }
    }

    private fun onTextResult(reply: String) {
        Timber.i("Text result: %s", reply.take(50))
    }

    private fun onAgentTaskStarted(taskDescription: String) {
        Timber.i("Agent task started: %s", taskDescription.take(50))
    }
}

internal class CompanionOverlayController {

    private var isShowing = false

    fun show() {
        if (isShowing) return
        isShowing = true
        Timber.d("Companion overlay shown")
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        Timber.d("Companion overlay hidden")
    }
}
