package com.lin.hippyagent.core.skill.install

import android.content.Context
import com.lin.hippyagent.core.plugin.SkillValidator
import com.lin.hippyagent.core.plugin.UrlDownloader
import com.lin.hippyagent.core.plugin.ZipHelper
import com.lin.hippyagent.core.skill.SecurityIssue
import com.lin.hippyagent.core.skill.SkillInfo
import com.lin.hippyagent.core.skill.SkillScanResult
import com.lin.hippyagent.core.skill.SkillScanner
import com.lin.hippyagent.core.skill.index.SkillIndexManager
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

private val PROTECTED_SKILLS = setOf("caveman", "context-engineering", "diagnose", "writing-plans")

class SkillInstaller(
    private val context: Context,
    private val skillsDir: File,
    private val skillScanner: SkillScanner,
    private val indexManager: SkillIndexManager
) {

    suspend fun installFromZip(zipPath: String): Result<SkillInfo> {
        val zipFile = File(zipPath)
        if (!zipFile.exists()) {
            return Result.failure(IllegalArgumentException("ZIP file not found: $zipPath"))
        }

        val validationResult = SkillValidator.validateZipStructure(zipFile)
        if (validationResult.isFailure) {
            return Result.failure(validationResult.exceptionOrNull() ?: RuntimeException("Skill validation failed"))
        }

        val name = validationResult.getOrNull()?.name ?: "unknown_skill"
        require(!name.contains("..") && !name.contains("/") && !name.contains("\\")) {
            "Invalid skill name: $name"
        }

        val skillDir = skillsDir.resolve(name)
        if (skillDir.exists()) {
            return Result.failure(IllegalStateException("Skill already exists: ${skillDir.name}"))
        }

        return runCatching {
            ZipHelper.unzip(zipFile, skillDir)
            val scanResult = scanSkillDir(skillDir)
            if (scanResult.hasHighSeverityIssues) {
                skillDir.deleteRecursively()
                return@runCatching Result.failure(
                    SecurityException("安全扫描未通过，安装已阻止:\n${scanResult.summary()}")
                )
            }
            val skillInfo = indexManager.parseSkillInfo(skillDir)
            Timber.d("Skill installed: ${skillInfo.name}")
            indexManager.invalidate()
            Result.success(skillInfo)
        }.getOrElse { e ->
            if (e is Result<*>) @Suppress("UNCHECKED_CAST") e as Result<SkillInfo>
            else Result.failure(e)
        }
    }

    suspend fun installFromUrl(url: String, checksum: String? = null): Result<SkillInfo> {
        val tempZip = File.createTempFile("skill_download", ".zip", context.cacheDir)
        try {
            val downloadResult = UrlDownloader.downloadFile(url, tempZip)
            if (downloadResult.isFailure) {
                return Result.failure(downloadResult.exceptionOrNull() ?: RuntimeException("Skill download failed"))
            }

            if (!checksum.isNullOrBlank()) {
                val actualChecksum = computeSha256(tempZip)
                if (!actualChecksum.equals(checksum, ignoreCase = true)) {
                    tempZip.delete()
                    return Result.failure(
                        SecurityException("Checksum mismatch: expected=$checksum, actual=$actualChecksum")
                    )
                }
            }

            return installFromZip(tempZip.absolutePath)
        } finally {
            if (tempZip.exists()) {
                tempZip.delete()
            }
        }
    }

    fun uninstallSkill(skillId: String): Result<Unit> {
        require(skillId !in PROTECTED_SKILLS) { "Cannot uninstall protected skill: $skillId" }
        require(skillId !in SkillIndexManager.EXCLUDED_DIRS) { "Cannot uninstall system directory: $skillId" }
        val skillDir = skillsDir.resolve(skillId)
        if (!skillDir.exists()) {
            return Result.failure(IllegalArgumentException("Skill not found: $skillId"))
        }

        return runCatching {
            skillDir.deleteRecursively()
            Timber.d("Skill uninstalled: $skillId")
            indexManager.invalidate()
        }
    }

    fun enableSkill(skillId: String): Result<Unit> {
        if (skillId in SkillIndexManager.EXCLUDED_DIRS) return Result.failure(IllegalArgumentException("Cannot operate on system directory: $skillId"))
        val skillDir = skillsDir.resolve(skillId)
        if (!skillDir.exists()) {
            return Result.failure(IllegalArgumentException("Skill not found: $skillId"))
        }

        val marker = skillDir.resolve(".disabled")
        if (marker.exists()) {
            val deleted = marker.delete()
            if (!deleted) return Result.failure(RuntimeException("Failed to remove .disabled marker"))
        }
        return Result.success(Unit)
    }

    fun disableSkill(skillId: String): Result<Unit> {
        if (skillId in SkillIndexManager.EXCLUDED_DIRS) return Result.failure(IllegalArgumentException("Cannot operate on system directory: $skillId"))
        val skillDir = skillsDir.resolve(skillId)
        if (!skillDir.exists()) {
            return Result.failure(IllegalArgumentException("Skill not found: $skillId"))
        }

        val marker = skillDir.resolve(".disabled")
        val created = marker.createNewFile()
        if (!created) return Result.failure(RuntimeException("Failed to create .disabled marker"))
        return Result.success(Unit)
    }

    fun isEnabled(skillId: String): Boolean {
        val skillDir = skillsDir.resolve(skillId)
        if (!skillDir.exists()) return false
        return !skillDir.resolve(".disabled").exists()
    }

    fun setEnabled(skillId: String, enabled: Boolean) {
        if (enabled) enableSkill(skillId) else disableSkill(skillId)
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun scanSkillDir(skillDir: File): SkillScanResult {
        val allIssues = mutableListOf<SecurityIssue>()
        skillDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val content = runCatching { file.readText() }.getOrNull() ?: return@forEach
            val issues = skillScanner.scanForSecurityIssues(content)
            allIssues.addAll(issues.map { it.copy(description = "[${file.relativeTo(skillDir)}] ${it.description}") })
        }
        return SkillScanResult(allIssues)
    }
}
