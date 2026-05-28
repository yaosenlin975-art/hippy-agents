package com.lin.hippyagent.core.ondevice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

object HuggingFaceMirror {

    private val MIRRORS = listOf(
        "hf-mirror.com",
        "huggingface.do.mirr.one",
    )

    private val cachedMirror = AtomicReference<String?>(null)

    private val HOST_REGEX = Regex("(https?://)huggingface\\.co")

    fun resolveUrl(originalUrl: String): String {
        val cached = cachedMirror.get() ?: return originalUrl
        return originalUrl.replaceFirst(HOST_REGEX, "$1$cached")
    }

    suspend fun probeAndCache(store: OnDeviceModelStore) {
        val mirror = MIRRORS.firstOrNull { isReachable("https://$it") }
        if (mirror != null) {
            cachedMirror.set(mirror)
            runCatching { store.saveMirrorDomain(mirror) }
            Timber.i("HuggingFaceMirror: using mirror $mirror")
        } else {
            Timber.w("HuggingFaceMirror: no mirror reachable, using direct")
        }
    }

    suspend fun restoreCache(store: OnDeviceModelStore) {
        runCatching {
            store.loadMirrorDomain()?.let { cachedMirror.set(it) }
        }
    }

    private suspend fun isReachable(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
