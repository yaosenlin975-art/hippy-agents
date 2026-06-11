package com.lin.hippyagent.core.log

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val value: String) {
    ALL("all"),
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR")
}

enum class TimeRange(val label: String, val hours: Long) {
    LAST_HOUR("最近1小时", 1),
    LAST_24H("最近24小时", 24),
    LAST_7D("最近7天", 168),
    ALL("全部", 0)
}

class LogExporter(private val context: Context) {

    private val logDir by lazy { File(context.filesDir, "logs") }

    fun getLogFiles(): List<File> {
        if (!logDir.exists()) return emptyList()
        return logDir.listFiles()?.filter { it.name.endsWith(".log") }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun readLogs(
        level: LogLevel = LogLevel.ALL,
        timeRange: TimeRange = TimeRange.ALL,
        tail: Int = 200
    ): String {
        val files = getLogFiles()
        if (files.isEmpty()) return "无日志文件"

        val cutoffTime = if (timeRange == TimeRange.ALL) 0L
            else System.currentTimeMillis() - timeRange.hours * 3600_000L

        val sb = StringBuilder()
        for (file in files) {
            if (file.lastModified() < cutoffTime) continue
            sb.appendLine("--- ${file.name} ---")
            try {
                val lines = file.readLines()
                val filtered = if (level == LogLevel.ALL) lines
                    else lines.filter { line ->
                        level.value in line || " ${level.value} " in line || line.startsWith(level.value)
                    }
                val tailed = if (filtered.size > tail) filtered.takeLast(tail) else filtered
                sb.appendLine(tailed.joinToString("\n"))
            } catch (e: Exception) {
                sb.appendLine("[无法读取: ${e.message}]")
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    suspend fun exportToFile(
        level: LogLevel = LogLevel.ALL,
        timeRange: TimeRange = TimeRange.ALL
    ): Result<File> = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { cont ->
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir = File(context.getExternalFilesDir(null), "logs")
            dir.mkdirs()
            val f = File(dir, "hippy_logs_$ts.txt")

            val sb = StringBuilder()
            sb.appendLine("=== Hippy Log Export ===")
            sb.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            sb.appendLine("Filter: level=${level.value}, timeRange=${timeRange.label}")
            sb.appendLine()

            sb.append(readLogs(level, timeRange, tail = Int.MAX_VALUE))

            sb.appendLine("--- System ---")
            sb.appendLine("Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
            sb.appendLine("SDK: ${android.os.Build.VERSION.SDK_INT}")
            sb.appendLine()

            appendMicDiagnostics(sb)

            f.writeText(sb.toString())
            Timber.i("Log exported to ${f.absolutePath}")
            Result.success(f)
        } catch (e: Exception) {
            Timber.e(e, "Log export failed")
            Result.failure(e)
        }
    }

    fun shareLogFile(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun copyToClipboard(logContent: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Hippy Logs", logContent))
    }

    /**
     * 收集麦克风相关诊断信息（权限、AppOps、AudioRecord buffer、SPEECH 能力），
     * 用于日志导出，便于在小米/HyperOS 等定制设备上定位"已授权但无法录音"问题。
     * 异常分支必须 runCatching 包裹，避免诊断本身炸掉整个导出流程。
     */
    private fun appendMicDiagnostics(sb: StringBuilder) {
        sb.appendLine("--- Microphone ---")
        val recordGranted = runCatching {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        sb.appendLine("RECORD_AUDIO: ${if (recordGranted) "granted" else "DENIED"}")

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (appOps != null) {
            val mode = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_RECORD_AUDIO,
                        Process.myUid(),
                        context.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_RECORD_AUDIO,
                        Process.myUid(),
                        context.packageName
                    )
                }
            }.getOrDefault(-1)
            val modeText = when (mode) {
                AppOpsManager.MODE_ALLOWED -> "MODE_ALLOWED"
                AppOpsManager.MODE_IGNORED -> "MODE_IGNORED"
                AppOpsManager.MODE_ERRORED -> "MODE_ERRORED"
                AppOpsManager.MODE_DEFAULT -> "MODE_DEFAULT"
                AppOpsManager.MODE_FOREGROUND -> "MODE_FOREGROUND"
                -1 -> "check_failed"
                else -> "mode=$mode"
            }
            sb.appendLine("AppOps OP_RECORD_AUDIO: $modeText")
        } else {
            sb.appendLine("AppOps: unavailable")
        }

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minBufText = when (minBuf) {
            AudioRecord.ERROR -> "ERROR"
            AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
            else -> "ok=$minBuf"
        }
        sb.appendLine("AudioRecord.getMinBufferSize(16k/MONO/PCM16): $minBufText")

        val sources = listOf(
            "MIC" to MediaRecorder.AudioSource.MIC,
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "DEFAULT" to MediaRecorder.AudioSource.DEFAULT,
        )
        val probeResults = sources.joinToString(", ") { (name, src) ->
            val probe = runCatching {
                AudioRecord(
                    src,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf.coerceAtLeast(4096)
                )
            }.getOrNull()
            val state = if (probe != null) {
                val s = probe.state
                runCatching { probe.release() }
                s
            } else -1
            val stateText = when (state) {
                AudioRecord.STATE_INITIALIZED -> "INIT"
                AudioRecord.STATE_UNINITIALIZED -> "UNINIT"
                else -> "ERR"
            }
            "$name=$stateText"
        }
        sb.appendLine("AudioSource probe: $probeResults")

        val srAvail = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }
            .getOrDefault(false)
        sb.appendLine("SpeechRecognizer.isRecognitionAvailable: $srAvail")
        sb.appendLine()
    }
}

