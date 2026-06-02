package com.lin.hippyagent.core.debug

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.lin.hippyagent.core.skill.index.SkillIndexManager
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugInfo(
    val appInfo: Map<String, String>,
    val deviceInfo: Map<String, String>,
    val runtimeInfo: Map<String, String>,
    val storageInfo: Map<String, String>,
    val dataInfo: Map<String, String>,
    val recentErrors: List<String>
)

class DebugInfoCollector(private val context: Context) {

    fun collect(): DebugInfo {
        return DebugInfo(
            appInfo = collectAppInfo(),
            deviceInfo = collectDeviceInfo(),
            runtimeInfo = collectRuntimeInfo(),
            storageInfo = collectStorageInfo(),
            dataInfo = collectDataInfo(),
            recentErrors = collectRecentErrors()
        )
    }

    fun toPlainText(info: DebugInfo = collect()): String {
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sb.appendLine("=== Hippy Debug Info ===")
        sb.appendLine("Generated: ${fmt.format(Date())}")
        sb.appendLine()

        sb.appendLine("[App Info]")
        info.appInfo.forEach { (k, v) -> sb.appendLine("  $k: $v") }
        sb.appendLine()

        sb.appendLine("[Device]")
        info.deviceInfo.forEach { (k, v) -> sb.appendLine("  $k: $v") }
        sb.appendLine()

        sb.appendLine("[Runtime]")
        info.runtimeInfo.forEach { (k, v) -> sb.appendLine("  $k: $v") }
        sb.appendLine()

        sb.appendLine("[Storage]")
        info.storageInfo.forEach { (k, v) -> sb.appendLine("  $k: $v") }
        sb.appendLine()

        sb.appendLine("[Data]")
        info.dataInfo.forEach { (k, v) -> sb.appendLine("  $k: $v") }
        sb.appendLine()

        if (info.recentErrors.isNotEmpty()) {
            sb.appendLine("[Recent Errors]")
            info.recentErrors.forEach { sb.appendLine("  $it") }
        }

        return sb.toString()
    }

    private fun collectAppInfo(): Map<String, String> {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        val versionCode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toString()
            }
        } catch (_: Exception) { "?" }
        return mapOf(
            "版本" to versionName,
            "版本号" to versionCode,
            "包名" to context.packageName,
            "数据目录" to context.filesDir.absolutePath,
            "最小SDK" to "${context.applicationInfo.minSdkVersion}",
            "目标SDK" to "${context.applicationInfo.targetSdkVersion}"
        )
    }

    private fun collectDeviceInfo(): Map<String, String> = mapOf(
        "型号" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "系统" to "Android ${Build.VERSION.RELEASE}",
        "SDK" to "${Build.VERSION.SDK_INT}",
        "安全补丁" to (Build.VERSION.SECURITY_PATCH ?: "N/A"),
        "CPU架构" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    )

    private fun collectRuntimeInfo(): Map<String, String> {
        val rt = Runtime.getRuntime()
        val usedMB = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val maxMB = rt.maxMemory() / 1048576
        val totalMB = rt.totalMemory() / 1048576
        val threadCount = Thread.activeCount()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return mapOf(
            "PID" to "${android.os.Process.myPid()}",
            "内存已用" to "${usedMB}MB",
            "内存最大" to "${maxMB}MB",
            "内存总量" to "${totalMB}MB",
            "线程数" to "$threadCount",
            "时间" to fmt.format(Date())
        )
    }

    private fun collectStorageInfo(): Map<String, String> {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalGB = stat.totalBytes / 1073741824.0
        val availGB = stat.availableBytes / 1073741824.0
        val appDir = context.filesDir
        val appSizeMB = calculateDirSize(appDir) / 1048576.0
        return mapOf(
            "总容量" to String.format("%.1fGB", totalGB),
            "可用" to String.format("%.1fGB", availGB),
            "App数据" to String.format("%.1fMB", appSizeMB)
        )
    }

    private fun collectDataInfo(): Map<String, String> {
        val copaw = context.filesDir
        val agents = File(copaw, "agents").listFiles()?.filter { it.extension == "json" }?.size ?: 0
        var sessions = 0
        val ws = File(copaw, "workspaces")
        if (ws.exists()) ws.listFiles()?.forEach { d ->
            sessions += File(d, "sessions").listFiles()?.filter { it.extension == "json" }?.size ?: 0
        }
        val skills = File(copaw, "skills").listFiles()?.count { it.isDirectory && it.name !in SkillIndexManager.EXCLUDED_DIRS && !it.name.startsWith(".") } ?: 0
        return mapOf(
            "智能体" to "${agents}个",
            "会话" to "${sessions}个",
            "技能" to "${skills}个"
        )
    }

    private fun collectRecentErrors(): List<String> {
        val errors = mutableListOf<String>()
        val logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) return errors
        logDir.listFiles()?.filter { it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()
            ?.let { file ->
                try {
                    file.readLines()
                        .filter { "ERROR" in it || "CRITICAL" in it || "Exception" in it }
                        .takeLast(10)
                        .forEach { errors.add(it.take(200)) }
                } catch (_: Exception) {}
            }
        return errors
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) size += f.length() }
        return size
    }
}



