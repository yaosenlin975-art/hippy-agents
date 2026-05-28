package com.lin.hippyagent.core.tools.android

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TakePhotoTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "take_photo",
        description = "拍照并保存到相册",
        parameters = mapOf(
            "file_name" to ToolParameter(
                name = "file_name",
                type = "string",
                description = "文件名（可选）",
                required = false
            )
        ),
        requiredPermissions = listOf("CAMERA"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val fileName = getOptionalArgument(arguments, "file_name") ?: "photo_${System.currentTimeMillis()}.jpg"
        val dir = context.getExternalFilesDir(null) ?: return ToolResult(callId, false, error = "External storage not available")
        val file = File(dir, fileName)

        val outputUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // API 29+ 使用 MediaStore 写入，避免 FileUriExposedException
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HippyAgent")
            }
            context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            android.net.Uri.fromFile(file)
        }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(callId, true, output = "Photo capture started: ${file.absolutePath}")
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to start camera: ${e.message}")
        }
    }
}

class RecordVideoTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "record_video",
        description = "录制视频",
        parameters = mapOf(
            "file_name" to ToolParameter(
                name = "file_name",
                type = "string",
                description = "文件名（可选）",
                required = false
            )
        ),
        requiredPermissions = listOf("CAMERA", "RECORD_AUDIO"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val fileName = getOptionalArgument(arguments, "file_name") ?: "video_${System.currentTimeMillis()}.mp4"
        val dir = context.getExternalFilesDir(null) ?: return ToolResult(callId, false, error = "External storage not available")
        val file = File(dir, fileName)

        val outputUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/HippyAgent")
            }
            context.contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        if (outputUri == null) {
            return ToolResult(callId, false, error = "Failed to create video output URI")
        }

        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(callId, true, output = "Video recording started: ${file.absolutePath}")
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to start video: ${e.message}")
        }
    }
}

class TakeScreenshotTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "take_screenshot",
        description = "截取屏幕",
        parameters = mapOf(
            "file_name" to ToolParameter(
                name = "file_name",
                type = "string",
                description = "文件名（可选）",
                required = false
            )
        ),
        requiredPermissions = listOf("READ_FRAME_BUFFER"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val fileName = getOptionalArgument(arguments, "file_name") ?: "screenshot_${System.currentTimeMillis()}.png"
        val dir = context.getExternalFilesDir(null) ?: return ToolResult(callId, false, error = "External storage not available")
        val file = File(dir, fileName)

        return ToolResult(callId, false, error = "Screenshot requires MediaProjection API. Use system screenshot shortcut instead.")
    }
}

class StartRecordingTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "start_recording",
        description = "开始录音",
        parameters = mapOf(
            "file_name" to ToolParameter(
                name = "file_name",
                type = "string",
                description = "文件名（可选）",
                required = false
            ),
            "duration" to ToolParameter(
                name = "duration",
                type = "integer",
                description = "录音时长（秒，0=无限）",
                required = false,
                defaultValue = "0"
            )
        ),
        requiredPermissions = listOf("RECORD_AUDIO"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val fileName = getOptionalArgument(arguments, "file_name") ?: "recording_${System.currentTimeMillis()}.m4a"
        val duration = (arguments["duration"] as? Number)?.toInt() ?: 0
        val dir = context.getExternalFilesDir(null) ?: return ToolResult(callId, false, error = "External storage not available")
        val file = File(dir, fileName)

        return try {
            val recorder = MediaRecorder()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setOutputFile(file.absolutePath)
                if (duration > 0) recorder.setMaxDuration(duration * 1000)
                recorder.prepare()
                recorder.start()
                RecordingManager.activeRecorders[callId] = recorder
                ToolResult(callId, true, output = "Recording started: ${file.absolutePath}${if (duration > 0) " (${duration}s)" else ""}")
            } else {
                @Suppress("DEPRECATION")
                with(recorder) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(file.absolutePath)
                    if (duration > 0) setMaxDuration(duration * 1000)
                    prepare()
                    start()
                }
                RecordingManager.activeRecorders[callId] = recorder
                ToolResult(callId, true, output = "Recording started: ${file.absolutePath}${if (duration > 0) " (${duration}s)" else ""}")
            }
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Recording failed: ${e.message}")
        }
    }
}

object RecordingManager {
    val activeRecorders = mutableMapOf<String, MediaRecorder>()

    fun stopRecording(callId: String): String? {
        val recorder = activeRecorders.remove(callId)
        return try {
            recorder?.stop()
            recorder?.release()
            "Recording stopped"
        } catch (e: Exception) {
            "Failed to stop recording: ${e.message}"
        }
    }
}

