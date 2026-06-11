package com.lin.hippyagent.core.voice

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.lin.hippyagent.R
import com.lin.hippyagent.core.ondevice.OnDeviceModelManager
import timber.log.Timber
import java.io.File

class STTService(
    private val context: Context,
    private val voiceManager: VoiceExtensionManager,
    private val sttRouter: SttRouter,
    private val litertlmTranscriber: LiteRTLMTranscriber,
    private val onDeviceModelManager: OnDeviceModelManager? = null,
) {
    private val androidBuiltinTranscriber: AndroidBuiltinTranscriber by lazy {
        AndroidBuiltinTranscriber(context)
    }

    /** AudioRecord fallback recorder for MIUI/HyperOS devices */
    private val fallbackRecorder: VoiceRecorder by lazy {
        VoiceRecorder(
            outputDir = File(context.cacheDir, "stt_fallback_recordings"),
            scope = serviceScope
        )
    }

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private var transcriber: Any? = null
    private var listeningJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var activeEngine: SttEngine? = null
    /** true when SpeechRecognizer failed on MIUI and we fell back to AudioRecord */
    private var isFallbackRecording = false

    val isAvailable: Boolean
        get() = sttRouter.isAnyAvailable

    val isAvailableFlow: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = sttRouter.isAvailableFlow

    val engineLabel: String
        get() = sttRouter.currentEngineLabel

    fun startListening(callback: SttCallback) {
        if (_isListening.value) {
            Timber.w("STTService: Already listening")
            return
        }

        when (val engine = sttRouter.resolve()) {
            SttEngine.MOONSHINE -> startMoonshineListening(callback)
            SttEngine.LITERTLM -> startLiteRTLMListening(callback)
            SttEngine.ANDROID_BUILTIN -> startAndroidBuiltinListening(callback)
            SttEngine.NONE -> {
                callback.onError(IllegalStateException("语音输入不可用：所有识别引擎均不可用"))
            }
        }
    }

    fun stopListening() {
        when {
            isFallbackRecording -> {
                // Stop the fallback AudioRecord and transcribe
                isFallbackRecording = false
                listeningJob = serviceScope.launch {
                    try {
                        val result = fallbackRecorder.stopRecording()
                        if (result != null && result.pcmBytes.isNotEmpty()) {
                            val text: String = if (onDeviceModelManager != null) {
                                onDeviceModelManager.transcribeAudio(result.pcmBytes)
                            } else {
                                // No on-device model available — surface a clear placeholder so the user
                                // knows the audio was captured successfully but cannot be transcribed offline.
                                val seconds = (result.durationMs / 1000).toInt()
                                context.getString(R.string.chat_stt_no_model, seconds, result.file.absolutePath)
                            }
                            withContext(Dispatchers.Main) {
                                _isListening.value = false
                                activeEngine = null
                                pendingFallbackCallback?.onFinalResult(SttResult(text = text, isFinal = true))
                                pendingFallbackCallback = null
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                _isListening.value = false
                                activeEngine = null
                                pendingFallbackCallback?.onFinalResult(SttResult(text = "", isFinal = true))
                                pendingFallbackCallback = null
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "STTService: Fallback transcription failed")
                        withContext(Dispatchers.Main) {
                            _isListening.value = false
                            activeEngine = null
                            pendingFallbackCallback?.onError(e)
                            pendingFallbackCallback = null
                        }
                    }
                }
            }
            else -> {
                when (activeEngine) {
                    SttEngine.MOONSHINE -> stopMoonshineListening()
                    SttEngine.LITERTLM -> litertlmTranscriber.stopListening()
                    SttEngine.ANDROID_BUILTIN -> androidBuiltinTranscriber.stopListening()
                    SttEngine.NONE -> { }
                    null -> { }
                }
                _isListening.value = false
                activeEngine = null
            }
        }
    }

    /** Stored callback for the fallback AudioRecord recording path */
    private var pendingFallbackCallback: SttCallback? = null

    private fun startMoonshineListening(callback: SttCallback) {
        if (!voiceManager.isSttAvailable.value || !voiceManager.isDeviceSupported) {
            callback.onError(IllegalStateException("STT 不可用：模型未下载或设备不支持（需 Android 15+）"))
            return
        }

        _isListening.value = true
        activeEngine = SttEngine.MOONSHINE

        listeningJob = serviceScope.launch {
            try {
                val sttModel = voiceManager.state.value.sttModel
                    ?: throw IllegalStateException("STT 模型未下载")
                startMoonshineTranscription(sttModel.modelPath, callback)
            } catch (e: Exception) {
                Timber.e(e, "STTService: Error during listening")
                withContext(Dispatchers.Main) {
                    callback.onError(e)
                }
                _isListening.value = false
            }
        }
    }

    private fun stopMoonshineListening() {
        try {
            transcriber?.let { t ->
                val stopMethod = t.javaClass.getMethod("stop")
                stopMethod.invoke(t)
            }
        } catch (e: Exception) {
            Timber.w(e, "STTService: Error stopping transcriber")
        }

        listeningJob?.cancel()
        listeningJob = null
    }

    private fun startLiteRTLMListening(callback: SttCallback) {
        _isListening.value = true
        activeEngine = SttEngine.LITERTLM
        val wrappedCallback = object : SttCallback {
            override fun onPartialResult(result: SttResult) = callback.onPartialResult(result)
            override fun onFinalResult(result: SttResult) {
                _isListening.value = false
                activeEngine = null
                callback.onFinalResult(result)
            }
            override fun onError(error: Throwable) {
                _isListening.value = false
                activeEngine = null
                callback.onError(error)
            }
        }
        litertlmTranscriber.startListening(wrappedCallback)
    }

    private fun startAndroidBuiltinListening(callback: SttCallback) {
        if (android.content.pm.PackageManager.PERMISSION_GRANTED != context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)) {
            callback.onError(IllegalStateException("缺少麦克风权限，请在系统设置中授予权限"))
            return
        }
        _isListening.value = true
        activeEngine = SttEngine.ANDROID_BUILTIN
        val wrappedCallback = object : SttCallback {
            override fun onPartialResult(result: SttResult) = callback.onPartialResult(result)
            override fun onFinalResult(result: SttResult) {
                _isListening.value = false
                activeEngine = null
                callback.onFinalResult(result)
            }
            override fun onError(error: Throwable) {
                // On MIUI/HyperOS, SpeechRecognizer may fail with ERROR_INSUFFICIENT_PERMISSIONS
                // (or other errors) even though AudioRecord works fine.
                // Always fall back to AudioRecord path so the user isn't left without recording capability.
                Timber.w(error, "STTService: SpeechRecognizer failed (${error.message}), falling back to AudioRecord")
                startFallbackRecording(callback)
            }
        }
        androidBuiltinTranscriber.startListening(wrappedCallback)
    }

    /**
     * Check if we can transcribe via on-device model after AudioRecord fallback completes.
     */
    private fun canTranscribeFallback(): Boolean {
        return onDeviceModelManager != null
    }

    /**
     * Start fallback recording using AudioRecord (works on MIUI/HyperOS).
     * The recording continues until stopListening() is called.
     * If onDeviceModelManager is available, will transcribe after stop;
     * otherwise returns a placeholder message with the recorded audio file path.
     */
    private fun startFallbackRecording(callback: SttCallback) {
        isFallbackRecording = true
        pendingFallbackCallback = callback
        // Keep _isListening = true and activeEngine = ANDROID_BUILTIN
        // so that the UI still shows the listening state

        val outputFile = fallbackRecorder.startRecording()
        if (outputFile == null) {
            // AudioRecord also failed — give up
            isFallbackRecording = false
            _isListening.value = false
            activeEngine = null
            pendingFallbackCallback = null
            callback.onError(IllegalStateException("无法启动录音（AudioRecord 初始化失败），请检查：1) 系统设置中麦克风权限 2) 是否有其他 App 占用麦克风 3) 设备硬件是否正常"))
            return
        }

        // Show "listening" state
        serviceScope.launch {
            withContext(Dispatchers.Main) {
                callback.onPartialResult(SttResult(text = context.getString(R.string.chat_listening), isFinal = false))
            }
        }

        if (!canTranscribeFallback()) {
            // No on-device model — schedule a forced stop after a short window so the user
            // gets something usable (the recorded file is saved on disk for later upload / manual transcription)
            serviceScope.launch {
                delay(NATIVE_FALLBACK_MAX_DURATION_MS)
                if (isFallbackRecording) {
                    Timber.w("STTService: native fallback reached max duration without on-device model, finalizing")
                    stopListening()
                }
            }
        }

        Timber.d("STTService: Fallback AudioRecord recording started, canTranscribeFallback=${canTranscribeFallback()}")
    }

    private suspend fun startMoonshineTranscription(modelPath: String, callback: SttCallback) {
        try {
            val micTranscriberClass = Class.forName("ai.moonshine.voice.MicTranscriber")
            val transcriberInstance = micTranscriberClass.getDeclaredConstructor().newInstance()

            val loadMethod = micTranscriberClass.getMethod("loadFromFiles", String::class.java)
            loadMethod.invoke(transcriberInstance, modelPath)

            this.transcriber = transcriberInstance

            val eventListener = java.lang.reflect.Proxy.newProxyInstance(
                micTranscriberClass.classLoader,
                arrayOf(Class.forName("ai.moonshine.voice.TranscriberEventListener"))
            ) { _, method, args ->
                when (method.name) {
                    "onEvent" -> {
                        val event = args?.firstOrNull()
                        handleTranscriptEvent(event, callback)
                    }
                }
                null
            }

            val addEventListenerMethod = micTranscriberClass.getMethod("addEventListener", eventListener.javaClass.interfaces[0])
            addEventListenerMethod.invoke(transcriberInstance, eventListener)

            val startMethod = micTranscriberClass.getMethod("start")
            startMethod.invoke(transcriberInstance)

            withContext(Dispatchers.Main) {
                callback.onPartialResult(SttResult(text = context.getString(R.string.chat_listening), isFinal = false))
            }

            while (_isListening.value) {
                delay(100)
            }

            val stopMethod = micTranscriberClass.getMethod("stop")
            stopMethod.invoke(transcriberInstance)

        } catch (e: ClassNotFoundException) {
            Timber.e(e, "STTService: Moonshine SDK not found on classpath")
            withContext(Dispatchers.Main) {
                callback.onError(IllegalStateException("Moonshine SDK 未加载，请确认设备为 Android 15+ (API 35)"))
            }
            _isListening.value = false
        } catch (e: Exception) {
            Timber.e(e, "STTService: Moonshine transcription failed")
            withContext(Dispatchers.Main) {
                callback.onError(e)
            }
            _isListening.value = false
        }
    }

    private fun handleTranscriptEvent(event: Any?, callback: SttCallback) {
        if (event == null) return

        try {
            val eventClass = event.javaClass
            val eventName = eventClass.simpleName

            val getLineMethod = eventClass.getMethod("getLine")
            val line = getLineMethod.invoke(event) ?: return

            val getTextMethod = line.javaClass.getMethod("getText")
            val text = getTextMethod.invoke(line) as? String ?: return

            when {
                eventName.contains("LineCompleted") -> {
                    serviceScope.launch {
                        withContext(Dispatchers.Main) {
                            callback.onFinalResult(SttResult(text = text, isFinal = true))
                        }
                    }
                }
                eventName.contains("LineTextChanged") || eventName.contains("LineUpdated") -> {
                    serviceScope.launch {
                        withContext(Dispatchers.Main) {
                            callback.onPartialResult(SttResult(text = text, isFinal = false))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "STTService: Error handling transcript event")
        }
    }

    fun release() {
        stopListening()
        try {
            transcriber?.let { t ->
                val freeMethod = t.javaClass.getMethod("free")
                freeMethod.invoke(t)
            }
        } catch (e: Exception) {
        }
        transcriber = null
        litertlmTranscriber.release()
        androidBuiltinTranscriber.release()
        serviceScope.cancel()
    }

    companion object {
        /**
         * Max duration to keep recording in the native AudioRecord-only fallback path
         * when no on-device STT model is available. Without this guard the user would
         * have to manually stop or wait for the 120s recorder timeout.
         */
        private const val NATIVE_FALLBACK_MAX_DURATION_MS = 30_000L
    }
}

data class SttResult(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float = 1f
)

interface SttCallback {
    fun onPartialResult(result: SttResult)
    fun onFinalResult(result: SttResult)
    fun onError(error: Throwable)
}
