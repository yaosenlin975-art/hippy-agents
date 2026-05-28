package com.lin.hippyagent.core.accessibility

import timber.log.Timber
import java.security.MessageDigest

class IncrementalSensor {

    companion object {
        private const val MAX_CONSECUTIVE_SKIPS = 3
        private const val SIMILARITY_THRESHOLD = 0.95f
    }

    private var previousSnapshot: String? = null
    private var previousHash: String? = null
    private var consecutiveSkips = 0

    fun checkUiChange(currentSnapshot: String, previousSnapshot: String?): Boolean {
        if (previousSnapshot == null) return true

        val currentHash = sha256(currentSnapshot)
        val prevHash = if (previousSnapshot == this.previousSnapshot) {
            this.previousHash ?: sha256(previousSnapshot)
        } else {
            sha256(previousSnapshot)
        }

        if (currentHash == prevHash) return false

        val similarity = simpleCharacterSimilarity(currentSnapshot, previousSnapshot)
        return similarity < SIMILARITY_THRESHOLD
    }

    fun shouldSkipReasoning(currentSnapshot: String): Boolean {
        val changed = checkUiChange(currentSnapshot, previousSnapshot)

        if (changed) {
            consecutiveSkips = 0
            previousSnapshot = currentSnapshot
            previousHash = sha256(currentSnapshot)
            Timber.d("IncrementalSensor: UI changed, skip=false")
            return false
        }

        consecutiveSkips++
        if (consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
            consecutiveSkips = 0
            previousSnapshot = currentSnapshot
            previousHash = sha256(currentSnapshot)
            Timber.d("IncrementalSensor: max skips reached, forcing reasoning")
            return false
        }

        previousSnapshot = currentSnapshot
        previousHash = sha256(currentSnapshot)
        Timber.d("IncrementalSensor: no change, skip=true (consecutive=%d)", consecutiveSkips)
        return true
    }

    private fun simpleCharacterSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f

        val freqA = a.groupingBy { it }.eachCount()
        val freqB = b.groupingBy { it }.eachCount()
        val allChars = freqA.keys + freqB.keys

        var common = 0
        var total = 0
        for (c in allChars) {
            val countA = freqA[c] ?: 0
            val countB = freqB[c] ?: 0
            common += minOf(countA, countB)
            total += maxOf(countA, countB)
        }

        return if (total > 0) common.toFloat() / total.toFloat() else 0f
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
