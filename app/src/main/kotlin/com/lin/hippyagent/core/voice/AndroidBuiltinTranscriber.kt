package com.lin.hippyagent.core.voice

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import timber.log.Timber

/**
 * Android 内置 SpeechRecognizer 转录器
 *
 * 使用 Android 系统自带的语音识别引擎（Google/厂商语音服务），
 * 无需下载任何模型，几乎所有设备即时可用。
 * 需要 RECORD_AUDIO 权限和 INTERNET 权限。
 *
 * 已知问题：在 MIUI 设备上 grantPermission 后 SpeechRecognizer 仍可能因 AppOps
 * 未记录而返回 ERROR_INSUFFICIENT_PERMISSIONS。notifyAppOps() 尝试在运行
 * 时通知 AppOpsManager 以缓解此问题。
 */
class AndroidBuiltinTranscriber(
    private val context: Context
) {
    companion object {
        /**
         * 通知 AppOpsManager 已获得 RECORD_AUDIO 权限。
         * 在 MiUI 上 grantPermission 后调用，防止 SpeechRecognizer
         * 因 AppOps 未记录而报 ERROR_INSUFFICIENT_PERMISSIONS。
         */
        fun notifyAppOps(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            try {
                val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return
                // OP_RECORD_AUDIO = 27, OP_PLAY_AUDIO = 28
                val opValue = 27 // RECORD_AUDIO 的 op 值
                val packageName = context.packageName
                val uid = android.os.Process.myUid()
                val method = AppOpsManager::class.java.getMethod(
                    "setMode", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                method.invoke(appOpsManager, opValue, uid, packageName, AppOpsManager.MODE_ALLOWED)
                Timber.d("AndroidBuiltinTranscriber: AppOps RECORD_AUDIO noted via setMode")
            } catch (_: Exception) {
                // 兼容性：反射失败时静默处理，SpeechRecognizer 自身错误提示会引导用户
                Timber.w("AndroidBuiltinTranscriber: failed to set AppOps, this is expected on non-MIUI devices")
            }
        }
    }
    private var speechRecognizer: SpeechRecognizer? = null
    private var callback: SttCallback? = null

    /** SpeechRecognizer 是否可用 */
    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * 开始聆听
     */
    fun startListening(callback: SttCallback) {
        this.callback = callback

        if (android.content.pm.PackageManager.PERMISSION_GRANTED != context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)) {
            callback.onError(IllegalStateException("缺少麦克风权限，请在系统设置中授予权限"))
            return
        }

        if (!isAvailable) {
            callback.onError(IllegalStateException("系统语音识别不可用"))
            return
        }

        stopListening()

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Timber.d("AndroidBuiltinTranscriber: ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    Timber.d("AndroidBuiltinTranscriber: beginning of speech")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // 可选：用于显示音量动画
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // 不需要处理
                }

                override fun onEndOfSpeech() {
                    Timber.d("AndroidBuiltinTranscriber: end of speech")
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音服务权限受限，请在系统设置 → 应用管理 → 麦克风权限 中确认是否已授予权限，部分系统还需额外开启「允许通话录音」开关"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
                        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
                        else -> "未知错误($error)"
                    }
                    Timber.w("AndroidBuiltinTranscriber: $errorMsg")
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        callback.onFinalResult(SttResult(text = "", isFinal = true))
                    } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        // 尝试重新通知 AppOps（部分 MIUI 版本需运行中动态设置）
                        notifyAppOps(context)
                        callback.onError(RuntimeException(errorMsg))
                    } else {
                        callback.onError(RuntimeException("语音识别失败: $errorMsg"))
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val text = matches?.firstOrNull() ?: ""
                    val confidence = confidenceScores?.firstOrNull() ?: 1f
                    Timber.d("AndroidBuiltinTranscriber: final result='$text' confidence=$confidence")
                    callback.onFinalResult(SttResult(text = text, isFinal = true, confidence = confidence))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotEmpty()) {
                        callback.onPartialResult(SttResult(text = text, isFinal = false))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    // 不需要处理
                }
            })

            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Timber.e(e, "AndroidBuiltinTranscriber: failed to start")
            callback.onError(e)
        }
    }

    /**
     * 停止聆听
     */
    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Timber.w(e, "AndroidBuiltinTranscriber: error stopping")
        } finally {
            speechRecognizer?.destroy()
            speechRecognizer = null
            callback = null
        }
    }

    fun release() {
        stopListening()
    }
}
