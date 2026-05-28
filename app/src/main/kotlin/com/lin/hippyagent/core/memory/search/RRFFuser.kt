package com.lin.hippyagent.core.memory.search

/**
 * RRF 融合排序 — Reciprocal Rank Fusion
 *
 * 将多个检索结果列表的排序位置合并为一个排序列表。
 * RRF 算法: score = Σ 1/(k + rank)，其中 rank 是 1-based 排序位置。
 *
 * 参考: gbrain search/fusion/RRFFuser.ts
 */
class RRFFuser {

    /**
     * 融合多个排序列表
     * @param lists 多个排序列表，每个包含 label + 排序后的结果
     * @param k RRF 常数，默认 60
     * @return 融合后的排序结果
     */
    fun fuse(
        lists: List<RankedList>,
        k: Int = 60
    ): List<ScoredItem> {
        val scores = mutableMapOf<String, MutableList<Float>>()

        for ((sourceLabel, items) in lists) {
            for ((index, item) in items.withIndex()) {
                val rank = index + 1 // 1-based rank
                val rrfScore = 1f / (k + rank)
                val list = scores.getOrPut(item.id) { mutableListOf() }
                list.add(rrfScore)
            }
        }

        return scores.map { (id, scoreList) ->
            ScoredItem(id, scoreList.sum())
        }.sortedByDescending { it.score }
    }

    /**
     * 融合两个列表（便捷方法）
     */
    fun fuseTwo(
        listA: List<ScoredItem>,
        listB: List<ScoredItem>,
        k: Int = 60
    ): List<ScoredItem> {
        return fuse(listOf(
            RankedList("a", listA),
            RankedList("b", listB)
        ), k)
    }
}

/**
 * 带标签的排序列表
 */
data class RankedList(
    val label: String,
    val items: List<ScoredItem>
)

/**
 * 带分数的结果项
 */
data class ScoredItem(
    val id: String,
    val score: Float
)
