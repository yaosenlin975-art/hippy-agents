package com.lin.hippyagent.core.agent.collaboration

class AgentCardParser {

    fun parseIdentity(soulContent: String?): String? {
        if (soulContent.isNullOrBlank()) return null
        val sections = soulContent.split(Regex("(?m)^##\\s+(我是谁|Who am I)\\s*$"), limit = 2)
        if (sections.size < 2) return null
        val body = sections[1]
        return body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("**") }
    }

    fun parseResponsibilities(agentsContent: String?): List<String> {
        return parseListSection(agentsContent, "做", "不做")
    }

    fun parseBoundaries(agentsContent: String?): List<String> {
        return parseListSection(agentsContent, "不做", null)
    }

    private fun parseListSection(content: String?, startMarker: String, endMarker: String?): List<String> {
        if (content.isNullOrBlank()) return emptyList()
        val afterStart = content.split("**$startMarker**", limit = 2).getOrNull(1) ?: return emptyList()
        val section = if (endMarker != null) {
            afterStart.split("**$endMarker**", limit = 2).first()
        } else {
            afterStart
        }
        return section.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { it.removePrefix("- ").removePrefix("* ").trim() }
            .filter { it.isNotBlank() }
            .toList()
    }
}
