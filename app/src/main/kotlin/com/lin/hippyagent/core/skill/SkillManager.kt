package com.lin.hippyagent.core.skill

import android.content.Context
import com.lin.hippyagent.core.skill.config.SkillConfigManager
import com.lin.hippyagent.core.skill.index.SkillIndexManager
import com.lin.hippyagent.core.skill.install.SkillInstaller
import java.io.File

class SkillManager(
    context: Context,
    skillsDir: File = File(context.filesDir, "skills"),
    skillScanner: SkillScanner = SkillScanner()
) {
    internal val indexManager = SkillIndexManager(skillsDir)
    internal val installer = SkillInstaller(context, skillsDir, skillScanner, indexManager)
    internal val configManager = SkillConfigManager(skillsDir)

    fun loadIndex() = indexManager.loadIndex()
    fun saveIndex(index: SkillIndex) = indexManager.saveIndex(index)
    fun rebuildIndex() = indexManager.rebuildIndex()
    fun getManifest(skillId: String) = indexManager.getManifest(skillId)
    fun getSkillDir(skillId: String) = indexManager.getSkillDir(skillId)

    fun listSkills() = indexManager.listSkills()
    fun listSkillsFromIndex() = indexManager.listSkillsFromIndex()
    fun getSkill(skillId: String) = indexManager.getSkill(skillId)

    suspend fun installSkillFromZip(zipPath: String) = installer.installFromZip(zipPath)
    suspend fun installSkillFromUrl(url: String, checksum: String? = null) = installer.installFromUrl(url, checksum)
    fun uninstallSkill(skillId: String) = installer.uninstallSkill(skillId)
    fun enableSkill(skillId: String) = installer.enableSkill(skillId)
    fun disableSkill(skillId: String) = installer.disableSkill(skillId)
    fun isSkillEnabled(skillId: String) = installer.isEnabled(skillId)
    fun setSkillEnabled(skillId: String, enabled: Boolean) = installer.setEnabled(skillId, enabled)

    fun loadSkillConfig(skillId: String) = configManager.load(skillId)
    fun saveSkillConfig(config: SkillConfig) = configManager.save(config)
    fun getSkillSecret(skillId: String, key: String) = configManager.getSecret(skillId, key)
    fun saveSkillSecret(skillId: String, key: String, value: String) = configManager.saveSecret(skillId, key, value)

    internal fun parseSkillInfo(skillDir: File) = indexManager.parseSkillInfo(skillDir)
}

data class SkillScanResult(val issues: List<SecurityIssue>) {
    val hasHighSeverityIssues: Boolean get() = issues.any { it.severity == "HIGH" || it.severity == "CRITICAL" }

    fun summary(): String {
        return issues.take(5).joinToString("\n") { "- [${it.severity}] ${it.description}" } +
            if (issues.size > 5) "\n... 还有 ${issues.size - 5} 项" else ""
    }
}
