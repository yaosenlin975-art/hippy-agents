package com.lin.hippyagent.core.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.Locale

/**
 * TTS 文字转语音服务
 * 使用 Android 系统 TextToSpeech 引擎
 * （Moonshine SDK 不提供 TTS，设计文档中的 TTS 部分使用系统引擎替代）
 */
class TTSService(
    private val context: Context
) {
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private var tts: TextToSpeech? = null
    private var currentCallback: TtsCallback? = null
    private var speakJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isInitialized = false

    /** 初始化 TTS 引擎 */
    fun initialize() {
        if (isInitialized) return

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    val result = tts?.setLanguage(Locale.CHINESE)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.getDefault())
                        Timber.w("TTSService: Chinese not supported, using default locale")
                    }
                    _isAvailable.value = true
                    isInitialized = true
                    Timber.i("TTSService: Initialized successfully")
                } catch (e: Exception) {
                    Timber.e(e, "TTSService: Failed to set language")
                    _isAvailable.value = true  // 即使语言设置失败，引擎本身可用
                    isInitialized = true
                }
            } else {
                Timber.e("TTSService: Initialization failed with status $status")
                _isAvailable.value = false
            }
        }
    }

    /**
     * 语音合成并播放
     */
    fun speak(request: TtsRequest, callback: TtsCallback) {
        if (!_isAvailable.value || tts == null) {
            callback.onError(IllegalStateException("TTS 引擎不可用"))
            return
        }

        // 停止当前播放
        stop()

        currentCallback = callback
        _isSpeaking.value = true

        try {
            val utteranceId = "tts_${System.currentTimeMillis()}"

            // 设置播放完成监听
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // TTS 开始播放
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    currentCallback?.onComplete()
                    currentCallback = null
                }

                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    currentCallback?.onError(Exception("TTS 播放出错"))
                    currentCallback = null
                }
            })

            // 设置语言
            val locale = when (request.language) {
                "zh" -> Locale.CHINESE
                "en" -> Locale.ENGLISH
                "ja" -> Locale.JAPANESE
                "ko" -> Locale.KOREAN
                else -> Locale.getDefault()
            }
            tts?.setLanguage(locale)

            val result = tts?.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                _isSpeaking.value = false
                callback.onError(Exception("TTS speak() 调用失败"))
                currentCallback = null
            }
        } catch (e: Exception) {
            Timber.e(e, "TTSService: Error during speak")
            _isSpeaking.value = false
            callback.onError(e)
            currentCallback = null
        }
    }

    /**
     * 停止当前播放
     */
    fun stop() {
        currentCallback = null
        _isSpeaking.value = false
        try {
            tts?.setOnUtteranceProgressListener(null)
        } catch (_: Exception) {}
        try {
            tts?.stop()
        } catch (e: Exception) {
            Timber.w(e, "TTSService: Error stopping TTS")
        }
        try {
            tts?.speak("", TextToSpeech.QUEUE_FLUSH, null, "stop_flush")
        } catch (_: Exception) {}
    }

    /**
     * 释放资源
     */
    fun release() {
        stop()
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            // ignore
        }
        tts = null
        isInitialized = false
        _isAvailable.value = false
        serviceScope.cancel()
    }
}

data class TtsRequest(
    val text: String,
    val language: String = "zh",
    val speakerId: String? = null
)

interface TtsCallback {
    fun onComplete()
    fun onError(error: Throwable)
}
