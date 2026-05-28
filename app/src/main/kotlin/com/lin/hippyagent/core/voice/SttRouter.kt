package com.lin.hippyagent.core.voice

import android.content.Context
import android.speech.SpeechRecognizer
import com.lin.hippyagent.core.ondevice.OnDeviceCapability
import com.lin.hippyagent.core.ondevice.OnDeviceModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class SttEngine { MOONSHINE, LITERTLM, ANDROID_BUILTIN, NONE }

class SttRouter(
    private val context: Context,
    private val voiceManager: VoiceExtensionManager,
    private val onDeviceModelManager: OnDeviceModelManager,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun resolve(): SttEngine {
        // 1. Moonshine 本地模型（需下载）
        if (voiceManager.isDeviceSupported && voiceManager.isSttAvailable.value) {
            return SttEngine.MOONSHINE
        }
        // 2. LiteRT-LM 端侧模型（需 AUDIO 能力）
        val engineModelId = onDeviceModelManager.currentEngineModelId.value
        if (engineModelId != null) {
            val config = onDeviceModelManager.getModelConfig(engineModelId)
            if (config != null && OnDeviceCapability.AUDIO in config.capabilities) {
                return SttEngine.LITERTLM
            }
        }
        // 3. Android 内置 SpeechRecognizer（几乎所有设备可用，无需下载）
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            return SttEngine.ANDROID_BUILTIN
        }
        return SttEngine.NONE
    }

    val isAnyAvailable: Boolean
        get() = resolve() != SttEngine.NONE

    val isAvailableFlow: StateFlow<Boolean> = combine(
        voiceManager.isSttAvailable,
        onDeviceModelManager.currentEngineModelId
    ) { _, _ ->
        resolve() != SttEngine.NONE
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val currentEngineLabel: String
        get() = when (resolve()) {
            SttEngine.MOONSHINE -> "Moonshine"
            SttEngine.LITERTLM -> "端侧"
            SttEngine.ANDROID_BUILTIN -> "系统语音"
            SttEngine.NONE -> "不可用"
        }
}
