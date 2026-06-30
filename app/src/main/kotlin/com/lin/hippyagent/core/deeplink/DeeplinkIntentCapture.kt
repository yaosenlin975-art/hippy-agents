package com.lin.hippyagent.core.deeplink

import com.lin.hippyagent.core.privilege.SystemApiBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通过 `dumpsys activity activities` 三级降级抓取当前前台 Activity 的完整 Intent。
 * Shizuku 优先 → Root 回退 → 都不可用返回 null（fail-open，不阻断录制）。
 */
class DeeplinkIntentCapture(
    private val bridge: SystemApiBridge
) {
    suspend fun capture(packageName: String, activityClassName: String): CapturedIntentSpec? =
        withContext(Dispatchers.IO) {
            val shortClass = activityClassName.substringAfterLast(".")
            val output = tryLevel1(shortClass)
                ?: tryLevel2(packageName)
                ?: tryLevel3()
                ?: return@withContext null
            DeeplinkIntentParser.parseBlock(output, packageName, shortClass)
        }

    private suspend fun tryLevel1(shortClass: String): String? {
        val cmd = "dumpsys activity activities | grep -B 2 -A 60 'Hist.*$shortClass' | head -80"
        return runCatching {
            bridge.execute(cmd).getOrNull()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun tryLevel2(packageName: String): String? {
        val confirmCmd = "dumpsys activity activities | grep mResumedActivity"
        val confirm = runCatching { bridge.execute(confirmCmd).getOrNull() }.getOrNull()
        if (confirm?.contains(packageName) != true) return null
        val cmd = "dumpsys activity activities | grep -B 2 -A 60 'Hist.*$packageName' | head -120"
        return runCatching { bridge.execute(cmd).getOrNull()?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    private suspend fun tryLevel3(): String? {
        return runCatching { bridge.execute("dumpsys activity activities").getOrNull() }.getOrNull()
    }
}
