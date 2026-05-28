package com.lin.hippyagent.core.agent.collaboration

class DescriptionPhraseMatcher : Scorer {

    override val name: String = "description_phrase"

    companion object {
        private val STOP_WORDS: Set<String> = setOf(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
            "你", "会", "着", "没有", "看", "好", "自己", "这", "他", "她",
            "它", "们", "吧", "吗", "啊", "呢", "嗯", "哦"
        )
        private val SPLIT_PATTERN: Regex = Regex("[\\s,，。！？、；：]+")
        private val PHRASE_SPLIT_PATTERN: Regex = Regex("[，,。；;]")
    }

    override fun score(message: String, agentId: String, description: String): Int {
        if (description.isBlank()) return 0
        val msgWords = extractKeywords(message).toSet()
        val descWords = extractKeywords(description).toSet()
        val keywordOverlap = descWords.intersect(msgWords)
        var score = keywordOverlap.size * 2
        val phrases = PHRASE_SPLIT_PATTERN.split(description)
            .map { it.trim() }
            .filter { it.length >= 4 }
        for (phrase in phrases) {
            if (message.contains(phrase)) {
                score += 3
            }
        }
        return score.coerceIn(0, 7)
    }

    private fun extractKeywords(text: String): List<String> {
        return SPLIT_PATTERN.split(text)
            .filter { it.length >= 2 && it !in STOP_WORDS }
    }
}
