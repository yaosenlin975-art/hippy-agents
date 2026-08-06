package com.lin.hippyagent.core.memory.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RRFFuserTest {

    private val fuser = RRFFuser()

    @Test
    fun fuse_singleList_preservesOrderWithRankScore() {
        val list = RankedList("a", listOf(ScoredItem("x", 1f), ScoredItem("y", 2f)))
        val result = fuser.fuse(listOf(list), k = 60)
        assertEquals(listOf("x", "y"), result.map { it.id })
        assertEquals(1f / 61f, result[0].score, 1e-6f)
        assertEquals(1f / 62f, result[1].score, 1e-6f)
    }

    @Test
    fun fuse_twoLists_rankOneInBoth_wins() {
        val listA = RankedList("a", listOf(ScoredItem("x", 1f), ScoredItem("y", 2f)))
        val listB = RankedList("b", listOf(ScoredItem("x", 3f), ScoredItem("z", 1f)))
        val result = fuser.fuse(listOf(listA, listB), k = 60)
        // x: 1/61 + 1/61, y: 1/62, z: 1/62
        assertEquals("x", result[0].id)
        assertEquals(2f / 61f, result[0].score, 1e-6f)
        assertEquals(setOf("y", "z"), result.drop(1).map { it.id }.toSet())
    }

    @Test
    fun fuse_missingFromOneList_getsOnlyOneContribution() {
        val listA = RankedList("a", listOf(ScoredItem("x", 1f), ScoredItem("y", 1f)))
        val listB = RankedList("b", listOf(ScoredItem("y", 1f)))
        val result = fuser.fuse(listOf(listA, listB), k = 60)
        val y = result.first { it.id == "y" }
        val x = result.first { it.id == "x" }
        assertTrue(y.score > x.score)
    }

    @Test
    fun fuse_emptyLists_returnsEmpty() {
        assertTrue(fuser.fuse(emptyList()).isEmpty())
        assertTrue(fuser.fuse(listOf(RankedList("a", emptyList()))).isEmpty())
    }

    @Test
    fun fuse_kParameter_affectsScoreMagnitude() {
        val list = RankedList("a", listOf(ScoredItem("x", 1f)))
        val smallK = fuser.fuse(listOf(list), k = 1)[0].score
        val bigK = fuser.fuse(listOf(list), k = 60)[0].score
        assertTrue(smallK > bigK)
        assertEquals(1f / 2f, smallK, 1e-6f)
    }

    @Test
    fun fuse_duplicateIdsAcrossLists_accumulate() {
        val listA = RankedList("a", listOf(ScoredItem("x", 1f)))
        val listB = RankedList("b", listOf(ScoredItem("x", 1f)))
        val listC = RankedList("c", listOf(ScoredItem("x", 1f)))
        val result = fuser.fuse(listOf(listA, listB, listC), k = 60)
        assertEquals(1, result.size)
        assertEquals(3f / 61f, result[0].score, 1e-6f)
    }

    @Test
    fun fuseTwo_ranksBothLists() {
        val listA = listOf(ScoredItem("x", 1f))
        val listB = listOf(ScoredItem("x", 2f), ScoredItem("w", 1f))
        val result = fuser.fuseTwo(listA, listB)
        assertEquals("x", result[0].id)
    }
}
