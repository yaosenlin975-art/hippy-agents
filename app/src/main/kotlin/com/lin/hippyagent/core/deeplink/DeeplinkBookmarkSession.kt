package com.lin.hippyagent.core.deeplink

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.Immutable
import com.lin.hippyagent.core.accessibility.PhoneControlAccessibilityService
import com.lin.hippyagent.core.deeplink.model.DeeplinkBookmark
import com.lin.hippyagent.core.deeplink.model.TrackedPage
import com.lin.hippyagent.core.privilege.SystemApiBridge

@Immutable
data class SessionState(
    val currentPage: TrackedPage? = null
)

class DeeplinkBookmarkSession(
    private val bridge: SystemApiBridge,
    private val intentCapture: DeeplinkIntentCapture,
    private val skillExporter: DeeplinkSkillExporter
) {
    @Volatile
    private var sessionState = SessionState()

    /**
     * start 保持非 suspend 以适配 UI 按钮同步调用。
     * privilege 探测延迟到 bookmarkCurrentPage（已是 suspend）内执行，更准确反映当前时刻可用性。
     */
    fun start(application: Application) {
        sessionState = SessionState(currentPage = buildTrackedPage(application))
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val activity = event.className?.toString() ?: return
        sessionState = sessionState.copy(
            currentPage = TrackedPage(pkg, activity, windowTitle = "", appName = pkg)
        )
    }

    suspend fun bookmarkCurrentPage(application: Application): String {
        val page = sessionState.currentPage ?: return "无当前页面信息"
        val privilege = bridge.availablePrivilege()
        if (privilege == SystemApiBridge.PrivilegeLevel.NONE) {
            return "Shizuku/Root 不可用，无法捕获 Deeplink"
        }

        val spec = runCatching {
            intentCapture.capture(page.packageName, page.activityClassName)
        }.getOrNull()

        if (spec == null) {
            return "当前使用 Activity 兜底（dumpsys 解析失败）"
        }

        val bookmark = DeeplinkBookmark(
            id = System.currentTimeMillis(),
            packageName = page.packageName,
            appName = page.appName,
            pageTitle = page.displayTitle,
            activityClassName = page.activityClassName,
            capturedAt = System.currentTimeMillis(),
            action = spec.action,
            dataUri = spec.dataUri,
            component = spec.component,
            extras = spec.extras,
            intentCommand = DeeplinkLauncher.buildAmCommand(spec)
        )

        DeeplinkBookmarkStore.add(bookmark)
        val exportResult = skillExporter.export(bookmark)

        return when {
            exportResult.success -> "已收藏并创建快捷指令「${exportResult.skillName ?: "未命名"}」"
            !bookmark.dataUri.isNullOrBlank() -> "已捕获 Deeplink（Skill 导出失败）"
            bookmark.hasPreciseJumpSpec -> "已捕获可回放参数"
            else -> "当前使用 Activity 兜底"
        }
    }

    fun stop() {
        sessionState = SessionState()
    }

    private fun buildTrackedPage(application: Application): TrackedPage? {
        val service = PhoneControlAccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null
        return TrackedPage(
            packageName = root.packageName?.toString() ?: "",
            activityClassName = "",
            windowTitle = "",
            appName = root.packageName?.toString() ?: ""
        )
    }
}
