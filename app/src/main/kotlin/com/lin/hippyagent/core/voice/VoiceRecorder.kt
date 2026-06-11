package com.lin.hippyagent.core.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lin.hippyagent.core.pool.ByteArrayOutputStreamPool
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class VoiceRecorder(
    private val outputDir: File,
    private val scope: CoroutineScope
) {
    private var audioRecord: AudioRecord? = null
    private val baosPool = ByteArrayOutputStreamPool(initialBufferSize = 64 * 1024, maxSize = 2)
    private var recordingBuffer: ByteArrayOutputStream? = null
    private var recordingJob: Job? = null
    private var timeoutJob: Job? = null
    private var startTimeMs: Long = 0
    private var outputFile: File? = null
    @Volatile
    private var isRecording = false

    val currentDurationMs: Long
        get() = if (isRecording) System.currentTimeMillis() - startTimeMs else 0L

    fun startRecording(): File? {
        if (isRecording) return null

        val (audioSource, bufferSize) = resolveAudioSource() ?: run {
            Timber.e("VoiceRecorder: no AudioSource/format combination supported on this device (sampleRate=$SAMPLE_RATE, channel=${CHANNELConfigToString(CHANNEL_CONFIG)}, format=PCM16BIT)")
            return null
        }

        audioRecord = AudioRecord(audioSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            val state = audioRecord?.state
            Timber.e("VoiceRecorder: AudioRecord.state != STATE_INITIALIZED (state=$state, audioSource=$audioSource, sampleRate=$SAMPLE_RATE, bufferSize=$bufferSize) — check RECORD_AUDIO permission and AppOps OP_RECORD_AUDIO")
            runCatching { audioRecord?.release() }
            audioRecord = null
            return null
        }

        Timber.i("VoiceRecorder: startRecording audioSource=$audioSource sampleRate=$SAMPLE_RATE bufferSize=$bufferSize")

        outputFile = File(outputDir, "voice_${System.currentTimeMillis()}.wav")
        recordingBuffer = baosPool.acquire()
        startTimeMs = System.currentTimeMillis()
        isRecording = true

        audioRecord?.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            val data = ByteArray(bufferSize)
            val buffer = recordingBuffer ?: return@launch
            while (coroutineContext.isActive && isRecording) {
                val read = audioRecord?.read(data, 0, data.size) ?: break
                if (read > 0) {
                    if (buffer.size() + read * 2 <= MAX_RECORDING_BYTES) {
                        buffer.write(data, 0, read)
                    } else {
                        break
                    }
                }
            }
        }

        timeoutJob = scope.launch(Dispatchers.IO) {
            delay(MAX_DURATION_MS)
            stopRecording()
        }

        return outputFile
    }

    /**
     * 依次尝试多个 AudioSource，找到第一个 getMinBufferSize 返回合法值且 AudioRecord 可初始化的组合。
     * 小米 HyperOS 上 MediaRecorder.AudioSource.MIC 经常返回 ERROR_BAD_VALUE（不允许非系统 App 直采），
     * 此时需降级到 VOICE_RECOGNITION / VOICE_COMMUNICATION / DEFAULT。
     */
    private fun resolveAudioSource(): Pair<Int, Int>? {
        val candidates = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT,
        )
        for (source in candidates) {
            val size = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (size <= 0) {
                Timber.w("VoiceRecorder: getMinBufferSize unsupported for audioSource=$source (size=$size)")
                continue
            }
            val probe = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, size)
            val ok = probe.state == AudioRecord.STATE_INITIALIZED
            runCatching { probe.release() }
            if (ok) {
                if (source != MediaRecorder.AudioSource.MIC) {
                    Timber.w("VoiceRecorder: MIC source failed on this device, fell back to audioSource=$source")
                }
                return source to size
            }
            Timber.w("VoiceRecorder: AudioRecord probe failed for audioSource=$source (state=${probe.state})")
        }
        return null
    }

    private fun CHANNELConfigToString(@Suppress("UNUSED_PARAMETER") config: Int): String =
        if (config == AudioFormat.CHANNEL_IN_MONO) "MONO" else "STEREO"

    suspend fun stopRecording(): VoiceRecordingResult? {
        if (!isRecording) return null

        isRecording = false
        timeoutJob?.cancel()
        timeoutJob = null
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.apply {
            runCatching {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            }
        }
        audioRecord = null

        val durationMs = System.currentTimeMillis() - startTimeMs
        val buffer = recordingBuffer
        recordingBuffer = null
        val pcmBytes = if (buffer != null) {
            val bytes = buffer.toByteArray()
            baosPool.release(buffer)
            bytes
        } else {
            ByteArray(0)
        }

        if (pcmBytes.isEmpty() || durationMs < 500) {
            outputFile?.delete()
            return null
        }

        val file = outputFile ?: return null

        runCatching {
            withContext(Dispatchers.IO) {
                writeWavFile(file, pcmBytes)
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to write WAV file")
        }

        return VoiceRecordingResult(
            file = file,
            pcmBytes = pcmBytes,
            durationMs = durationMs
        )
    }

    private fun writeWavFile(file: File, pcmData: ByteArray) {
        val dataLength = pcmData.size
        val totalLength = 36 + dataLength

        FileOutputStream(file).use { fos ->
            fos.write("RIFF".toByteArray())
            fos.write(intToBytes(totalLength, 4))
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            fos.write(intToBytes(16, 4))
            fos.write(intToBytes(1, 2))
            fos.write(intToBytes(1, 2))
            fos.write(intToBytes(SAMPLE_RATE, 4))
            fos.write(intToBytes(SAMPLE_RATE * 2, 4))
            fos.write(intToBytes(2, 2))
            fos.write(intToBytes(16, 2))
            fos.write("data".toByteArray())
            fos.write(intToBytes(dataLength, 4))
            fos.write(pcmData)
        }
    }

    private fun intToBytes(value: Int, size: Int): ByteArray {
        val bytes = ByteArray(size)
        for (i in 0 until size) {
            bytes[i] = (value shr (8 * i) and 0xFF).toByte()
        }
        return bytes
    }

    fun release() {
        if (!isRecording) return
        isRecording = false
        timeoutJob?.cancel()
        timeoutJob = null
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.apply {
            runCatching {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            }
        }
        audioRecord = null
        recordingBuffer?.let { baosPool.release(it) }
        recordingBuffer = null
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_RECORDING_BYTES = 3_840_000
        private const val MAX_DURATION_MS = 120_000L
    }
}

data class VoiceRecordingResult(
    val file: File,
    val pcmBytes: ByteArray,
    val durationMs: Long
)
