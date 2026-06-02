package com.lin.hippyagent.core.skill.store.provider

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.skill.store.SkillSource
import com.lin.hippyagent.core.skill.store.StoreSkillItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class SkillsShProvider(
    private val linuxManager: LinuxManager
) : MarketProvider {

    override val key = "skills_sh"
    override val label = "Skills.sh"
    override val source = SkillSource.SKILLS_SH

    override fun available(): AvailabilityResult = AvailabilityResult(true, null)

    override suspend fun search(query: String, page: Int, pageSize: Int): Result<SearchResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx -y skills find ${shellEscape(query)}"
                Timber.tag("SkillsSh").i("search start: query='%s'", query)
                Timber.tag("SkillsSh").d("cmd: %s", cmd)
                val (code, output) = linuxManager.exec(cmd, timeout = 60_000)
                Timber.tag("SkillsSh").i("search done: exitCode=%d outputLen=%d", code, output.length)
                when {
                    code == -1 -> {
                        Timber.tag("SkillsSh").w("search aborted: linux not ready")
                        throw LinuxNotReadyException()
                    }
                    code == -2 -> {
                        Timber.tag("SkillsSh").w("search timed out (>60s)")
                        return@runCatching SearchResult(emptyList(), false)
                    }
                    code == -3 -> {
                        Timber.tag("SkillsSh").w("search exec failed: %s", output)
                        return@runCatching SearchResult(emptyList(), false)
                    }
                    code != 0 -> {
                        Timber.tag("SkillsSh").w("search non-zero exit: code=%d output=%s", code, output.take(500))
                        return@runCatching SearchResult(emptyList(), false)
                    }
                }
                val items = parseText(output)
                Timber.tag("SkillsSh").i("search parsed: %d items. head=%s", items.size, output.take(300))
                SearchResult(items = items, hasMore = false)
            }.onFailure { e ->
                if (e !is LinuxNotReadyException) {
                    Timber.tag("SkillsSh").e(e, "search failed")
                }
            }
        }

    override suspend fun install(identifier: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ownerRepo = identifier.substringBefore("@")
                val skillName = identifier.substringAfter("@")
                val cmd = "npx skills add ${shellEscape(ownerRepo)}@${shellEscape(skillName)} -g -y"
                Timber.tag("SkillsSh").i("install start: identifier='%s'", identifier)
                val (code, output) = linuxManager.exec(cmd, timeout = 180_000)
                Timber.tag("SkillsSh").i("install done: exitCode=%d outputLen=%d", code, output.length)
                if (code != 0) {
                    Timber.tag("SkillsSh").w("install failed: code=%d output=%s", code, output.take(500))
                    throw RuntimeException("安装失败: ${output.take(200)}")
                }
                output
            }.onFailure { e ->
                Timber.tag("SkillsSh").e(e, "install exception")
            }
        }

    internal fun parseText(text: String): List<StoreSkillItem> {
        val results = mutableListOf<StoreSkillItem>()
        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trim()
            val match = SKILLS_SH_REGEX.find(trimmed)
            if (match != null) {
                val identifier = match.groupValues[1]
                val installs = match.groupValues[2].toLongOrNull() ?: 0
                val parts = identifier.split("@")
                val ownerRepo = parts.getOrElse(0) { "" }
                val skillName = parts.getOrElse(1) { "" }
                val owner = ownerRepo.substringBefore("/")
                results.add(
                    StoreSkillItem(
                        identifier = identifier,
                        name = skillName,
                        description = "",
                        author = owner,
                        source = SkillSource.SKILLS_SH,
                        category = "",
                        installCount = installs,
                        installCommand = "npx -y skills add $identifier -g -y"
                    )
                )
            }
        }
        return results
    }

    override suspend fun getDetail(identifier: String): Result<StoreSkillItem?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx -y skills info ${shellEscape(identifier)}"
                val (code, output) = linuxManager.exec(cmd, timeout = 30_000)
                if (code != 0) return@runCatching null
                parseDetailText(identifier, output)
            }
        }

    internal fun parseDetailText(identifier: String, text: String): StoreSkillItem? {
        val lines = text.lines()
        val parts = identifier.split("@")
        val ownerRepo = parts.getOrElse(0) { "" }
        val skillName = parts.getOrElse(1) { "" }
        val owner = ownerRepo.substringBefore("/")
        var author = owner
        var description = ""
        val descLines = mutableListOf<String>()
        var inDesc = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Description:") || trimmed.startsWith("description:")) {
                inDesc = true
                val rest = trimmed.substringAfter(":").trim()
                if (rest.isNotBlank()) descLines.add(rest)
                continue
            }
            if (inDesc) {
                if (trimmed.isEmpty() || (trimmed.contains(":") && !trimmed.startsWith(" "))) {
                    inDesc = false
                } else {
                    descLines.add(trimmed)
                }
            }
            if (trimmed.startsWith("Author:") || trimmed.startsWith("author:")) {
                author = trimmed.substringAfter(":").trim().ifBlank { owner }
            }
        }
        description = descLines.joinToString(" ").trim()
        if (description.isBlank()) return null
        return StoreSkillItem(
            identifier = identifier,
            name = skillName,
            description = description,
            author = author,
            source = SkillSource.SKILLS_SH,
            category = "",
            installCommand = "npx -y skills add $identifier -g -y"
        )
    }

    companion object {
        private val SKILLS_SH_REGEX = Regex("""^(\S+/\S+@\S+)\s+(\d+)\s+installs?""")
    }
}
