package com.lin.hippyagent.core.memory

import com.huaban.analysis.jieba.JiebaSegmenter
import com.huaban.analysis.jieba.SegToken

object ChineseTokenizer {
    private val segmenter by lazy { JiebaSegmenter() }

    fun segment(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens: List<SegToken> = segmenter.process(text, JiebaSegmenter.SegMode.SEARCH)
        return tokens.map { it.word }.filter { it.isNotBlank() }
    }

    fun segmentToString(text: String): String = segment(text).joinToString(" ")

    fun segmentForSearch(text: String): String = segment(text).joinToString(" OR ")
}
