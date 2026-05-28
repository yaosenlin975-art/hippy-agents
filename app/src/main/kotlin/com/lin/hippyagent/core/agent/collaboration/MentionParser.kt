package com.lin.hippyagent.core.agent.collaboration

object MentionParser {

    private val MENTION_PATTERN = Regex("@([\\w\\u4e00-\\u9fff-]+)")
    private val MENTION_CONTAINS_PATTERN = Regex("@[\\w\\u4e00-\\u9fff-]+")
    private val MULTI_SPACE_PATTERN = Regex("\\s{2,}")

    fun parse(message: String): List<String> {
        val mentions = mutableListOf<String>()
        val matches = MENTION_PATTERN.findAll(message)
        for (match in matches) {
            val mention = match.groupValues[1].trim()
            if (mention.isNotBlank()) {
                mentions.add(mention)
            }
        }
        return mentions.distinct()
    }

    fun hasMentions(message: String): Boolean {
        return message.contains(MENTION_CONTAINS_PATTERN)
    }

    fun removeMentions(message: String): String {
        return message.replace(MENTION_CONTAINS_PATTERN, "")
            .trim()
            .replace(MULTI_SPACE_PATTERN, " ")
    }

    fun formatMentions(agentIds: List<String>): String {
        return agentIds.joinToString(" ") { "@$it" }
    }
}
