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
                Timber.tag("LobeHub").i("search start: query='%s' page=%d pageSize=%d", query, page, pageSize)
                Timber.tag("LobeHub").d("cmd: %s", cmd)
                val (code, output) = linuxManager.exec(cmd, timeout = 30_000)
                Timber.tag("LobeHub").i("search done: exitCode=%d outputLen=%d", code, output.length)
                when {
                    code == -1 -> {
                        Timber.tag("LobeHub").w("search aborted: linux not ready")
                        throw LinuxNotReadyException()
                    }
                    code == -2 -> {
                        Timber.tag("LobeHub").w("search timed out (>30s)")
                        return@runCatching SearchResult(emptyList(), false)
                    }
                    code == -3 -> {
                        Timber.tag("LobeHub").w("search exec failed: %s", output)
                        return@runCatching SearchResult(emptyList(), false)
                    }
                    code != 0 -> {
                        Timber.tag("LobeHub").w("search non-zero exit: code=%d output=%s", code, output.take(500))
                        return@runCatching SearchResult(emptyList(), false)
                    }
                }
                val items = parseJson(output)
                Timber.tag("LobeHub").i("search parsed: %d items", items.size)
                SearchResult(items = items, hasMore = items.size >= pageSize)
            }.onFailure { e ->
                if (e !is LinuxNotReadyException) {
                    Timber.tag("LobeHub").e(e, "search failed")
                }
            }
        }

    override suspend fun install(identifier: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx -y @lobehub/market-cli skills install ${shellEscape(identifier)}"
                Timber.tag("LobeHub").i("install start: identifier='%s'", identifier)
                val (code, output) = linuxManager.exec(cmd, timeout = 180_000)
                Timber.tag("LobeHub").i("install done: exitCode=%d outputLen=%d", code, output.length)
                if (code != 0) {
                    Timber.tag("LobeHub").w("install failed: code=%d output=%s", code, output.take(500))
                    throw RuntimeException("安装失败: ${output.take(200)}")
                }
                output
            }.onFailure { e ->
                Timber.tag("LobeHub").e(e, "install exception")
            }
        }

    internal fun parseJson(json: String): List<StoreSkillItem> {
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("items") ?: run {
                Timber.tag("LobeHub").w("parseJson: missing 'items' key. head=%s", json.take(300))
                return emptyList()
            }
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val ratingAvg = obj.optDouble("ratingAverage", -1.0)
                StoreSkillItem(
                    identifier = obj.optString("identifier", ""),
                    name = obj.optString("name", ""),
                    description = obj.optString("description", ""),
                    author = obj.optJSONObject("author")?.optString("name", "") ?: obj.optString("author", ""),
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
            Timber.tag("LobeHub").e(e, "parseJson failed. raw=%s", json.take(500))
            emptyList()
        }
    }
}
