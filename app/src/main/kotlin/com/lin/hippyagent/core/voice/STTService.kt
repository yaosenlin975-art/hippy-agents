package com.lin.hippyagent.core.voice

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.lin.hippyagent.R
import timber.log.Timber
import java.io.File

class STTService(
    private val context: Context,
    private val voiceManager: VoiceExtensionManager,
    private val sttRouter: SttRouter,
    private val litertlmTranscriber: LiteRTLMTranscriber,
) {
    private val androidBuiltinTranscriber: AndroidBuiltinTranscriber by lazy {
        AndroidBuiltinTranscriber(context)
    }

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private var transcriber: Any? = null
    private var listeningJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var activeEngine: SttEngine? = null

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
                _isListening.value = false
                activeEngine = null
                callback.onError(error)
            }
        }
        androidBuiltinTranscriber.startListening(wrappedCallback)
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
            // ignore
        }
        transcriber = null
        litertlmTranscriber.release()
        androidBuiltinTranscriber.release()
        serviceScope.cancel()
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
