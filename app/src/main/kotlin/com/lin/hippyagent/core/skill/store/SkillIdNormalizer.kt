package com.lin.hippyagent.core.skill.store

object SkillIdNormalizer {
    private val suffixRegex = Regex("""(?:^.*/|^)([^/@]+)(?:@.*)?$""")
    private val cleanupRegex = Regex("[^a-z0-9_-]")

    fun normalize(identifier: String): String {
        val base = suffixRegex.find(identifier)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: identifier
        return cleanupRegex.replace(base.lowercase(), "_")
    }

    fun normalizeAll(ids: Iterable<String>): Set<String> = ids.map { normalize(it) }.toSet()
}
