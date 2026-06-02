package com.lin.hippyagent.core.skill.store.provider

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.skill.store.SkillSource
import com.lin.hippyagent.core.skill.store.StoreSkillItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * SkillHub 技能源 (https://skillhub.cn)
 *
 * - 发现：通过 OkHttp + jsoup 抓取 https://skillhub.cn/skills 列表页，解析每个技能卡片
 * - 安装：技能"源自 ClawHub"或"源自 SkillHub"均通过 clawhub CLI 安装（重定向到 ClawHubProvider）
 * - 设计文档：docs/design.md > 系统 > 工具系统 > 技能商店
 */
class SkillHubProvider(
    private val linuxManager: LinuxManager,
    private val clawHubProvider: ClawHubProvider
) : MarketProvider {

    override val key = "skillhub"
    override val label = "SkillHub"
    override val source = SkillSource.SKILLHUB

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun available(): AvailabilityResult = AvailabilityResult(true, null)

    override suspend fun search(query: String, page: Int, pageSize: Int): Result<SearchResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = buildString {
                    append("https://skillhub.cn/skills")
                    val params = mutableListOf<String>()
                    if (query.isNotBlank()) params.add("q=${java.net.URLEncoder.encode(query, "UTF-8")}")
                    if (page > 1) params.add("page=$page")
                    if (params.isNotEmpty()) {
                        append("?")
                        append(params.joinToString("&"))
                    }
                }
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build()

                val response = httpClient.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        Timber.w("SkillHub fetch failed: http=${resp.code}")
                        return@runCatching SearchResult(emptyList(), hasMore = false)
                    }
                    val html = resp.body?.string().orEmpty()
                    val items = parseHtml(html, query)
                    val hasMore = items.size >= pageSize
                    SearchResult(items = items, hasMore = hasMore)
                }
            }.onFailure { e ->
                Timber.w(e, "SkillHub search failed")
            }
        }

    override suspend fun install(identifier: String): Result<String> {
        return clawHubProvider.install(identifier)
    }

    override suspend fun getDetail(identifier: String): Result<StoreSkillItem?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://skillhub.cn/skills/$identifier"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val html = resp.body?.string().orEmpty()
                    if (html.isBlank()) return@runCatching null
                    val doc = Jsoup.parse(html)
                    val name = doc.selectFirst("h1")?.text()?.trim()
                        ?: identifier.replace("-", " ").replaceFirstChar { it.titlecase() }
                    val description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
                        ?: doc.selectFirst("article p, .skill-content p, main p")?.text()?.trim()
                        ?: ""
                    val version = extractVersion(doc)
                    val (installCount, rating) = parseMetrics(extractMetricsText(doc))
                    StoreSkillItem(
                        identifier = identifier,
                        name = name,
                        description = description,
                        author = extractAuthor(doc),
                        source = SkillSource.SKILLHUB,
                        category = "",
                        installCount = installCount,
                        rating = rating,
                        version = version,
                        detailUrl = url,
                        installCommand = "npx -y clawhub install $identifier"
                    )
                }
            }.onFailure { e ->
                Timber.w(e, "SkillHub getDetail failed: $identifier")
            }
        }

    internal fun parseHtml(html: String, query: String): List<StoreSkillItem> {
        if (html.isBlank()) return emptyList()
        return try {
            val doc = Jsoup.parse(html)
            val anchors = doc.select("a[href^=/skills/]")
            val items = mutableListOf<StoreSkillItem>()
            val seen = HashSet<String>()
            for (anchor in anchors) {
                val href = anchor.attr("href")
                val slug = href.removePrefix("/skills/").substringBefore('/').takeIf { it.isNotBlank() }
                    ?: continue
                if (!seen.add(slug)) continue

                val cardRoot = nearestCard(anchor) ?: anchor
                val name = cardRoot.selectFirst("h3, h4, .skill-name, strong")?.text()?.trim()
                    ?: slug.replace("-", " ").replaceFirstChar { it.titlecase() }
                val description = cardRoot.selectFirst("p, .skill-desc, .description")?.text()?.trim().orEmpty()
                val (installCount, rating) = parseMetrics(cardRoot.text())
                val tagLinks = cardRoot.select("a[href*=/skills/category], .tag, .badge, .category")
                val category = tagLinks.firstOrNull()?.text()?.trim().orEmpty()
                val author = extractAuthor(cardRoot)

                items.add(
                    StoreSkillItem(
                        identifier = slug,
                        name = name,
                        description = description,
                        author = author,
                        source = SkillSource.SKILLHUB,
                        category = category,
                        installCount = installCount,
                        rating = rating,
                        detailUrl = "https://skillhub.cn$href",
                        installCommand = "npx -y clawhub install $slug"
                    )
                )
            }
            items
        } catch (e: Exception) {
            Timber.w(e, "SkillHub parseHtml failed")
            emptyList()
        }
    }

    private fun nearestCard(anchor: Element): Element? {
        var current: Element? = anchor.parent()
        repeat(5) {
            val node = current ?: return null
            if (node.hasClass("skill-card") ||
                node.hasClass("card") ||
                node.tagName() == "article" ||
                node.tagName() == "li"
            ) return node
            current = node.parent()
        }
        return current
    }

    private fun extractMetadataByRegex(text: String, regex: Regex): Float? =
        regex.find(text)?.groupValues?.lastOrNull()?.replace(",", "")?.toFloatOrNull()

    private fun parseMetrics(text: String): Pair<Long, Float> {
        if (text.isBlank()) return 0L to -1f
        val downloads = DOWNLOAD_REGEX.find(text)?.groupValues?.get(1)?.let { parseCount(it) } ?: 0L
        val rating = RATING_REGEX.find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
        return downloads to rating
    }

    private fun extractVersion(doc: Element): String {
        val meta = doc.selectFirst(".skill-meta, .skill-info, .metadata, aside") ?: return ""
        return VERSION_REGEX.find(meta.text())?.groupValues?.get(1).orEmpty()
    }

    private fun extractMetricsText(doc: Element): String {
        val meta = doc.selectFirst(".skill-meta, .skill-info, .metadata, aside, .skill-stats")
        return meta?.text() ?: doc.text()
    }

    private fun parseCount(raw: String): Long {
        val cleaned = raw.replace(",", "").trim()
        if (cleaned.isEmpty()) return 0L
        val unit = cleaned.last().lowercaseChar()
        val numStr = if (unit in "kmw") cleaned.dropLast(1) else cleaned
        val num = numStr.toDoubleOrNull() ?: return 0L
        return when (unit) {
            'k' -> (num * 1_000).toLong()
            'm' -> (num * 1_000_000).toLong()
            'w' -> (num * 10_000).toLong()
            else -> num.toLong()
        }
    }

    private fun extractAuthor(card: Element): String {
        val text = card.text()
        val match = AUTHOR_REGEX.find(text)
        return match?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun shellEscape(s: String): String {
        if (s.isEmpty()) return "''"
        return "'" + s.replace("'", "'\\''") + "'"
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 HippyAgent/1.0"
        private val DOWNLOAD_REGEX = Regex("([0-9][0-9,]*(?:\\.[0-9]+)?[KMkmwW]?)\\s*下载")
        private val RATING_REGEX = Regex("(?<![0-9.])([0-9](?:\\.[0-9]+)?)\\s*(?:AI 评分|评分)")
        private val AUTHOR_REGEX = Regex("作者是\\s*(\\S+)|作者[：:]\\s*(\\S+)")
        private val VERSION_REGEX = Regex("V\\s*([0-9]+\\.[0-9]+\\.[0-9]+)")
    }
}
