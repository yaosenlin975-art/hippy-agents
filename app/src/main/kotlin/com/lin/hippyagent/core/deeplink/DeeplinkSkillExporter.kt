package com.lin.hippyagent.core.deeplink

import com.lin.hippyagent.core.deeplink.model.DeeplinkBookmark
import com.lin.hippyagent.core.skill.SkillManager
import timber.log.Timber
import java.io.File

class DeeplinkSkillExporter(
    private val skillManager: SkillManager
) {
    data class ExportResult(val success: Boolean, val skillName: String?, val reason: String?)

    fun export(bookmark: DeeplinkBookmark): ExportResult {
        val skillName = generateSkillName(bookmark)

        if (bookmark.dataUri.isNullOrBlank() && bookmark.effectiveAmCommand.isNullOrBlank()) {
            return ExportResult(false, null, "bookmark 无 dataUri 且无 amCommand，无法导出")
        }

        return runCatching {
            val skillDir = File(skillManager.skillsDir, skillName)
            skillDir.mkdirs()
            val skillMd = buildSkillMarkdown(bookmark, skillName)
            File(skillDir, "SKILL.md").writeText(skillMd)
            skillManager.rebuildIndex()
            Timber.i("DeeplinkSkillExporter: exported skill $skillName")
            ExportResult(true, skillName, null)
        }.getOrElse {
            Timber.w(it, "DeeplinkSkillExporter: export failed")
            ExportResult(false, null, it.message)
        }
    }

    /**
     * 智能命名：appName + cleanTitle/pageType，take(10) ≤10 字符。
     */
    private fun generateSkillName(bookmark: DeeplinkBookmark): String {
        val appPart = bookmark.appName.take(4)
        val pagePart = extractPageTypeFromUri(bookmark.dataUri)
            ?: cleanTitle(bookmark.pageTitle).take(6)
        return "${appPart}_${pagePart}".take(10).replace(" ", "_")
    }

    /**
     * URI 路径推断 pageType。
     */
    private fun extractPageTypeFromUri(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        for ((pattern, type) in URI_PAGE_TYPE_PATTERNS) {
            if (pattern.containsMatchIn(uri)) return type
        }
        return null
    }

    private fun cleanTitle(title: String): String {
        return title.replace(CLEAN_TITLE_REGEX, "").trim().ifBlank { "page" }
    }

    private fun buildSkillMarkdown(bookmark: DeeplinkBookmark, skillName: String): String = buildString {
        val emoji = AppEmojiMapper.map(bookmark.packageName)
        appendLine("---")
        appendLine("name: $skillName")
        appendLine("description: |")
        appendLine("  ${bookmark.appName} ${bookmark.pageTitle} 一键直达")
        appendLine("metadata:")
        appendLine("  always: true")
        appendLine("  emoji: $emoji")
        appendLine("  version: 1.0.0")
        appendLine("  category: deeplink")
        appendLine("keywords:")
        appendLine("  - \"${bookmark.appName}\"")
        appendLine("  - \"${bookmark.pageTitle}\"")
        appendLine("should_use:")
        appendLine("  - \"用户要求打开 ${bookmark.appName} ${bookmark.pageTitle}\"")
        appendLine("should_not_use:")
        appendLine("  - \"与 ${bookmark.appName} 无关的请求\"")
        appendLine("---")
        appendLine()
        appendLine("# $emoji ${bookmark.appName} · ${bookmark.pageTitle}")
        appendLine()
        appendLine("## 🚫 绝对禁止 am start / start_activity")
        appendLine()
        appendLine("直接执行 `am start` 会导致：")
        appendLine("- 空参数 Activity fallback 到首页，无法直达目标页")
        appendLine("- 部分 Activity 需 system flag，普通应用调 am start 触发 SecurityException")
        appendLine("- 缺少 extras 导致目标页白屏或崩溃")
        appendLine()
        appendLine("## ✅ 唯一正确方式：device(action=open, uri=...)")
        appendLine()
        if (!bookmark.dataUri.isNullOrBlank()) {
            appendLine("```kotlin")
            appendLine("device(action = \"open\", uri = \"${bookmark.dataUri}\")")
            appendLine("```")
        } else if (!bookmark.effectiveAmCommand.isNullOrBlank()) {
            appendLine("需 Shizuku/Root 权限执行：")
            appendLine("```shell")
            appendLine(bookmark.effectiveAmCommand)
            appendLine("```")
        }
        appendLine()
        appendLine("## think→action 一致性自检")
        appendLine()
        appendLine("- [ ] think 中提到的页面 == action 目标页面")
        appendLine("- [ ] think 中提到的 App == ${bookmark.appName}")
        appendLine("- [ ] 未使用 am start / start_activity")
        appendLine()
        appendLine("## 包名")
        appendLine(bookmark.packageName)
    }

    companion object {
        private val CLEAN_TITLE_REGEX = Regex("""[\\/:*?"<>|]""")

        private val URI_PAGE_TYPE_PATTERNS: List<Pair<Regex, String>> = listOf(
            Regex("seckill") to "秒杀",
            Regex("deal-detail") to "团购",
            Regex("/food") to "美食",
            Regex("/hotel") to "酒店",
            Regex("/movie") to "电影",
            Regex("/shop") to "商家",
            Regex("/search") to "搜索",
            Regex("/detail") to "商品",
            Regex("shop") to "店铺",
            Regex("/cart") to "购物车",
            Regex("/video") to "视频",
            Regex("/live") to "直播",
            Regex("/user") to "主页"
        )
    }
}
