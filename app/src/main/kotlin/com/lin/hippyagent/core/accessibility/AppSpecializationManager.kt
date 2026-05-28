package com.lin.hippyagent.core.accessibility

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber

@Serializable
enum class RuleType {
    COORDINATE_REDIRECT,
    REF_INJECTION,
    SNAPSHOT_HINT
}

@Serializable
data class SpecializationAction(
    val redirectToRef: String? = null,
    val injectRef: String? = null,
    val hintText: String? = null
)

@Serializable
data class AppSpecializationRule(
    val packageName: String,
    val ruleType: RuleType,
    val targetKeyword: String,
    val action: SpecializationAction
)

class AppSpecializationManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "app_specialization"
        private const val KEY_RULES = "rules"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private var cachedRules: List<AppSpecializationRule>? = null

    fun loadRules(): List<AppSpecializationRule> {
        cachedRules?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rulesJson = prefs.getString(KEY_RULES, null)

        if (rulesJson.isNullOrBlank()) {
            cachedRules = emptyList()
            return emptyList()
        }

        return try {
            val rules = json.decodeFromString<List<AppSpecializationRule>>(rulesJson)
            cachedRules = rules
            Timber.d("Loaded %d specialization rules", rules.size)
            rules
        } catch (e: Exception) {
            Timber.w(e, "Failed to load specialization rules")
            cachedRules = emptyList()
            emptyList()
        }
    }

    fun getRulesForPackage(packageName: String): List<AppSpecializationRule> {
        return loadRules().filter { it.packageName == packageName }
    }

    fun saveRules(rules: List<AppSpecializationRule>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rulesJson = json.encodeToString(ListSerializer(AppSpecializationRule.serializer()), rules)
        prefs.edit().putString(KEY_RULES, rulesJson).apply()
        cachedRules = rules
        Timber.d("Saved %d specialization rules", rules.size)
    }

    fun addRule(rule: AppSpecializationRule) {
        val current = loadRules().toMutableList()
        current.add(rule)
        saveRules(current)
    }

    fun removeRule(packageName: String, targetKeyword: String, ruleType: RuleType) {
        val current = loadRules().filterNot {
            it.packageName == packageName && it.targetKeyword == targetKeyword && it.ruleType == ruleType
        }
        saveRules(current)
    }

    fun clearCache() {
        cachedRules = null
    }
}
