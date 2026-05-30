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
                val (code, output) = linuxManager.exec(cmd, timeout = 60_000)
                if (code == -1) throw LinuxNotReadyException()
                if (code != 0) {
                    Timber.w("Skills.sh CLI failed: code=$code, output=${output.take(200)}")
                    return@runCatching SearchResult(emptyList(), false)
                }
                val items = parseText(output)
                SearchResult(items = items, hasMore = false)
            }
        }

    override suspend fun install(identifier: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ownerRepo = identifier.substringBefore("@")
                val skillName = identifier.substringAfter("@")
                val cmd = "npx skills add ${shellEscape(ownerRepo)}@${shellEscape(skillName)} -g -y"
                val (code, output) = linuxManager.exec(cmd, timeout = 180_000)
                if (code != 0) throw RuntimeException("安装失败: ${output.take(200)}")
                output
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

    companion object {
        private val SKILLS_SH_REGEX = Regex("""^(\S+/\S+@\S+)\s+(\d+)\s+installs?""")
    }
}
