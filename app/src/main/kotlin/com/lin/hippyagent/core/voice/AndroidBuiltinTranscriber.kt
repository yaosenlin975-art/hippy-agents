package com.lin.hippyagent.core.voice

import android.content.Context
import android.os.Bundle
import android.os.Process
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
 *
 * 解决：在 RECORD_AUDIO 权限授权回调中调用 [notifyAppOps]，
 * 通过反射将 AppOpsManager 的 OP_RECORD_AUDIO 设为 MODE_ALLOWED，
 * 让厂商 SpeechRecognizer 服务端的权限校验通过。
 * 若仍报 ERROR_INSUFFICIENT_PERMISSIONS，提示用户在系统设置中手动开关一次麦克风权限。
 *
 * 调用方应检测此错误并回退到 AudioRecord 录音 + 端侧转录。
 */
class AndroidBuiltinTranscriber(
    private val context: Context
) {
    companion object {
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
            val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) ?: run {
                Timber.w("AndroidBuiltinTranscriber: APP_OPS_SERVICE unavailable")
                return
            }
            val appOpsClass = appOpsManager.javaClass

            // Try the high-affinity path first: setMode(OP_RECORD_AUDIO, ..., MODE_ALLOWED).
            // On some MIUI/HyperOS builds this throws SecurityException because setMode is restricted
            // to system/owner UIDs only.
            val setModeResolved = resolveSetMode(appOpsClass)
            if (setModeResolved != null) {
                val (op, mode, method) = setModeResolved
                try {
                    method.invoke(appOpsManager, op, Process.myUid(), context.packageName, mode)
                    Timber.i("AndroidBuiltinTranscriber: AppOps setMode(OP_RECORD_AUDIO=$op, MODE_ALLOWED=$mode) succeeded for ${context.packageName}")
                    return
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    val cause = e.targetException ?: e
                    Timber.w(cause, "AndroidBuiltinTranscriber: AppOps setMode threw ${cause.javaClass.simpleName} — falling back to noteProxyOp")
                } catch (e: Exception) {
                    Timber.w(e, "AndroidBuiltinTranscriber: AppOps setMode failed — falling back to noteProxyOp")
                }
            } else {
                Timber.w("AndroidBuiltinTranscriber: AppOps setMode not available on this SDK — trying noteProxyOp")
            }

            // Fallback: noteProxyOp(op, proxyUid, proxyPackageName, proxyAttributionTag, ...)
            // On some HyperOS builds this is the only path that successfully refreshes the
            // cached permission state seen by the SpeechRecognizer service.
            val noteProxyResolved = resolveNoteProxyOp(appOpsClass)
            if (noteProxyResolved != null) {
                val (op, method) = noteProxyResolved
                try {
                    val allowed = appOpsClass.getDeclaredField("MODE_ALLOWED").getInt(null)
                    // The legacy 4-arg overload (op, proxyUid, proxyPackageName, mode) is
                    // present on API 24+. Newer devices add more arguments; we attempt the
                    // minimal signature first.
                    method.invoke(appOpsManager, op, Process.myUid(), context.packageName, allowed)
                    Timber.i("AndroidBuiltinTranscriber: AppOps noteProxyOp succeeded for ${context.packageName}")
                } catch (e: Exception) {
                    Timber.w(e, "AndroidBuiltinTranscriber: AppOps noteProxyOp failed (non-critical)")
                }
            }
        }

        @Volatile
        private var resolvedOpsReflection: Triple<Int, Int, java.lang.reflect.Method>? = null

        private fun resolveSetMode(appOpsClass: Class<*>): Triple<Int, Int, java.lang.reflect.Method>? {
            resolvedOpsReflection?.let { return it }
            return try {
                val op = appOpsClass.getDeclaredField("OP_RECORD_AUDIO").getInt(null)
                val mode = appOpsClass.getDeclaredField("MODE_ALLOWED").getInt(null)
                val method = appOpsClass.getMethod(
                    "setMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                val triple = Triple(op, mode, method)
                resolvedOpsReflection = triple
                triple
            } catch (e: Exception) {
                Timber.w(e, "AndroidBuiltinTranscriber: AppOps setMode reflection resolve failed (non-critical)")
                null
            }
        }

        private fun resolveNoteProxyOp(appOpsClass: Class<*>): Pair<Int, java.lang.reflect.Method>? = try {
            val op = appOpsClass.getDeclaredField("OP_RECORD_AUDIO").getInt(null)
            val method = appOpsClass.getMethod(
                "noteProxyOp",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            op to method
        } catch (e: Exception) {
            Timber.w(e, "AndroidBuiltinTranscriber: AppOps noteProxyOp reflection resolve failed (non-critical)")
            null
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
        readSystemProperty("ro.miui.ui.version.name")?.isNotEmpty() == true ||
            readSystemProperty("ro.miui.ui.version.code")?.isNotEmpty() == true
    }

    private fun readSystemProperty(name: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java, String::class.java)
        get.invoke(null, name, "") as? String
    } catch (_: Exception) {
        null
    }
}
