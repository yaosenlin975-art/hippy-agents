package com.lin.hippyagent.core.memory

import com.lin.hippyagent.core.pool.FloatArrayPool
import kotlin.math.sqrt
import kotlin.random.Random

class LocalEmbeddingModel : EmbeddingModel {
    override val dimensions: Int = 128
    private val embeddingPool = FloatArrayPool(arraySize = dimensions, maxSize = 8)

    override suspend fun embed(text: String): Result<FloatArray> {
        return runCatching {
            generateSimpleEmbedding(text)
        }
    }

    override suspend fun embedBatch(texts: List<String>): Result<List<FloatArray>> {
        return runCatching {
            texts.map { generateSimpleEmbedding(it) }
        }
    }

    private fun generateSimpleEmbedding(text: String): FloatArray {
        // 使用 use 模式确保池中的数组被正确释放
        return embeddingPool.use { embedding ->
            val normalized = text.lowercase().trim()
            if (normalized.isEmpty()) return@use embedding.copyOf()

            var hash = normalized.hashCode().toLong()
            for (i in 0 until dimensions) {
                hash = hash * 31 + i
                embedding[i] = (hash % 1000) / 1000f
            }

            val norm = sqrt(embedding.fold(0.0) { acc, v -> acc + v.toDouble() * v.toDouble() }).toFloat()
            if (norm > 0) {
                for (i in embedding.indices) {
                    embedding[i] /= norm
                }
            }

            embedding.copyOf()
        }
    }

    fun releaseEmbedding(arr: FloatArray) {
        embeddingPool.release(arr)
    }
}

