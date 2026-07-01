package com.lin.hippyagent.core.deeplink

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lin.hippyagent.core.privilege.SystemApiBridge
import timber.log.Timber

class DeeplinkLauncher(
    private val context: Context,
    private val bridge: SystemApiBridge
) {
    sealed class LaunchResult {
        data object Success : LaunchResult()
        data class Failed(val reason: String) : LaunchResult()
    }

    suspend fun launch(spec: CapturedIntentSpec): LaunchResult {
        if (bridge.availablePrivilege() != SystemApiBridge.PrivilegeLevel.NONE) {
            val amCmd = buildAmCommand(spec)
            val result = bridge.execute(amCmd)
            if (result.isSuccess) return LaunchResult.Success
            Timber.w("DeeplinkLauncher: am start failed, fallback to direct intent")
        }
        return tryDirectIntent(spec)
    }

    private fun tryDirectIntent(spec: CapturedIntentSpec): LaunchResult {
        val intent = Intent().apply {
            spec.action?.let { action = it }
            spec.dataUri?.let { data = Uri.parse(it) }
            spec.component?.let { setClassName(context, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            spec.flags?.let { flags = parseFlags(it) }
            for ((key, value) in spec.extras) putExtra(key, value)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return LaunchResult.Failed("当前页面仅支持 Shizuku/Root 回放，普通 Intent 无法解析")
        }
        return runCatching {
            context.startActivity(intent)
            LaunchResult.Success
        }.getOrElse { LaunchResult.Failed(it.message ?: "startActivity failed") }
    }

    private fun parseFlags(flg: String): Int {
        return flg.split("|").mapNotNull { token ->
            val trimmed = token.trim()
            trimmed.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: runCatching {
                Intent::class.java.getField(trimmed).getInt(null)
            }.getOrNull()
        }.fold(0) { acc, flag -> acc or flag }
    }

    companion object {
        private fun shellEscape(s: String): String = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")

        fun buildAmCommand(spec: CapturedIntentSpec): String = buildString {
            append("am start")
            spec.action?.let { append(" -a \"").append(shellEscape(it)).append("\"") }
            spec.dataUri?.let { append(" -d \"").append(shellEscape(it)).append("\"") }
            spec.component?.let { append(" -n \"").append(shellEscape(it)).append("\"") }
            spec.flags?.let { append(" -f \"").append(shellEscape(it)).append("\"") }
            for ((key, value) in spec.extras) {
                append(" --es \"").append(shellEscape(key)).append("\" \"").append(shellEscape(value)).append("\"")
            }
        }
    }
}
