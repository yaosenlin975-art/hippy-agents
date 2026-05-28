package com.lin.hippyagent.core.voice

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File

/**
 * 语音扩展统一管理入口
 * 管理 STT 模型下载/删除/状态 + TTS 可用性检测
 */
class VoiceExtensionManager(
    private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("voice_extensions", Context.MODE_PRIVATE)
    }

    private val _state = MutableStateFlow(VoiceExtensionState())
    val state: StateFlow<VoiceExtensionState> = _state.asStateFlow()

    private val _isSttAvailable = MutableStateFlow(false)
    val isSttAvailable: StateFlow<Boolean> = _isSttAvailable.asStateFlow()

    private val _isTtsAvailable = MutableStateFlow(false)
    val isTtsAvailable: StateFlow<Boolean> = _isTtsAvailable.asStateFlow()

    /** 设备 API level 是否满足 Moonshine SDK 最低要求 (35+) */
    val isDeviceSupported: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= 35

    /** 语音扩展根目录 */
    val voiceDir: File
        get() = File(context.filesDir, "voice_extensions")

    /** STT 模型目录 */
    val sttModelDir: File
        get() = File(voiceDir, "stt")

    /** 初始化：检测已安装模型和可用性 */
    fun initialize() {
        if (!isDeviceSupported) {
            Timber.w("VoiceExtension: Device API ${android.os.Build.VERSION.SDK_INT} < 35, voice features disabled")
            _state.value = _state.value.copy(
                deviceUnsupported = true
            )
            return
        }

        // 检查已安装的 STT 模型
        val sttInfo = detectSttModel()
        if (sttInfo != null) {
            _state.value = _state.value.copy(sttModel = sttInfo)
            _isSttAvailable.value = true
        }

        // TTS 通过 Android 系统 API 检测（始终可用）
        _isTtsAvailable.value = true
        _state.value = _state.value.copy(ttsLanguage = prefs.getString("tts_language", "zh") ?: "zh")

        Timber.i("VoiceExtension initialized: STT=${_isSttAvailable.value}, TTS=${_isTtsAvailable.value}")
    }

    /** 检测已安装的 STT 模型 */
    private fun detectSttModel(): SttModelInfo? {
        val savedSize = prefs.getString("stt_model_size", null)
        val savedLang = prefs.getString("stt_language", null)

        if (savedSize == null || savedLang == null) return null

        val modelDir = File(sttModelDir, "moonshine_${savedSize.lowercase()}_$savedLang")
        if (!modelDir.exists()) return null

        // 检查关键模型文件是否存在
        val encoderFile = File(modelDir, "encoder_model.ort")
        val decoderFile = File(modelDir, "decoder_model_merged.ort")
        val tokenizerFile = File(modelDir, "tokenizer.bin")

        if (!encoderFile.exists() || !decoderFile.exists() || !tokenizerFile.exists()) {
            Timber.w("VoiceExtension: STT model dir exists but files incomplete: $modelDir")
            return null
        }

        val totalSize = encoderFile.length() + decoderFile.length() + tokenizerFile.length()
        return SttModelInfo(
            size = SttModelSize.valueOf(savedSize),
            language = savedLang,
            modelPath = modelDir.absolutePath,
            fileSizeBytes = totalSize
        )
    }

    /** 标记 STT 模型为已下载（实际下载由外部处理） */
    fun markSttModelDownloaded(size: SttModelSize, language: String, modelPath: String) {
        val modelDir = File(modelPath)
        val totalSize = modelDir.listFiles()?.sumOf { it.length() } ?: 0L

        val info = SttModelInfo(
            size = size,
            language = language,
            modelPath = modelPath,
            fileSizeBytes = totalSize
        )

        prefs.edit()
            .putString("stt_model_size", size.name)
            .putString("stt_language", language)
            .apply()

        _state.value = _state.value.copy(
            sttModel = info,
            isSttDownloading = false,
            downloadProgress = 0f
        )
        _isSttAvailable.value = true
        Timber.i("VoiceExtension: STT model marked as downloaded: $size/$language")
    }

    /** 删除已下载的 STT 模型 */
    fun deleteSttModel() {
        val info = _state.value.sttModel ?: return

        val modelDir = File(info.modelPath)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }

        prefs.edit()
            .remove("stt_model_size")
            .remove("stt_language")
            .apply()

        _state.value = _state.value.copy(sttModel = null)
        _isSttAvailable.value = false
        Timber.i("VoiceExtension: STT model deleted")
    }

    /** 更新下载进度 */
    fun updateDownloadProgress(progress: Float) {
        _state.value = _state.value.copy(downloadProgress = progress)
    }

    /** 设置下载状态 */
    fun setDownloading(downloading: Boolean) {
        _state.value = _state.value.copy(
            isSttDownloading = downloading,
            downloadProgress = if (!downloading) 0f else _state.value.downloadProgress
        )
    }

    companion object {
        /** 可用的 STT 模型列表 */
        val AVAILABLE_STT_MODELS = listOf(
            SttModelSize.TINY,
            SttModelSize.SMALL,
            SttModelSize.MEDIUM
        )
    }
}

data class VoiceExtensionState(
    val sttModel: SttModelInfo? = null,
    val ttsLanguage: String = "zh",
    val isSttDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val deviceUnsupported: Boolean = false
)

data class SttModelInfo(
    val size: SttModelSize,
    val language: String,
    val modelPath: String,
    val fileSizeBytes: Long
)

enum class SttModelSize(
    val displayName: String,
    val approxSize: String,
    val params: String
) {
    TINY("Tiny Streaming", "~26MB", "34M"),
    SMALL("Small Streaming", "~100MB", "123M"),
    MEDIUM("Medium Streaming", "~250MB", "245M")
}
