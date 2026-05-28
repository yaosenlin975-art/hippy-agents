package com.lin.hippyagent.core.behavior

import com.lin.hippyagent.core.tools.android.AppPackageResolver
import timber.log.Timber
import java.io.File

object BehaviorSkillWriter {

    data class WriteResult(val skillName: String, val skillFilePath: String)

    fun writeSkill(events: List<BehaviorRecorder.RecordedEvent>, appAlias: String, workspaceDir: File): WriteResult? {
        if (events.isEmpty()) return null

        val packageName = events.first().packageName
        val skillName = "behavior_${packageName.replace(".", "_")}_${System.currentTimeMillis()}"
        val skillDir = File(workspaceDir, "skills/$skillName")
        skillDir.mkdirs()

        val steps = events.filter { it.text != null || it.contentDescription != null }
            .distinctBy { it.className to it.text }
            .take(20)

        val skillContent = buildString {
            appendLine("# $appAlias 快捷操作")
            appendLine()
            appendLine("## 触发条件")
            appendLine("- shouldUse: 用户要求打开或操作 $appAlias")
            appendLine("- shouldNotUse: 与 $appAlias 无关的请求")
            appendLine()
            appendLine("## 操作步骤")
            steps.forEachIndexed { index, event ->
                val desc = event.text ?: event.contentDescription ?: "操作${index + 1}"
                appendLine("${index + 1}. $desc (${event.className?.substringAfterLast(".")})")
            }
            appendLine()
            appendLine("## 包名")
            appendLine(packageName)
        }

        val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText(skillContent)

        Timber.i("BehaviorSkillWriter: wrote skill $skillName with ${steps.size} steps")
        return WriteResult(skillName, skillFile.absolutePath)
    }
}
