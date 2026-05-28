package com.lin.hippyagent.core.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.File

/**
 * 配置文件的读写
 */
class ConfigStorage(
    private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("hippy_config", Context.MODE_PRIVATE)
    }

    /**
     * 保存字符串配置
     */
    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /**
     * 获取字符串配置
     */
    fun getString(key: String, defaultValue: String = ""): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    /**
     * 保存布尔配置
     */
    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    /**
     * 获取布尔配置
     */
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    /**
     * 保存整数配置
     */
    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    /**
     * 获取整数配置
     */
    fun getInt(key: String, defaultValue: Int = 0): Int {
        return prefs.getInt(key, defaultValue)
    }

    /**
     * 保存 JSON 配置
     */
    fun putJson(key: String, json: JsonObject) {
        prefs.edit().putString(key, json.toString()).apply()
    }

    /**
     * 获取 JSON 配置
     */
    fun getJson(key: String): JsonObject? {
        val jsonString = prefs.getString(key, null) ?: return null
        return try {
            json.parseToJsonElement(jsonString).jsonObject
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse JSON config: $key")
            null
        }
    }

    /**
     * 删除配置
     */
    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /**
     * 清空所有配置
     */
    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * 检查是否存在配置
     */
    fun contains(key: String): Boolean {
        return prefs.contains(key)
    }

    /**
     * 获取所有配置键
     */
    fun getAllKeys(): Set<String> {
        return prefs.all.keys
    }

    /**
     * 从文件加载配置
     */
    fun loadFromFile(file: File): JsonObject? {
        return try {
            if (file.exists()) {
                json.parseToJsonElement(file.readText()).jsonObject
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load config from file: ${file.name}")
            null
        }
    }

    /**
     * 保存配置到文件
     */
    fun saveToFile(file: File, config: JsonObject) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(config.toString())
        } catch (e: Exception) {
            Timber.e(e, "Failed to save config to file: ${file.name}")
        }
    }
}

