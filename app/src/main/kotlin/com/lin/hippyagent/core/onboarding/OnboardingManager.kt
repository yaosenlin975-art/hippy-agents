package com.lin.hippyagent.core.onboarding

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lin.hippyagent.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(name = "onboarding")

/**
 * 首次使用引导管理器
 */
class OnboardingManager(
    private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_ONBOARDING_STEP = stringPreferencesKey("onboarding_step")
        val KEY_USER_PREFERENCES = stringPreferencesKey("user_preferences")
    }

    /**
     * 用户偏好
     */
    @Serializable
    data class UserPreferences(
        val language: String = "zh",
        val expertise: String = "general",
        val interests: List<String> = emptyList(),
        val onboardingVersion: Int = 1
    )

    /**
     * 引导步骤
     */
    @Serializable
    data class OnboardingStep(
        val id: String,
        val title: String,
        val description: String,
        val icon: String,
        val options: List<String> = emptyList()
    )

    /**
     * 检查是否已完成引导
     */
    val isOnboardingCompleted: Flow<Boolean> = context.onboardingDataStore.data
        .map { preferences ->
            preferences[KEY_ONBOARDING_COMPLETED] ?: false
        }

    /**
     * 获取当前引导步骤
     */
    val currentStep: Flow<String> = context.onboardingDataStore.data
        .map { preferences ->
            preferences[KEY_ONBOARDING_STEP] ?: "welcome"
        }

    /**
     * 获取用户偏好
     */
    val userPreferences: Flow<UserPreferences> = context.onboardingDataStore.data
        .map { preferences ->
            val prefsJson = preferences[KEY_USER_PREFERENCES] ?: "{}"
            runCatching {
                json.decodeFromString<UserPreferences>(prefsJson)
            }.getOrDefault(UserPreferences())
        }

    /**
     * 标记引导步骤完成
     */
    suspend fun completeStep(stepId: String) {
        context.onboardingDataStore.edit { preferences ->
            preferences[KEY_ONBOARDING_STEP] = stepId
        }
    }

    /**
     * 标记引导完成
     */
    suspend fun completeOnboarding() {
        context.onboardingDataStore.edit { preferences ->
            preferences[KEY_ONBOARDING_COMPLETED] = true
        }
    }

    /**
     * 保存用户偏好
     */
    suspend fun saveUserPreferences(prefs: UserPreferences) {
        context.onboardingDataStore.edit { preferences ->
            preferences[KEY_USER_PREFERENCES] = json.encodeToString(prefs)
        }
    }

    /**
     * 重置引导（用于测试）
     */
    suspend fun resetOnboarding() {
        context.onboardingDataStore.edit { preferences ->
            preferences.clear()
        }
    }

    /**
     * 获取所有引导步骤
     */
    fun getAllSteps(): List<OnboardingStep> = listOf(
        OnboardingStep(
            id = "welcome",
            title = context.getString(R.string.onboarding_step_welcome_title),
            description = context.getString(R.string.onboarding_step_welcome_desc),
            icon = "🐾"
        ),
        OnboardingStep(
            id = "language",
            title = context.getString(R.string.onboarding_step_language_title),
            description = context.getString(R.string.onboarding_step_language_desc),
            icon = "🌐",
            options = listOf("中文", "English", "日本語")
        ),
        OnboardingStep(
            id = "expertise",
            title = context.getString(R.string.onboarding_step_expertise_title),
            description = context.getString(R.string.onboarding_step_expertise_desc),
            icon = "🎯",
            options = listOf("编程开发", "数据分析", "日常助手", "创意设计")
        ),
        OnboardingStep(
            id = "model",
            title = context.getString(R.string.onboarding_step_model_title),
            description = context.getString(R.string.onboarding_step_model_desc),
            icon = "🧠",
            options = listOf("端侧小模型 (快速)", "云端大模型 (强大)", "混合模式")
        ),
        OnboardingStep(
            id = "privacy",
            title = context.getString(R.string.onboarding_step_privacy_title),
            description = context.getString(R.string.onboarding_step_privacy_desc),
            icon = "🔒"
        ),
        OnboardingStep(
            id = "complete",
            title = context.getString(R.string.onboarding_step_complete_title),
            description = context.getString(R.string.onboarding_step_complete_desc),
            icon = "🚀"
        )
    )
}

