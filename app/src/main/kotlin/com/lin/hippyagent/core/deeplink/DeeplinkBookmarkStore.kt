package com.lin.hippyagent.core.deeplink

import android.content.Context
import android.content.SharedPreferences
import com.lin.hippyagent.core.deeplink.model.DeeplinkBookmark
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Deeplink Bookmark 持久化存储（SharedPreferences + JSON）。
 *
 * 数据模型用 [DeeplinkBookmark]（扩展字段：dataUri/component/extras/pageTitle/appName 等）。
 */
object DeeplinkBookmarkStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val PREFS_NAME = "deeplink_bookmarks"
    private const val KEY_BOOKMARKS = "bookmarks"
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun add(bookmark: DeeplinkBookmark) {
        if (prefs == null) {
            Timber.w("DeeplinkBookmarkStore not initialized")
            return
        }
        val current = getAll().toMutableList()
        val exists = current.any {
            it.packageName == bookmark.packageName &&
                it.activityClassName == bookmark.activityClassName &&
                it.dataUri == bookmark.dataUri
        }
        if (!exists) {
            current.add(bookmark)
            saveAll(current)
            Timber.i("DeeplinkBookmarkStore: added bookmark ${bookmark.appName}/${bookmark.pageTitle}")
        }
    }

    fun remove(id: Long) {
        val current = getAll().filter { it.id != id }
        saveAll(current)
    }

    fun getAll(): List<DeeplinkBookmark> {
        val raw = prefs?.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<DeeplinkBookmark>>(raw)
        }.getOrElse {
            Timber.w(it, "DeeplinkBookmarkStore: failed to decode bookmarks")
            emptyList()
        }
    }

    private fun saveAll(bookmarks: List<DeeplinkBookmark>) {
        prefs?.edit()?.putString(KEY_BOOKMARKS, json.encodeToString(bookmarks))?.apply()
    }
}
