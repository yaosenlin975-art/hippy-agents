package com.lin.hippyagent.core.skill.store.provider

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.skill.store.SkillSource
import com.lin.hippyagent.core.skill.store.StoreSkillItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

class LobeHubProvider(
    private val linuxManager: LinuxManager
) : MarketProvider {

    override val key = "lobehub"
    override val label = "LobeHub"
    override val source = SkillSource.LOBEHUB

    override fun available(): AvailabilityResult = AvailabilityResult(true, null)

    override suspend fun search(query: String, page: Int, pageSize: Int): Result<SearchResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx -y @lobehub/market-cli skills search --q ${shellEscape(query)} --output json --page $page --page-size $pageSize"
                val (code, output) = linuxManager.exec(cmd, timeout = 30_000)
                if (code == -1) throw LinuxNotReadyException()
                if (code != 0) {
                    Timber.w("LobeHub CLI failed: code=$code, output=${output.take(200)}")
                    return@runCatching SearchResult(emptyList(), false)
                }
                val items = parseJson(output)
                SearchResult(items = items, hasMore = items.size >= pageSize)
            }
        }

    override suspend fun install(identifier: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx -y @lobehub/market-cli skills install ${shellEscape(identifier)}"
                val (code, output) = linuxManager.exec(cmd, timeout = 180_000)
                if (code != 0) throw RuntimeException("安装失败: ${output.take(200)}")
                output
            }
        }

    internal fun parseJson(json: String): List<StoreSkillItem> {
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("items") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val ratingAvg = obj.optDouble("ratingAverage", -1.0)
                StoreSkillItem(
                    identifier = obj.optString("identifier", ""),
                    name = obj.optString("name", ""),
                    description = obj.optString("description", ""),
                    author = obj.optString("author", obj.optJSONObject("author")?.optString("name", "") ?: ""),
                    source = SkillSource.LOBEHUB,
                    category = obj.optString("category", ""),
                    installCount = obj.optLong("installCount", 0),
                    starsCount = obj.optJSONObject("github")?.optLong("stars", 0) ?: 0,
                    rating = if (ratingAvg.isNaN()) -1f else ratingAvg.toFloat(),
                    version = obj.optString("version", ""),
                    updatedAt = obj.optLong("updatedAt", 0),
                    tags = obj.optJSONArray("tags")?.let { tags ->
                        (0 until tags.length()).map { tags.getString(it) }
                    } ?: emptyList(),
                    installCommand = "npx -y @lobehub/market-cli skills install ${obj.optString("identifier")}",
                    detailUrl = obj.optString("homepage", ""),
                    isValidated = obj.optBoolean("isValidated", false)
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse LobeHub JSON")
            emptyList()
        }
    }
}
