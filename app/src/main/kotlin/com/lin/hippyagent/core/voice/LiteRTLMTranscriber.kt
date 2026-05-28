package com.lin.hippyagent.core.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.lin.hippyagent.core.ondevice.OnDeviceCapability
import com.lin.hippyagent.core.ondevice.OnDeviceModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

class LiteRTLMTranscriber(
    private val onDeviceModelManager: OnDeviceModelManager,
    private val serviceScope: CoroutineScope,
) {
    private var audioRecord: AudioRecord? = null
    private var recordingBuffer = ByteArrayOutputStream()
    private var listeningJob: Job? = null
    private var timeoutJob: Job? = null
    private val currentCallback = AtomicReference<SttCallback?>(null)

    val isAvailable: Boolean
        get() {
            val modelId = onDeviceModelManager.currentEngineModelId.value ?: return false
            val config = onDeviceModelManager.getModelConfig(modelId)
            return config != null && OnDeviceCapability.AUDIO in config.capabilities
        }

    fun startListening(callback: SttCallback) {
        if (!isAvailable) {
            callback.onError(IllegalStateException("LiteRT-LM Audio 不可用：引擎未加载或模型不支持音频"))
            return
        }

        currentCallback.set(callback)

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            callback.onError(IllegalStateException("AudioRecord 初始化失败"))
            runCatching { audioRecord?.release() }
            audioRecord = null
            return
        }

        recordingBuffer.reset()
        audioRecord?.startRecording()

        listeningJob = serviceScope.launch {
            val data = ByteArray(bufferSize)
            while (coroutineContext.isActive) {
                val read = audioRecord?.read(data, 0, data.size) ?: break
                if (read > 0) {
                    if (recordingBuffer.size() + read <= MAX_RECORDING_BYTES) {
                        recordingBuffer.write(data, 0, read)
                    } else {
                        break
                    }
                }
            }
            doTranscribe()
        }

        timeoutJob = serviceScope.launch {
            delay(30_000)
            stopRecording()
        }

        serviceScope.launch {
            withContext(Dispatchers.Main) {
                callback.onPartialResult(SttResult(text = "正在聆听...", isFinal = false))
            }
        }
    }

    fun stopListening() {
        stopRecording()
    }

    private fun stopRecording() {
        timeoutJob?.cancel()
        timeoutJob = null
        listeningJob?.cancel()
        listeningJob = null
        audioRecord?.apply {
            runCatching {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            }
        }
        audioRecord = null
    }

    private suspend fun doTranscribe() = supervisorScope {
        val pcmBytes = recordingBuffer.toByteArray()
        recordingBuffer.reset()

        if (pcmBytes.isEmpty()) {
            withContext(Dispatchers.Main) {
                currentCallback.getAndSet(null)?.onError(IllegalStateException("未录制到音频数据"))
            }
            return@supervisorScope
        }

        val cb = currentCallback.get()
        if (cb == null) {
            return@supervisorScope
        }

        try {
            withContext(Dispatchers.Main) {
                cb.onPartialResult(SttResult(text = "正在识别...", isFinal = false))
            }
            val text = onDeviceModelManager.transcribeAudio(pcmBytes)
            withContext(Dispatchers.Main) {
                currentCallback.getAndSet(null)?.onFinalResult(SttResult(text = text, isFinal = true))
            }
        } catch (e: Exception) {
            Timber.e(e, "LiteRT-LM Audio transcription failed")
            withContext(Dispatchers.Main) {
                currentCallback.getAndSet(null)?.onError(e)
            }
        }
    }

    fun release() {
        timeoutJob?.cancel()
        listeningJob?.cancel()
        audioRecord?.apply {
            runCatching {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            }
        }
        audioRecord = null
        recordingBuffer.reset()
        currentCallback.set(null)
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_RECORDING_BYTES = 960_000
    }
}
