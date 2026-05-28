package com.lin.hippyagent.core.skill.store

import com.lin.hippyagent.core.linux.LinuxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * CLI 桥接服务 — 通过 LinuxManager 执行 Node.js CLI 命令获取技能商店数据
 */
class SkillStoreService(
    private val linuxManager: LinuxManager
) {
    companion object {
        private val skillsShRegex = Regex("""^(\S+/\S+@\S+)\s+(\d+)\s+installs?""")
        private val clawHubRegex = Regex("""^(\S+)\s+(.+?)\s+\(([0-9.]+)\)""")
    }

    /** 暴露 LinuxManager 给 ViewModel/UI 层自动安装 Node 时使用 */
    fun getLinuxManager(): LinuxManager = linuxManager
    suspend fun searchLobeHub(query: String, page: Int = 1, pageSize: Int = 20): Result<List<StoreSkillItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx -y @lobehub/market-cli skills search --q ${shellEscape(query)} --output json --page $page --page-size $pageSize"
                val (code, output) = linuxManager.exec(cmd, timeout = 30_000)
                if (code == -1) throw LinuxNotReadyException()
                if (code != 0) {
                    Timber.w("LobeHub CLI failed: code=$code, output=${output.take(200)}")
                    return@runCatching emptyList()
                }
                parseLobeHubJson(output)
            }
        }

    suspend fun searchSkillsSh(query: String): Result<List<StoreSkillItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx -y skills find \"$query\""
                val (code, output) = linuxManager.exec(cmd, timeout = 60_000)
                if (code == -1) throw LinuxNotReadyException()
                if (code != 0) {
                    Timber.w("Skills.sh CLI failed: code=$code, output=${output.take(200)}")
                    return@runCatching emptyList()
                }
                parseSkillsShText(output)
            }
        }

    suspend fun searchClawHub(query: String): Result<List<StoreSkillItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx -y clawhub search ${shellEscape(query)} --limit 20"
                val (code, output) = linuxManager.exec(cmd, timeout = 30_000)
                if (code == -1) throw LinuxNotReadyException()
                if (code != 0) {
                    Timber.w("ClawHub CLI failed: code=$code, output=${output.take(200)}")
                    return@runCatching emptyList()
                }
                parseClawHubText(output)
            }
        }

    suspend fun installFromLobeHub(identifier: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx -y @lobehub/market-cli skills install $identifier"
                val (code, output) = linuxManager.exec(cmd, timeout = 180_000)
                if (code != 0) throw RuntimeException("安装失败: ${output.take(200)}")
                output
            }
        }

    suspend fun installFromSkillsSh(ownerRepo: String, skillName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx skills add ${shellEscape(ownerRepo)}@${shellEscape(skillName)} -g -y"
                val (code, output) = linuxManager.exec(cmd, timeout = 180_000)
                if (code != 0) throw RuntimeException("安装失败: ${output.take(200)}")
                output
            }
        }

    suspend fun installFromClawHub(slug: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = "npx -y clawhub install ${shellEscape(slug)}"
                val (code, output) = linuxManager.exec(cmd, timeout = 180_000)
                if (code != 0) throw RuntimeException("安装失败: ${output.take(200)}")
                output
            }
        }

    private fun shellEscape(s: String): String {
        return "'${s.replace("'", "'\\''")}'"
    }

    private fun parseLobeHubJson(json: String): List<StoreSkillItem> {
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("items") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                StoreSkillItem(
                    identifier = obj.optString("identifier", ""),
                    name = obj.optString("name", ""),
                    description = obj.optString("description", ""),
                    author = obj.optString("author", obj.optJSONObject("author")?.optString("name", "") ?: ""),
                    source = SkillSource.LOBEHUB,
                    category = obj.optString("category", ""),
                    installCount = obj.optLong("installCount", 0),
                    starsCount = obj.optJSONObject("github")?.optLong("stars", 0) ?: 0,
                    rating = obj.optDouble("ratingAverage", -1.0).toFloat(),
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

    /**
     * 解析 Skills.sh 纯文本输出
     * 格式: owner/repo@skill-name N installs\n└ URL\n\n
     */
    private fun parseSkillsShText(text: String): List<StoreSkillItem> {
        val results = mutableListOf<StoreSkillItem>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            // 匹配: owner/repo@skill-name N installs
            val match = skillsShRegex.find(line)
            if (match != null) {
                val identifier = match.groupValues[1]
                val installs = match.groupValues[2].toLongOrNull() ?: 0
                val parts = identifier.split("@")
                val ownerRepo = parts.getOrElse(0) { "" }
                val skillName = parts.getOrElse(1) { "" }
                val owner = ownerRepo.substringBefore("/")
                results.add(StoreSkillItem(
                    identifier = identifier,
                    name = skillName,
                    description = "",
                    author = owner,
                    source = SkillSource.SKILLS_SH,
                    category = "",
                    installCount = installs,
                    installCommand = "npx -y skills add $identifier -g -y"
                ))
            }
            i++
        }
        return results
    }

    /**
     * 解析 ClawHub 纯文本输出
     * 格式: slug  名称  (score)\n
     */
    private fun parseClawHubText(text: String): List<StoreSkillItem> {
        val results = mutableListOf<StoreSkillItem>()
        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // 匹配: slug  名称  (0.903)
            val match = clawHubRegex.find(trimmed)
            if (match != null) {
                val slug = match.groupValues[1]
                val name = match.groupValues[2].trim()
                val score = match.groupValues[3].toDoubleOrNull() ?: 0.0
                results.add(StoreSkillItem(
                    identifier = slug,
                    name = name,
                    description = "",
                    author = "",
                    source = SkillSource.CLAWHUB,
                    category = "",
                    installCount = (score * 100).toLong(),
                    installCommand = "npx -y clawhub install $slug"
                ))
            }
        }
        return results
    }
}

class LinuxNotReadyException : Exception("Linux 环境未初始化")

