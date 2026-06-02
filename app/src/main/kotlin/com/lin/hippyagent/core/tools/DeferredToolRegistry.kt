package com.lin.hippyagent.core.tools

import com.lin.hippyagent.core.model.ModelToolDefinition
import timber.log.Timber

internal data class DeferredToolEntry(
    val name: String,
    val description: String,
    val definition: ModelToolDefinition
)

class DeferredToolRegistry {
    private val entries = mutableListOf<DeferredToolEntry>()
    private val regexCache = mutableMapOf<String, Regex>()

    fun register(definition: ModelToolDefinition) {
        if (entries.any { it.name == definition.name }) return
        entries.add(DeferredToolEntry(
            name = definition.name,
            description = definition.description,
            definition = definition
        ))
    }

    fun search(query: String): List<ModelToolDefinition> {
        if (entries.isEmpty()) return emptyList()

        if (query.startsWith("select:")) {
            val names = query.removePrefix("select:").split(",").map { it.trim() }.toSet()
            return entries.filter { it.name in names }.take(MAX_RESULTS).map { it.definition }
        }

        if (query.startsWith("+")) {
            val parts = query.removePrefix("+").split(Regex("\\s+"), 2)
            val required = parts.getOrNull(0)?.lowercase() ?: return emptyList()
            val candidates = entries.filter { required in it.name.lowercase() }
            if (parts.size > 1) {
                val secondaryRegex = regexCache.getOrPut("score:${parts[1]}") {
                    try {
                        Regex(parts[1], RegexOption.IGNORE_CASE)
                    } catch (_: Exception) {
                        Regex(Regex.escape(parts[1]), RegexOption.IGNORE_CASE)
                    }
                }
                val ranked = candidates.sortedByDescending { entry ->
                    secondaryRegex.findAll("${entry.name} ${entry.description}").count()
                }
                return ranked.take(MAX_RESULTS).map { it.definition }
            }
            return candidates.take(MAX_RESULTS).map { it.definition }
        }

        val regex = regexCache.getOrPut(query) {
            try {
                Regex(query, RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
            }
        }

        val scored = entries.mapNotNull { entry ->
            val searchable = "${entry.name} ${entry.description}"
            if (regex.containsMatchIn(searchable)) {
                val score = if (regex.containsMatchIn(entry.name)) 2 else 1
                score to entry
            } else null
        }.sortedByDescending { it.first }

        return scored.take(MAX_RESULTS).map { it.second.definition }
    }

    fun getDeferredNames(): Set<String> = entries.map { it.name }.toSet()

    fun clear() {
        entries.clear()
    }

    companion object {
        const val MAX_RESULTS = 5
    }
}
