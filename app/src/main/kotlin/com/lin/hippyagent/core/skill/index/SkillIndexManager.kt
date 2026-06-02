package com.lin.hippyagent.core.skill.index

import com.lin.hippyagent.core.skill.BuiltinSkillNames
import com.lin.hippyagent.core.skill.SkillIndex
import com.lin.hippyagent.core.skill.SkillIndexEntry
import com.lin.hippyagent.core.skill.SkillInfo
import com.lin.hippyagent.core.skill.SkillManifest
import com.lin.hippyagent.core.skill.SkillToolDef
import com.lin.hippyagent.core.skill.SkillTriggers
import com.lin.hippyagent.core.util.FileUtils
import timber.log.Timber
import java.io.File

class SkillIndexManager(
    private val skillsDir: File
) {
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

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        if (!skillsDir.exists()) {
            skillsDir.mkdirs()
        }
    }

    fun invalidate() {
        cachedIndex = null
    }

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

    private fun listSkillDirs(): List<File> =
        skillsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in EXCLUDED_DIRS } ?: emptyList()

    fun rebuildIndex(): SkillIndex {
        val entries = mutableMapOf<String, SkillIndexEntry>()
        listSkillDirs().forEach { dir ->
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
                    entries[dir.name] = brokenSkillIndexEntry(dir)
                }
            }
        }
        val index = SkillIndex(version = System.currentTimeMillis(), skills = entries)
        saveIndex(index)
        return index
    }

    fun getManifest(skillId: String): SkillManifest? {
        if (skillId in EXCLUDED_DIRS) return null
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

    fun getSkillDir(skillId: String): File? {
        if (skillId in EXCLUDED_DIRS) return null
        val skillDir = skillsDir.resolve(skillId)
        return if (skillDir.exists()) skillDir else null
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

    fun listSkills(): List<SkillInfo> {
        return listSkillDirs().mapNotNull { dir ->
            val disabled = dir.resolve(".disabled").exists()
            val displayName = BuiltinSkillNames.getDisplayName(dir.name)
            runCatching {
                parseSkillInfo(dir).copy(isEnabled = !disabled, displayName = displayName)
            }.getOrNull() ?: brokenSkillInfo(dir)
        }
    }

    fun listSkillsFromIndex(): List<SkillInfo> {
        val index = loadIndex()
        return index.skills.filter { it.key !in EXCLUDED_DIRS }.mapNotNull { entry ->
            val skillDir = getSkillDir(entry.key) ?: return@mapNotNull null
            val disabled = skillDir.resolve(".disabled").exists()
            val displayName = BuiltinSkillNames.getDisplayName(entry.key)
            runCatching {
                parseSkillInfo(skillDir).copy(isEnabled = !disabled, displayName = displayName)
            }.getOrNull()
        }
    }

    fun getSkill(skillId: String): SkillInfo? {
        if (skillId in EXCLUDED_DIRS) return null
        val skillDir = skillsDir.resolve(skillId)
        if (!skillDir.exists()) return null
        val displayName = BuiltinSkillNames.getDisplayName(skillId)
        return runCatching {
            val disabled = skillDir.resolve(".disabled").exists()
            parseSkillInfo(skillDir).copy(isEnabled = !disabled, displayName = displayName)
        }.getOrNull() ?: brokenSkillInfo(skillDir)
    }

    private fun brokenSkillInfo(dir: File): SkillInfo {
        val disabled = dir.resolve(".disabled").exists()
        val displayName = BuiltinSkillNames.getDisplayName(dir.name)
        return SkillInfo(
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

    private fun brokenSkillIndexEntry(dir: File): SkillIndexEntry {
        return SkillIndexEntry(
            id = dir.name,
            name = dir.name,
            description = "(broken)",
            version = "0.0.0",
            installedAt = dir.lastModified(),
            updatedAt = dir.lastModified(),
            broken = true
        )
    }

    private fun extractFrontmatterField(content: String, field: String): String? {
        val pattern = frontmatterFieldPatterns.getOrPut(field) {
            Regex("""${Regex.escape(field)}:\s*(.+)""")
        }
        return pattern.find(content)?.groupValues?.get(1)?.trim()
    }

    companion object {
        val EXCLUDED_DIRS = setOf("_config")
    }
}
