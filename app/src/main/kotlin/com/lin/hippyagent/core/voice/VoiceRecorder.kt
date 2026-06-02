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

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { audioRecord?.release() }
            audioRecord = null
            return null
        }

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
