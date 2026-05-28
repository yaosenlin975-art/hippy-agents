package com.lin.hippyagent.core.behavior

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

@Serializable
data class Bookmark(
    val id: Long,
    val name: String,
    val packageName: String,
    val amCommand: String,
    val createdAt: Long
)

object DeeplinkBookmarkStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val PREFS_NAME = "deeplink_bookmarks"
    private const val KEY_BOOKMARKS = "bookmarks"
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun add(bookmark: Bookmark) {
        val current = getAll().toMutableList()
        current.add(bookmark)
        saveAll(current)
        Timber.i("DeeplinkBookmarkStore: added bookmark ${bookmark.name}")
    }

    fun remove(id: Long) {
        val current = getAll().filter { it.id != id }
        saveAll(current)
    }

    fun getAll(): List<Bookmark> {
        val raw = prefs?.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<Bookmark>>(raw)
        } catch (e: Exception) {
            Timber.w(e, "DeeplinkBookmarkStore: failed to decode bookmarks")
            emptyList()
        }
    }

    private fun saveAll(bookmarks: List<Bookmark>) {
        prefs?.edit()?.putString(KEY_BOOKMARKS, json.encodeToString(bookmarks))?.apply()
    }
}
