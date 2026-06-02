package com.lin.hippyagent.core.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.Manifest
import timber.log.Timber

/**
 * Android 内置 SpeechRecognizer 转录器
 *
 * 使用 Android 系统自带的语音识别引擎（Google/厂商语音服务），
 * 无需下载任何模型，几乎所有设备即时可用。
 * 需要 RECORD_AUDIO 权限和 INTERNET 权限。
 *
 * 已知问题：在 MIUI/HyperOS 设备上 grantPermission 后 SpeechRecognizer
 * 仍可能返回 ERROR_INSUFFICIENT_PERMISSIONS（error 9），因为 HyperOS 的
 * SpeechRecognizer 实现使用了与 AudioRecord 不同的权限检查路径。
 * AudioRecord warmup 无法修复此问题。
 *
 * 调用方应检测此错误并回退到 AudioRecord 录音 + 端侧转录。
 */
class AndroidBuiltinTranscriber(
    private val context: Context
) {
    companion object {
        /**
         * 通过短暂使用麦克风来尝试同步 AppOpsManager 的 RECORD_AUDIO 条目。
         *
         * 注意：在 HyperOS 上此方法可能无法解决 SpeechRecognizer 的权限问题，
         * 因为两者使用不同的权限检查路径。保留此方法用于旧版 MIUI 兼容。
         */
        fun warmupAppOps(context: Context) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                if (bufferSize <= 0) return

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate, channelConfig, audioFormat, bufferSize
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    return
                }
                recorder.startRecording()
                val buf = ByteArray(minOf(bufferSize, 256))
                recorder.read(buf, 0, buf.size)
                recorder.stop()
                recorder.release()
                Timber.d("AndroidBuiltinTranscriber: AppOps warmup via AudioRecord succeeded")
            } catch (e: Exception) {
                Timber.w(e, "AndroidBuiltinTranscriber: AppOps warmup failed (non-critical)")
            }
        }

        /**
         * 通过反射调用 AppOpsManager.setMode() 强制将 OP_RECORD_AUDIO 设为 MODE_ALLOWED。
         * 用于解决 MIUI/HyperOS 设备上 checkSelfPermission(RECORD_AUDIO) 已 GRANTED
         * 但 SpeechRecognizer 仍报 ERROR_INSUFFICIENT_PERMISSIONS 的问题
         * （AppOps 未记录权限授予导致厂商 SpeechRecognizer 服务端校验失败）。
         *
         * setMode() 是 @hide 隐藏 API，且 OP_RECORD_AUDIO / MODE_ALLOWED 常量值
         * 可能随 Android SDK 升级变更，因此全部通过反射从 AppOpsManager 类查询。
         * 反射失败时仅记录警告，不影响主流程（不抛异常给调用方）。
         */
        fun notifyAppOps(context: Context) {
            try {
                val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) ?: return
                val appOpsClass = appOpsManager.javaClass
                val opRecordAudio = appOpsClass.getDeclaredField("OP_RECORD_AUDIO").getInt(null)
                val modeAllowed = appOpsClass.getDeclaredField("MODE_ALLOWED").getInt(null)
                val setModeMethod = appOpsClass.getMethod(
                    "setMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                setModeMethod.invoke(
                    appOpsManager,
                    opRecordAudio,
                    android.os.Process.myUid(),
                    context.packageName,
                    modeAllowed
                )
                Timber.i("AndroidBuiltinTranscriber: AppOps setMode(OP_RECORD_AUDIO=$opRecordAudio, MODE_ALLOWED=$modeAllowed) succeeded for ${context.packageName}")
            } catch (e: Exception) {
                Timber.w(e, "AndroidBuiltinTranscriber: AppOps setMode reflection failed (non-critical)")
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

        if (android.content.pm.PackageManager.PERMISSION_GRANTED != context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)) {
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
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                }

                override fun onEndOfSpeech() {
                    Timber.d("AndroidBuiltinTranscriber: end of speech")
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            if (isMiuiOrHyperOs()) {
                                "语音服务权限受限（小米设备）：请进入系统设置 → 应用管理 → 本应用 → 权限管理，确认麦克风权限已开启；如仍无法使用，请先关闭再重新开启麦克风权限"
                            } else {
                                "语音服务权限受限，请在系统设置 → 应用管理 → 麦克风权限 中确认是否已授予权限"
                            }
                        }
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
                        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
                        else -> "未知错误($error)"
                    }
                    Timber.w("AndroidBuiltinTranscriber: onError($error) - $errorMsg")
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        callback.onFinalResult(SttResult(text = "", isFinal = true))
                    } else {
                        callback.onError(RuntimeException(errorMsg))
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

    /**
     * 检测是否为小米 MIUI 或 HyperOS 系统（结果已缓存）
     */
    private fun isMiuiOrHyperOs(): Boolean = _isMiuiOrHyperOs

    private val _isMiuiOrHyperOs: Boolean by lazy {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            val miuiVersion = get.invoke(null, "ro.miui.ui.version.name", "") as String
            miuiVersion.isNotEmpty()
        } catch (_: Exception) {
            try {
                val clazz = Class.forName("android.os.SystemProperties")
                val get = clazz.getMethod("get", String::class.java, String::class.java)
                val prop = get.invoke(null, "ro.miui.ui.version.code", "") as String
                prop.isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }
    }
}
