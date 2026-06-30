package com.lin.hippyagent.core.deeplink

import java.util.concurrent.ConcurrentHashMap

/**
 * dumpsys 输出的 Intent 解析器。
 * 正则全部放 companion object 或顶层 private val，遵循 coding.md 规则。
 * 动态 Hist 正则用 ConcurrentHashMap + getOrPut 缓存。
 */
object DeeplinkIntentParser {

    fun parseBlock(dumpsysOutput: String, packageName: String, shortClass: String): CapturedIntentSpec? {
        val histBlock = extractHistBlock(dumpsysOutput, shortClass) ?: return null
        val intentBlock = extractIntentBlock(histBlock) ?: return null
        val action = FIELD_ACTION.find(intentBlock)?.groupValues?.getOrNull(1)?.trim()
        val data = FIELD_DATA.find(intentBlock)?.groupValues?.getOrNull(1)?.trim()
        val cmp = FIELD_CMP.find(intentBlock)?.groupValues?.getOrNull(1)?.trim()
        val flg = FIELD_FLG.find(intentBlock)?.groupValues?.getOrNull(1)?.trim()
        val categories = FIELD_CATEGORIES.find(intentBlock)?.groupValues?.getOrNull(1)
            ?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        val extras = extractExtras(intentBlock)
        return CapturedIntentSpec(action, data, cmp, flg, categories, extras)
    }

    private fun extractHistBlock(output: String, shortClass: String): String? {
        val histRegex = HIST_PATTERN.getOrPut(shortClass) {
            Regex("""Hist.*$shortClass""")
        }
        val match = histRegex.find(output) ?: return null
        val start = match.range.first
        val end = minOf(start + 2000, output.length)
        return output.substring(start, end)
    }

    private fun extractIntentBlock(histBlock: String): String? {
        val match = INTENT_OPEN.find(histBlock) ?: return null
        val start = match.range.first
        val end = minOf(start + 1500, histBlock.length)
        return histBlock.substring(start, end)
    }

    private fun extractExtras(intentBlock: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (match in EXTRA_PATTERN.findAll(intentBlock)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim().removeSurrounding("\"")
            result[key] = value
        }
        return result
    }

    private val HIST_PATTERN = ConcurrentHashMap<String, Regex>()
    private val INTENT_OPEN = Regex("""Intent\s*\{""")
    private val FIELD_ACTION = Regex("""act=([^\s}]+)""")
    private val FIELD_DATA = Regex("""dat=([^\s}]+)""")
    private val FIELD_CMP = Regex("""cmp=([^\s}]+)""")
    private val FIELD_FLG = Regex("""flg=([^\s}]+)""")
    private val FIELD_CATEGORIES = Regex("""cat=\[([^\]]*)]""")
    private val EXTRA_PATTERN = Regex("""(?:es|ei|el|eb|eu|ecn)\s+(\w+)=([^\s}\n]+)""")
}
