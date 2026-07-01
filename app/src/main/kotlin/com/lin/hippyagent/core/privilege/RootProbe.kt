package com.lin.hippyagent.core.privilege

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Root 探测器。5 秒缓存避免重复 fork su 进程。
 * 用 `su -c id` 探测，输出含 `uid=0` 视为 root 可用。
 */
object RootProbe {

    private const val CACHE_TTL_MS = 5_000L
    private val cache = AtomicReference<Pair<Long, Boolean>?>(null)

    suspend fun hasRoot(forceRefresh: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cache.get()
        if (!forceRefresh && cached != null && now - cached.first < CACHE_TTL_MS) {
            return@withContext cached.second
        }
        val result = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            try {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exited = process.waitFor(3, TimeUnit.SECONDS)
                exited && process.exitValue() == 0 && output.contains("uid=0")
            } finally {
                runCatching { process.destroy() }
            }
        }.getOrElse {
            Timber.d("RootProbe: su not available")
            false
        }
        cache.set(now to result)
        result
    }
}
