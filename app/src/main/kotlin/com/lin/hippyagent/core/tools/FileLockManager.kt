package com.lin.hippyagent.core.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

class FileLockManager(
    private val lockTimeoutMs: Long = 30_000L
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val canonicalPathCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > 256
        }
    }

    @Synchronized
    private fun getCachedCanonicalPath(path: String): String {
        canonicalPathCache[path]?.let { return it }
        val canonical = runCatching { java.io.File(path).canonicalPath }.getOrDefault(path)
        canonicalPathCache[path] = canonical
        return canonical
    }

    private fun getLock(path: String): Mutex =
        locks.getOrPut(path) { Mutex() }

    suspend fun <T> withReadLock(path: String, block: suspend () -> T): T? {
        val canonicalPath = getCachedCanonicalPath(path)
        val mutex = getLock(canonicalPath)
        return withTimeoutOrNull(lockTimeoutMs) {
            mutex.withLock { block() }
        } ?: run {
            Timber.w("FileLockManager: read lock timeout for $canonicalPath")
            null
        }
    }

    suspend fun <T> withWriteLock(path: String, block: suspend () -> T): T? {
        val canonicalPath = getCachedCanonicalPath(path)
        val mutex = getLock(canonicalPath)
        return withTimeoutOrNull(lockTimeoutMs) {
            mutex.withLock { block() }
        } ?: run {
            Timber.w("FileLockManager: write lock timeout for $canonicalPath")
            null
        }
    }

    fun cleanup() {
        locks.entries.removeIf { (_, mutex) -> !mutex.isLocked }
        synchronized(this) {
            canonicalPathCache.clear()
        }
    }
}

