package com.lin.hippyagent.core.skill.store.provider

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.skill.store.SkillSource
import com.lin.hippyagent.core.skill.store.StoreSkillItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class ClawHubProvider(
    private val linuxManager: LinuxManager
) : MarketProvider {

    override val key = "clawhub"
    override val label = "ClawHub"
    override val source = SkillSource.CLAWHUB

    override fun available(): AvailabilityResult = AvailabilityResult(true, null)

    override suspend fun search(query: String, page: Int, pageSize: Int): Result<SearchResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx -y clawhub search ${shellEscape(query)} --limit $pageSize"
                val (code, output) = linuxManager.exec(cmd, timeout = 30_000)
                if (code == -1) throw LinuxNotReadyException()
                if (code != 0) {
                    Timber.w("ClawHub CLI failed: code=$code, output=${output.take(200)}")
                    return@runCatching SearchResult(emptyList(), false)
                }
                val items = parseText(output)
                SearchResult(items = items, hasMore = false)
            }
        }

    override suspend fun install(identifier: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx -y clawhub install ${shellEscape(identifier)}"
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
            if (trimmed.isEmpty()) continue
            val match = CLAWHUB_REGEX.find(trimmed)
            if (match != null) {
                val slug = match.groupValues[1]
                val name = match.groupValues[2].trim()
                val score = match.groupValues[3].toFloatOrNull() ?: 0f
                results.add(
                    StoreSkillItem(
                        identifier = slug,
                        name = name,
                        description = "",
                        author = "",
                        source = SkillSource.CLAWHUB,
                        category = "",
                        confidence = score,
                        installCommand = "npx -y clawhub install $slug"
                    )
                )
            }
        }
        return results
    }

    companion object {
        private val CLAWHUB_REGEX = Regex("""^(\S+)\s+(.+?)\s+\(([0-9.]+)\)""")
    }
}
