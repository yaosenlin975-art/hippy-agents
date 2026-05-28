package com.lin.hippyagent.core.skill

import android.content.Context
import com.lin.hippyagent.core.plugin.SkillValidator
import com.lin.hippyagent.core.plugin.ZipHelper
import com.lin.hippyagent.core.plugin.UrlDownloader
import com.lin.hippyagent.core.util.FileUtils
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

class SkillManager(
    private val context: Context,
    private val skillsDir: File = File(context.filesDir, "skills"),
    private val skillScanner: SkillScanner = SkillScanner()
) {
    private val configDir get() = File(skillsDir, "_config")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val indexFile get() = File(skillsDir, "_index.json")

    @Volatile
    private var cachedIndex: SkillIndex? = null

    private val frontmatterFieldPatterns = object : LinkedHashMap<String, Regex>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>) = size > 50
    }
    private val frontmatterYamlArrayPatterns = object : LinkedHashMap<String, Regex>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>) = size > 50
    }
    private val frontmatterInlineArrayPatterns = object : LinkedHashMap<String, Regex>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>) = size > 50
    }

    init {
        if (!skillsDir.exists()) {
            skillsDir.mkdirs()
        }
    }

    // --- Skill Index ---

    fun loadIndex(): SkillIndex {
        cachedIndex?.let { return it }
        if (!indexFile.exists()) return SkillIndex()
        return runCatching {
            json.decodeFromString<SkillIndex>(indexFile.readText()).also { cachedIndex = it }
        }.getOrElse { SkillIndex() }
    }

    fun saveIndex(index: SkillIndex) {
        runCatching {
            FileUtils.atomicWrite(indexFile, json.encodeToString(SkillIndex.serializer(), index))
            cachedIndex = index
        }.onFailure { Timber.e(it, "Failed to save skill index") }
    }

    fun rebuildIndex(): SkillIndex {
        val entries = mutableMapOf<String, SkillIndexEntry>()
        skillsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "_config" }?.forEach { dir ->
            val manifestFile = dir.resolve("manifest.json")
            val skillMd = dir.resolve("SKILL.md")
            val manifest = parseManifest(dir)
            val disabled = dir.resolve(".disabled").exists()
            if (manifest != null) {
                entries[dir.name] = SkillIndexEntry(
                    id = dir.name,
                    name = manifest.name,
                    description = manifest.description,
                    version = manifest.version,
                    source = manifest.source,
                    toolNames = manifest.tools.map { it.name },
                    triggers = manifest.triggers,
                    requiresBins = manifest.requires.bins,
                    protected = manifest.protected,
                    installedAt = dir.lastModified(),
                    updatedAt = dir.lastModified(),
                    manifestMtime = if (manifestFile.exists()) manifestFile.lastModified() else skillMd.lastModified(),
                    manifestJson = json.encodeToString(SkillManifest.serializer(), manifest)
                )
            } else {
                val info = runCatching { parseSkillInfo(dir) }.getOrNull()
                if (info != null) {
                    entries[dir.name] = SkillIndexEntry(
                        id = info.id,
                        name = info.name,
                        description = info.description,
                        version = info.version,
                        source = if (info.isBuiltin) "builtin" else "installed",
                        installedAt = info.installedAt,
                        updatedAt = info.installedAt
                    )
                } else {
                    entries[dir.name] = SkillIndexEntry(
                        id = dir.name,
                        name = dir.name,
                        description = "(broken)",
                        version = "0.0.0",
                        installedAt = dir.lastModified(),
                        updatedAt = dir.lastModified(),
                        broken = true
                    )
                }
            }
        }
        val index = SkillIndex(version = System.currentTimeMillis(), skills = entries)
        saveIndex(index)
        return index
    }

    fun getManifest(skillId: String): SkillManifest? {
        val skillDir = skillsDir.resolve(skillId)
        if (!skillDir.exists()) return null
        val index = loadIndex()
        val entry = index.skills[skillId]
        val manifestFile = skillDir.resolve("manifest.json")
        val skillMd = skillDir.resolve("SKILL.md")
        val currentMtime = if (manifestFile.exists()) manifestFile.lastModified()
            else if (skillMd.exists()) skillMd.lastModified()
            else 0L
        if (entry != null && entry.manifestMtime == currentMtime && currentMtime > 0 && entry.manifestJson.isNotBlank()) {
            return runCatching { json.decodeFromString<SkillManifest>(entry.manifestJson) }.getOrNull()
        }
        return parseManifest(skillDir)
    }

    private fun parseManifest(skillDir: File): SkillManifest? {
        val manifestFile = skillDir.resolve("manifest.json")
        if (manifestFile.exists()) {
            return runCatching {
                json.decodeFromString<SkillManifest>(manifestFile.readText())
            }.getOrNull()
        }
        return parseManifestFromSkillMd(skillDir)
    }

    private fun parseManifestFromSkillMd(skillDir: File): SkillManifest? {
        val skillMd = skillDir.resolve("SKILL.md")
        if (!skillMd.exists()) return null
        val content = skillMd.readText()
        val name = extractFrontmatterField(content, "name") ?: skillDir.name
        val description = extractFrontmatterField(content, "description") ?: ""
        val version = extractFrontmatterField(content, "version") ?: "1.0.0"
        val source = if (extractFrontmatterField(content, "builtin")?.lowercase() == "true") "builtin" else "installed"
        val keywords = extractFrontmatterList(content, "keywords")
        val fileExtensions = extractFrontmatterList(content, "file_extensions")
        val scenarios = extractFrontmatterList(content, "scenarios")
        val shouldUse = extractFrontmatterList(content, "should_use")
        val shouldNotUse = extractFrontmatterList(content, "should_not_use")
        val toolNames = extractFrontmatterList(content, "tools")
        val tools = toolNames.map { SkillToolDef(name = it, description = "") }
        return SkillManifest(
            name = name,
            description = description,
            version = version,
            source = source,
            tools = tools,
            triggers = SkillTriggers(
                keywords = keywords,
                fileExtensions = fileExtensions,
                scenarios = scenarios,
                shouldUse = shouldUse,
                shouldNotUse = shouldNotUse
            )
        )
    }

    private fun extractFrontmatterList(content: String, field: String): List<String> {
        val yamlArrayPattern = frontmatterYamlArrayPatterns.getOrPut(field) {
            Regex("""${Regex.escape(field)}:\s*\n((?:\s+-\s+.+\n?)+)""")
        }
        val match = yamlArrayPattern.find(content)
        if (match != null) {
            return match.groupValues[1].lines()
                .map { it.trim().removePrefix("- ").trim() }
                .filter { it.isNotBlank() }
        }
        val inlinePattern = frontmatterInlineArrayPatterns.getOrPut(field) {
            Regex("""${Regex.escape(field)}:\s*\[(.+)]""")
        }
        val inlineMatch = inlinePattern.find(content)
        if (inlineMatch != null) {
            return inlineMatch.groupValues[1].split(",").map { it.trim().trim('"').trim('\'') }.filter { it.isNotBlank() }
        }
        return emptyList()
    }

    // --- Skill Configuration ---

    fun loadSkillConfig(skillId: String): SkillConfig {
        configDir.mkdirs()
        val configFile = File(configDir, "$skillId.json")
        if (!configFile.exists()) return SkillConfig(skillId)
        return runCatching {
            json.decodeFromString<SkillConfig>(configFile.readText())
        }.getOrElse { SkillConfig(skillId) }
    }

    fun saveSkillConfig(config: SkillConfig): Result<Unit> = runCatching {
        configDir.mkdirs()
        val configFile = File(configDir, "${config.skillId}.json")
        FileUtils.atomicWrite(configFile, json.encodeToString(SkillConfig.serializer(), config))
    }

    fun getSkillSecret(skillId: String, key: String): String? {
        val config = loadSkillConfig(skillId)
        return config.secrets[key]
    }

    fun saveSkillSecret(skillId: String, key: String, value: String): Result<Unit> {
        val currentConfig = loadSkillConfig(skillId)
        val updatedConfig = currentConfig.copy(secrets = currentConfig.secrets + (key to value))
        return saveSkillConfig(updatedConfig)
    }

    suspend fun installSkillFromZip(zipPath: String): Result<SkillInfo> {
        val zipFile = File(zipPath)
        if (!zipFile.exists()) {
            return Result.failure(IllegalArgumentException("ZIP file not found: $zipPath"))
        }

        val validationResult = SkillValidator.validateZipStructure(zipFile)
        if (validationResult.isFailure) {
            return Result.failure(validationResult.exceptionOrNull() ?: RuntimeException("Skill validation failed"))
        }

        val skillDir = skillsDir.resolve(validationResult.getOrNull()?.name ?: "unknown_skill")
        if (skillDir.exists()) {
            return Result.failure(IllegalStateException("Skill already exists: ${skillDir.name}"))
        }

        return runCatching {
            ZipHelper.unzip(zipFile, skillDir)
            val scanResult = scanSkillDir(skillDir)
            if (scanResult.hasHighSeverityIssues) {
                skillDir.deleteRecursively()
                return Result.failure(
                    SecurityException("安全扫描未通过，安装已阻止:\n${scanResult.summary()}")
                )
            }
            val skillInfo = parseSkillInfo(skillDir)
            Timber.d("Skill installed: ${skillInfo.name}")
            cachedIndex = null
            skillInfo
        }
    }

    suspend fun installSkillFromUrl(url: String, checksum: String? = null): Result<SkillInfo> {
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

            return installSkillFromZip(tempZip.absolutePath)
        } finally {
            if (tempZip.exists()) {
                tempZip.delete()
            }
        }
    }

    fun uninstallSkill(skillId: String): Result<Unit> {
        val skillDir = skillsDir.resolve(skillId)
        if (!skillDir.exists()) {
            return Result.failure(IllegalArgumentException("Skill not found: $skillId"))
        }

        return runCatching {
            skillDir.deleteRecursively()
            Timber.d("Skill uninstalled: $skillId")
            cachedIndex = null
        }
    }

    fun enableSkill(skillId: String): Result<Unit> {
        val skillDir = skillsDir.resolve(skillId)
        if (!skillDir.exists()) {
            return Result.failure(IllegalArgumentException("Skill not found: $skillId"))
        }

        val marker = skillDir.resolve(".disabled")
        if (marker.exists()) {
            marker.delete()
        }
        return Result.success(Unit)
    }

    fun disableSkill(skillId: String): Result<Unit> {
        val skillDir = skillsDir.resolve(skillId)
        if (!skillDir.exists()) {
            return Result.failure(IllegalArgumentException("Skill not found: $skillId"))
        }

        val marker = skillDir.resolve(".disabled")
        marker.createNewFile()
        return Result.success(Unit)
    }

    fun listSkills(): List<SkillInfo> {
        return skillsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.mapNotNull { dir ->
            val disabled = dir.resolve(".disabled").exists()
            val displayName = BuiltinSkillNames.getDisplayName(dir.name)
            runCatching {
                parseSkillInfo(dir).copy(isEnabled = !disabled, displayName = displayName)
            }.getOrNull() ?: run {
                SkillInfo(
                    id = dir.name,
                    name = dir.name,
                    description = "(broken)",
                    version = "0.0.0",
                    isEnabled = false,
                    installedAt = dir.lastModified(),
                    scripts = emptyList(),
                    assets = emptyList(),
                    isBuiltin = false,
                    displayName = displayName
                )
            }
        } ?: emptyList()
    }

    fun listSkillsFromIndex(): List<SkillInfo> {
        val index = loadIndex()
        return index.skills.mapNotNull { entry ->
            val skillDir = getSkillDir(entry.key) ?: return@mapNotNull null
            val disabled = skillDir.resolve(".disabled").exists()
            val displayName = BuiltinSkillNames.getDisplayName(entry.key)
            runCatching {
                parseSkillInfo(skillDir).copy(isEnabled = !disabled, displayName = displayName)
            }.getOrNull()
        }
    }

    fun getSkill(skillId: String): SkillInfo? {
        val skillDir = skillsDir.resolve(skillId)
        if (!skillDir.exists()) return null
        val disabled = skillDir.resolve(".disabled").exists()
        val displayName = BuiltinSkillNames.getDisplayName(skillId)
        return runCatching {
            parseSkillInfo(skillDir).copy(isEnabled = !disabled, displayName = displayName)
        }.getOrNull() ?: SkillInfo(
            id = skillId,
            name = skillId,
            description = "(broken)",
            version = "0.0.0",
            isEnabled = false,
            installedAt = skillDir.lastModified(),
            scripts = emptyList(),
            assets = emptyList(),
            isBuiltin = false
        )
    }

    /** 返回技能目录（用于复制到智能体工作区） */
    fun getSkillDir(skillId: String): File? {
        val skillDir = skillsDir.resolve(skillId)
        return if (skillDir.exists()) skillDir else null
    }

    fun isSkillEnabled(skillId: String): Boolean {
        val skillDir = skillsDir.resolve(skillId)
        if (!skillDir.exists()) return false
        return !skillDir.resolve(".disabled").exists()
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        if (enabled) enableSkill(skillId) else disableSkill(skillId)
    }

    internal fun parseSkillInfo(skillDir: File): SkillInfo {
        val skillMd = skillDir.resolve("SKILL.md")
        if (!skillMd.exists()) {
            throw IllegalStateException("SKILL.md not found in ${skillDir.name}")
        }

        val content = skillMd.readText()
        val name = extractFrontmatterField(content, "name") ?: skillDir.name
        val description = extractFrontmatterField(content, "description") ?: "No description"
        val version = extractFrontmatterField(content, "version") ?: "1.0.0"
        val isBuiltin = extractFrontmatterField(content, "builtin")?.lowercase() == "true"

        val scriptsDir = skillDir.resolve("scripts")
        val scripts = if (scriptsDir.exists()) {
            scriptsDir.listFiles()?.map { it.name } ?: emptyList()
        } else emptyList()

        val assetsDir = skillDir.resolve("assets")
        val assets = if (assetsDir.exists()) {
            assetsDir.listFiles()?.map { it.name } ?: emptyList()
        } else emptyList()

        return SkillInfo(
            id = skillDir.name,
            name = name,
            description = description,
            version = version,
            isEnabled = true,
            installedAt = skillDir.lastModified(),
            scripts = scripts,
            assets = assets,
            isBuiltin = isBuiltin
        )
    }

    private fun extractFrontmatterField(content: String, field: String): String? {
        val pattern = frontmatterFieldPatterns.getOrPut(field) {
            Regex("""${Regex.escape(field)}:\s*(.+)""")
        }
        return pattern.find(content)?.groupValues?.get(1)?.trim()
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

data class SkillScanResult(val issues: List<SecurityIssue>) {
    val hasHighSeverityIssues: Boolean get() = issues.any { it.severity == "HIGH" || it.severity == "CRITICAL" }

    fun summary(): String {
        return issues.take(5).joinToString("\n") { "- [${it.severity}] ${it.description}" } +
            if (issues.size > 5) "\n... 还有 ${issues.size - 5} 项" else ""
    }
}


