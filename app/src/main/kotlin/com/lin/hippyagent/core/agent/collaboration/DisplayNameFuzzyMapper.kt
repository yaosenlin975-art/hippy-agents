package com.lin.hippyagent.core.agent.collaboration

class DisplayNameFuzzyMapper(
    private val agentDisplayNames: suspend () -> Map<String, String>
) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var cacheTimestamp = 0L
    private val cacheTtlMs = 30_000L

    private suspend fun getDisplayNames(): Map<String, String> {
        val now = System.currentTimeMillis()
        if (cache.isEmpty() || now - cacheTimestamp > cacheTtlMs) {
            val names = agentDisplayNames()
            cache.clear()
            cache.putAll(names)
            cacheTimestamp = now
        }
        return cache
    }

    suspend fun resolve(mentionText: String): String? {
        val names = getDisplayNames()

        names.keys.find { it == mentionText }?.let { return it }

        names.entries.find { it.value == mentionText }?.let { return it.key }

        names.entries.find { it.value.startsWith(mentionText, ignoreCase = true) }?.let { return it.key }

        names.entries.find { it.value.contains(mentionText, ignoreCase = true) }?.let { return it.key }

        return null
    }

    suspend fun formatForDisplay(agentIds: List<String>): List<String> {
        val names = getDisplayNames()
        return agentIds.map { names[it] ?: it }
    }
}
