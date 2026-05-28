package com.lin.hippyagent.core.tools.android

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lin.hippyagent.core.model.TokenUsageManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import java.io.File

private val Context.timezoneDataStore by preferencesDataStore(name = "user_timezone")

class GetSystemInfoTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "get_system_info",
        description = "获取系统信息",
        parameters = mapOf(
            "category" to ToolParameter(
                name = "category",
                type = "string",
                description = "信息类别: basic/device/os/memory/storage/battery/all",
                required = false,
                defaultValue = "all"
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val category = getOptionalArgument(arguments, "category", "all")!!
        val callId = arguments["callId"] as? String ?: ""

        // 将不识别的类别默认为 "all"
        val effectiveCategory = if (category in listOf("device", "os", "memory", "storage", "battery", "basic", "all")) {
            category
        } else {
            "all"
        }

        val info = buildString {
            if (effectiveCategory in listOf("device", "basic", "all")) {
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Brand: ${Build.BRAND}")
                appendLine("SDK: ${Build.VERSION.SDK_INT}")
                appendLine("Android: ${Build.VERSION.RELEASE}")
            }
            if (effectiveCategory in listOf("memory", "basic", "all")) {
                val runtime = Runtime.getRuntime()
                val maxMem = runtime.maxMemory() / (1024 * 1024)
                val totalMem = runtime.totalMemory() / (1024 * 1024)
                val freeMem = runtime.freeMemory() / (1024 * 1024)
                appendLine("Max Memory: ${maxMem}MB")
                appendLine("Total Memory: ${totalMem}MB")
                appendLine("Free Memory: ${freeMem}MB")
            }
            if (effectiveCategory in listOf("storage", "all")) {
                val stat = android.os.StatFs(context.filesDir.absolutePath)
                val total = stat.totalBytes / (1024 * 1024 * 1024)
                val available = stat.availableBytes / (1024 * 1024 * 1024)
                appendLine("Total Storage: ${total}GB")
                appendLine("Available Storage: ${available}GB")
            }
            if (effectiveCategory in listOf("battery", "all")) {
                try {
                    val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    if (batteryIntent != null) {
                        val level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                        val scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                        val percentage = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                        val status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                        val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
                        val temperature = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
                        val health = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1)
                        val healthStr = when (health) {
                            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                            else -> "Unknown"
                        }
                        appendLine("Battery Level: ${percentage}%")
                        appendLine("Charging: ${if (isCharging) "Yes" else "No"}")
                        appendLine("Temperature: ${temperature}°C")
                        appendLine("Health: $healthStr")
                    } else {
                        appendLine("Battery: Unable to retrieve battery information")
                    }
                } catch (e: Exception) {
                    appendLine("Battery: Unable to retrieve battery information (${e.message ?: "unknown error"})")
                }
            }
        }

        return ToolResult(callId, true, info.trimEnd())
    }
}

class VibrateTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "vibrate",
        description = "让手机震动",
        parameters = mapOf(
            "duration" to ToolParameter(
                name = "duration",
                type = "integer",
                description = "震动时长（毫秒）",
                required = false,
                defaultValue = 200
            )
        ),
        requiredPermissions = listOf("VIBRATE"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val duration = (arguments["duration"] as? Number)?.toLong() ?: 200L
        val callId = arguments["callId"] as? String ?: ""

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))

        return ToolResult(callId, true, output = "Vibrated for ${duration}ms")
    }
}

class GetVolumeTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "get_volume",
        description = "获取音量信息",
        parameters = mapOf(
            "stream_type" to ToolParameter(
                name = "stream_type",
                type = "string",
                description = "音量类型: ring/notification/music/alarm/system",
                required = false,
                defaultValue = "music"
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val streamType = getOptionalArgument(arguments, "stream_type", "music")!!
        val callId = arguments["callId"] as? String ?: ""

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (streamType) {
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "music" -> AudioManager.STREAM_MUSIC
            "alarm" -> AudioManager.STREAM_ALARM
            "system" -> AudioManager.STREAM_SYSTEM
            else -> AudioManager.STREAM_MUSIC
        }

        val currentVolume = audioManager.getStreamVolume(stream)
        val maxVolume = audioManager.getStreamMaxVolume(stream)

        return ToolResult(callId, true, output = "Volume: $currentVolume/$maxVolume (type: $streamType)")
    }
}

class LaunchAppTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "launch_app",
        description = "启动应用。支持中文应用名（如「淘宝」「微信」）或包名。",
        parameters = mapOf(
            "package" to ToolParameter(
                name = "package",
                type = "string",
                description = "应用包名或中文应用名（如「淘宝」「微信」「com.taobao.taobao」）",
                required = true
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val input = getRequiredArgument(arguments, "package")
        val callId = arguments["callId"] as? String ?: ""

        AppPackageResolver.initialize(context)
        val packageName = AppPackageResolver.resolvePackageName(input, context.packageManager) ?: input

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(callId, true, output = "App launched: $packageName")
        } else {
            ToolResult(callId, false, error = "App not found: $packageName (input: $input)")
        }
    }
}

class ListAppsTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "list_apps",
        description = "列出已安装应用",
        parameters = mapOf(
            "query" to ToolParameter(
                name = "query",
                type = "string",
                description = "搜索关键词",
                required = false
            )
        ),
        requiredPermissions = listOf("QUERY_ALL_PACKAGES"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val query = getOptionalArgument(arguments, "query")
        val callId = arguments["callId"] as? String ?: ""

        val apps = context.packageManager.getInstalledApplications(0)
            .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
            .filter { query == null || it.packageName.contains(query, true) || (it.loadLabel(context.packageManager).toString().contains(query, true)) }
            .map { "${it.packageName} - ${it.loadLabel(context.packageManager)}" }

        return ToolResult(callId, true, apps.joinToString("\n"))
    }
}

class ReadClipboardTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "read_clipboard",
        description = "读取剪贴板内容",
        parameters = emptyMap(),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        return if (clip != null && clip.itemCount > 0) {
            ToolResult(callId, true, clip.getItemAt(0).text?.toString() ?: "")
        } else {
            ToolResult(callId, true, output = "Clipboard is empty")
        }
    }
}

class WriteClipboardTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "write_clipboard",
        description = "写入剪贴板",
        parameters = mapOf(
            "text" to ToolParameter(
                name = "text",
                type = "string",
                description = "要写入的文本",
                required = true
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val text = getRequiredArgument(arguments, "text")
        val callId = arguments["callId"] as? String ?: ""
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        return ToolResult(callId, true, output = "Copied to clipboard")
    }
}

class GetScreenInfoTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "get_screen_info",
        description = "获取屏幕信息",
        parameters = emptyMap(),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val display = context.getSystemService(Context.WINDOW_SERVICE).let {
            (it as android.view.WindowManager).defaultDisplay
        }
        val metrics = android.util.DisplayMetrics()
        display.getMetrics(metrics)
        val info = buildString {
            appendLine("Resolution: ${metrics.widthPixels}x${metrics.heightPixels}")
            appendLine("Density: ${metrics.density}")
            appendLine("DensityDpi: ${metrics.densityDpi}")
            appendLine("ScaledDensity: ${metrics.scaledDensity}")
            appendLine("RefreshRate: ${display.refreshRate}Hz")
        }
        return ToolResult(callId, true, info.trimEnd())
    }
}

class SetUserTimezoneTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "set_user_timezone",
        description = "设置用户时区",
        parameters = mapOf(
            "timezone_name" to ToolParameter(
                name = "timezone_name",
                type = "string",
                description = "IANA 时区名称 (如 Asia/Shanghai)",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val timezoneName = getRequiredArgument(arguments, "timezone_name")
        val callId = arguments["callId"] as? String ?: ""
        return try {
            val zoneId = java.time.ZoneId.of(timezoneName)
            val key = stringPreferencesKey("timezone")
            context.timezoneDataStore.edit { prefs -> prefs[key] = zoneId.id }
            ToolResult(callId, true, output = "Timezone set to: $timezoneName")
        } catch (e: java.time.zone.ZoneRulesException) {
            ToolResult(callId, false, error = "Invalid timezone: $timezoneName")
        }
    }
}

class SendFileToUserTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "send_file_to_user",
        description = "发送文件给用户，文件将在聊天界面中展示，用户可保存或分享",
        parameters = mapOf(
            "file_path" to ToolParameter(
                name = "file_path",
                type = "string",
                description = "文件路径",
                required = true
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val filePath = getRequiredArgument(arguments, "file_path")
        val callId = arguments["callId"] as? String ?: ""
        val file = File(filePath)
        if (!file.exists()) {
            return ToolResult(callId, false, error = "File not found: $filePath")
        }

        val fileName = file.name
        val fileSize = file.length()
        val sizeStr = when {
            fileSize >= 1_073_741_824 -> "%.1f GB".format(fileSize / 1_073_741_824.0)
            fileSize >= 1_048_576 -> "%.1f MB".format(fileSize / 1_048_576.0)
            fileSize >= 1024 -> "%.1f KB".format(fileSize / 1024.0)
            else -> "$fileSize B"
        }

        return ToolResult(
            callId = callId,
            success = true,
            output = "[附件: $filePath]",
            forLLM = "File sent to user: $fileName ($sizeStr)",
            forUser = "已发送文件: $fileName ($sizeStr)\n[附件: $filePath]",
            media = listOf(filePath)
        )
    }
}

class GetTokenUsageTool(
    private val tokenUsageManager: TokenUsageManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "get_token_usage",
        description = "查询 LLM Token 使用统计（支持按天数、模型、提供商过滤）",
        parameters = mapOf(
            "days" to ToolParameter(
                name = "days",
                type = "integer",
                description = "查询最近 N 天的使用量（默认 30）",
                required = false,
                defaultValue = "30"
            ),
            "model_name" to ToolParameter(
                name = "model_name",
                type = "string",
                description = "按模型名过滤（可选）",
                required = false
            ),
            "provider_id" to ToolParameter(
                name = "provider_id",
                type = "string",
                description = "按提供商 ID 过滤（可选）",
                required = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val days = (getOptionalArgument(arguments, "days", "30")?.toIntOrNull() ?: 30).coerceIn(1, 365)
        val modelName = getOptionalArgument(arguments, "model_name")
        val providerId = getOptionalArgument(arguments, "provider_id")

        return try {
            val endDate = java.time.LocalDate.now()
            val startDate = endDate.minusDays(days.toLong())
            val summary = tokenUsageManager.getSummary(startDate, endDate)

            val sb = StringBuilder()
            val filterDesc = mutableListOf<String>()
            modelName?.let { filterDesc.add("model=$it") }
            providerId?.let { filterDesc.add("provider=$it") }
            if (filterDesc.isEmpty()) filterDesc.add("all models")

            sb.appendLine("Token 使用统计 ($startDate ~ $endDate, ${filterDesc.joinToString(", ")}):")
            sb.appendLine()
            val totalTokens = summary.totalInputTokens + summary.totalOutputTokens
            sb.appendLine("- 总 Token: ${formatCount(totalTokens)}")
            sb.appendLine("- 输入 Token: ${formatCount(summary.totalInputTokens)}")
            sb.appendLine("- 输出 Token: ${formatCount(summary.totalOutputTokens)}")
            sb.appendLine("- 总调用次数: ${summary.totalCalls}")
            sb.appendLine()

            // 按模型分组
            val filteredModels = summary.byModel.entries.filter { (key, _) ->
                (modelName == null || key.contains(modelName, ignoreCase = true)) &&
                (providerId == null || key.startsWith(providerId, ignoreCase = true))
            }

            if (filteredModels.isNotEmpty()) {
                sb.appendLine("按模型:")
                filteredModels.sortedByDescending { it.value.inputTokens + it.value.outputTokens }
                    .forEach { (model, stats) ->
                        val tokens = stats.inputTokens + stats.outputTokens
                        sb.appendLine("  - $model: ${formatCount(tokens)} tokens (${stats.calls} 次调用)")
                    }
            }

            ToolResult(callId, true, sb.toString().trimEnd())
        } catch (e: Exception) {
            ToolResult(callId, false, error = "获取 Token 统计失败: ${e.message}")
        }
    }

    private fun formatCount(count: Long): String = when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fk", count / 1_000.0)
        else -> count.toString()
    }
}

class SetVolumeTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "set_volume",
        description = "设置音量",
        parameters = mapOf(
            "volume" to ToolParameter(
                name = "volume",
                type = "integer",
                description = "音量值 (0-最大音量)",
                required = true
            ),
            "stream_type" to ToolParameter(
                name = "stream_type",
                type = "string",
                description = "音量类型: ring/notification/music/alarm/system",
                required = false,
                defaultValue = "music"
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val volume = (arguments["volume"] as? Number)?.toInt() ?: return ToolResult("", false, error = "Missing volume value")
        val streamType = getOptionalArgument(arguments, "stream_type", "music")!!
        val callId = arguments["callId"] as? String ?: ""

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (streamType) {
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "music" -> AudioManager.STREAM_MUSIC
            "alarm" -> AudioManager.STREAM_ALARM
            "system" -> AudioManager.STREAM_SYSTEM
            else -> AudioManager.STREAM_MUSIC
        }

        val maxVolume = audioManager.getStreamMaxVolume(stream)
        val clampedVolume = volume.coerceIn(0, maxVolume)
        audioManager.setStreamVolume(stream, clampedVolume, 0)

        return ToolResult(callId, true, output = "Volume set to $clampedVolume/$maxVolume (type: $streamType)")
    }
}

