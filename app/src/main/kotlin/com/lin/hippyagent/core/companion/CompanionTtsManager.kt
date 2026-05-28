package com.lin.hippyagent.core.companion

import android.content.Context
import com.lin.hippyagent.core.voice.TTSService
import com.lin.hippyagent.core.voice.TtsRequest
import com.lin.hippyagent.core.voice.TtsCallback
import timber.log.Timber

class CompanionTtsManager(private val context: Context) {
    private var ttsService: TTSService? = null

    fun initialize() {
        ttsService = TTSService(context)
        Timber.i("CompanionTtsManager: initialized")
    }

    fun speak(text: String) {
        ttsService?.speak(
            TtsRequest(text = text),
            object : TtsCallback {
                override fun onComplete() {}
                override fun onError(error: Throwable) {
                    Timber.w(error, "CompanionTtsManager: TTS error")
                }
            }
        )
    }

    fun stop() {
        ttsService?.stop()
    }

    fun shutdown() {
        ttsService?.release()
        ttsService = null
    }
}
