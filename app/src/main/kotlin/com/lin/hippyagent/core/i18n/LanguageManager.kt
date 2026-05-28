package com.lin.hippyagent.core.i18n

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * 多语言管理器
 */
class LanguageManager(
    private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("language_settings", Context.MODE_PRIVATE)
    }

    private var currentLanguage: String = loadLanguage()

    /**
     * 获取当前语言
     */
    fun getCurrentLanguage(): String = currentLanguage

    /**
     * 设置语言
     */
    fun setLanguage(languageCode: String) {
        currentLanguage = languageCode
        saveLanguage(languageCode)
        updateConfiguration(languageCode)
    }

    /**
     * 获取支持的语言列表
     */
    fun getSupportedLanguages(): List<LanguageInfo> {
        return listOf(
            LanguageInfo("zh", "中文", "简体中文"),
            LanguageInfo("en", "English", "English"),
            LanguageInfo("ja", "日本語", "Japanese"),
            LanguageInfo("ko", "한국어", "Korean")
        )
    }

    /**
     * 更新配置
     */
    private fun updateConfiguration(languageCode: String) {
        val locale = when (languageCode) {
            "zh" -> Locale.CHINESE
            "en" -> Locale.ENGLISH
            "ja" -> Locale.JAPANESE
            "ko" -> Locale.KOREAN
            else -> Locale.getDefault()
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    /**
     * 应用保存的语言设置
     */
    fun applySavedLanguage() {
        updateConfiguration(currentLanguage)
    }

    private fun saveLanguage(languageCode: String) {
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    private fun loadLanguage(): String {
        return prefs.getString(KEY_LANGUAGE, "zh") ?: "zh"
    }

    companion object {
        private const val KEY_LANGUAGE = "app_language"
    }
}

data class LanguageInfo(
    val code: String,
    val name: String,
    val nativeName: String
)

