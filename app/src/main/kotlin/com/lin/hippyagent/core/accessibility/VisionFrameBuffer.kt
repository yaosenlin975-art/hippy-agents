package com.lin.hippyagent.core.accessibility

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import timber.log.Timber

@Serializable
data class FrameData(
    val timestamp: Long,
    @Transient
    val jpegBytes: ByteArray = ByteArray(0),
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameData) return false
        return timestamp == other.timestamp && width == other.width && height == other.height
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

class VisionFrameBuffer(private val capacity: Int = 30) {

    private val ring = arrayOfNulls<FrameData>(capacity)
    private var writeIndex = 0
    private var count = 0

    @Synchronized
    fun offer(frame: FrameData) {
        ring[writeIndex] = frame
        writeIndex = (writeIndex + 1) % capacity
        if (count < capacity) count++
        Timber.d("Frame offered ts=%d, buffer count=%d", frame.timestamp, count)
    }

    @Synchronized
    fun getLatestFrameAtOrBefore(targetTs: Long): FrameData? {
        if (count == 0) return null
        var best: FrameData? = null
        for (i in 0 until count) {
            val idx = (writeIndex - 1 - i + capacity) % capacity
            val frame = ring[idx] ?: continue
            if (frame.timestamp <= targetTs) {
                best = frame
                break
            }
        }
        return best
    }

    @Synchronized
    fun getFrameClosestTo(targetTs: Long): FrameData? {
        if (count == 0) return null
        var best: FrameData? = null
        var bestDelta = Long.MAX_VALUE
        for (i in 0 until count) {
            val idx = (writeIndex - 1 - i + capacity) % capacity
            val frame = ring[idx] ?: continue
            val delta = kotlin.math.abs(frame.timestamp - targetTs)
            if (delta < bestDelta) {
                bestDelta = delta
                best = frame
            }
        }
        return best
    }

    @Synchronized
    fun selectFramesForQuery(userText: String): List<FrameData> {
        if (count == 0) return emptyList()
        val dynamicKeywords = listOf("刚才", "过程", "怎么做的", "动态", "刚才的", "整个过程", "发生了什么", "变化")
        val isDynamicQuery = dynamicKeywords.any { userText.contains(it) }
        val requestedCount = if (isDynamicQuery) 10 else 1
        val result = mutableListOf<FrameData>()
        for (i in 0 until minOf(requestedCount, count)) {
            val idx = (writeIndex - 1 - i + capacity) % capacity
            ring[idx]?.let { result.add(it) }
        }
        Timber.d("selectFramesForQuery: dynamicQuery=%b, selected=%d", isDynamicQuery, result.size)
        return result
    }

    @Synchronized
    fun clear() {
        for (i in ring.indices) ring[i] = null
        writeIndex = 0
        count = 0
        Timber.d("Frame buffer cleared")
    }
}
