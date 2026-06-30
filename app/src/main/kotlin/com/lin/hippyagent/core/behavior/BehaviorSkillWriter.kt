package com.lin.hippyagent.core.behavior

import com.lin.hippyagent.core.skill.SkillManager
import timber.log.Timber

object BehaviorSkillWriter {

    data class WriteResult(val skillName: String, val skillFilePath: String)

    fun writeSkill(
        events: List<BehaviorRecorder.RecordedEvent>,
        appAlias: String,
        skillManager: SkillManager
    ): WriteResult? {
        if (events.isEmpty()) return null

        val packageName = events.first().packageName
        val skillName = "behavior_${packageName.replace(".", "_")}_${System.currentTimeMillis()}"
        val skillDir = java.io.File(skillManager.skillsDir, skillName)
        skillDir.mkdirs()

        val steps = events.filter { it.text != null || it.contentDescription != null }
            .distinctBy { it.className to it.text }
            .take(20)

        val skillContent = buildSkillMarkdown(skillName, steps, appAlias, packageName)
        java.io.File(skillDir, "SKILL.md").writeText(skillContent)

        skillManager.rebuildIndex()

        Timber.i("BehaviorSkillWriter: wrote skill $skillName with ${steps.size} steps")
        return WriteResult(skillName, java.io.File(skillDir, "SKILL.md").absolutePath)
    }

    private fun buildSkillMarkdown(
        skillName: String,
        steps: List<BehaviorRecorder.RecordedEvent>,
        appAlias: String,
        packageName: String
    ): String = buildString {
        appendLine("---")
        appendLine("name: $skillName")
        appendLine("description: $appAlias 快捷操作录制")
        appendLine("version: 1.0.0")
        appendLine("builtin: false")
        appendLine("keywords:")
        appendLine("  - \"$appAlias\"")
        appendLine("should_use:")
        appendLine("  - \"用户要求打开或操作 $appAlias\"")
        appendLine("should_not_use:")
        appendLine("  - \"与 $appAlias 无关的请求\"")
        appendLine("---")
        appendLine()
        appendLine("# $appAlias 快捷操作")
        appendLine()
        appendLine("## 触发条件")
        appendLine("- shouldUse: 用户要求打开或操作 $appAlias")
        appendLine("- shouldNotUse: 与 $appAlias 无关的请求")
        appendLine()
        appendLine("## 操作步骤")
        steps.forEachIndexed { index, event ->
            val desc = event.text ?: event.contentDescription ?: "操作${index + 1}"
            appendLine("${index + 1}. $desc (${event.className.substringAfterLast(".")})")
        }
        appendLine()
        appendLine("## 包名")
        appendLine(packageName)
    }
}
