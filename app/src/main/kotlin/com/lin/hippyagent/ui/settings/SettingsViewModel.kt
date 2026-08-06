package com.lin.hippyagent.ui.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.AgentProfile
import com.lin.hippyagent.core.storage.SecureStorage
import com.lin.hippyagent.data.repository.AgentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

private val NAME_LINE_REGEX = Regex("""(- \*\*名字[：:]\*\*\s*).*""", RegexOption.MULTILINE)

@Immutable
data class SettingsUiState(
    val agents: List<AgentProfile> = emptyList(),
    val language: String = "",
    val notificationsEnabled: Boolean = true,
    val storagePath: String = "",
    val apiKeyCount: Int = 0,
    val pendingApprovals: Int = 0,
    val lastBackupTime: String? = null,
    val appVersion: String = "1.0.0",
    val isLoading: Boolean = false
)

class SettingsViewModel(
    private val repository: AgentRepository,
    val secureStorage: SecureStorage,
    private val application: Application,
    private val storageManager: com.lin.hippyagent.core.storage.StorageManager,
    private val agentFactory: com.lin.hippyagent.core.agent.AgentFactory
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val prefs: SharedPreferences by lazy {
        application.getSharedPreferences("hippy_settings", Context.MODE_PRIVATE)
    }

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val profiles = repository.loadAgentProfiles().first()
                val apiKeys = secureStorage.listApiKeys()
                val langCode = prefs.getString("language", "zh") ?: "zh"
                val langName = when (langCode) {
                    "zh" -> application.getString(R.string.settings_lang_zh)
                    "en" -> application.getString(R.string.settings_lang_en)
                    "ja" -> application.getString(R.string.settings_lang_ja)
                    "ko" -> application.getString(R.string.settings_lang_ko)
                    else -> application.getString(R.string.settings_lang_zh)
                }
                val notificationsEnabled = prefs.getBoolean("notifications_enabled", true)
                val appVersion = try {
                    application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "1.0.0"
                } catch (e: Exception) { "1.0.0" }

                _uiState.update {
                    it.copy(
                        agents = profiles.values.toList(),
                        apiKeyCount = apiKeys.size,
                        language = langName,
                        notificationsEnabled = notificationsEnabled,
                        storagePath = storageManager.getStoragePathDisplay(),
                        appVersion = appVersion,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load settings")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun refreshAgents() {
        viewModelScope.launch {
            val profiles = repository.loadAgentProfiles().first()
            _uiState.update { it.copy(agents = profiles.values.toList()) }
        }
    }

    fun updateAgentName(agentId: String, name: String) {
        viewModelScope.launch {
            val profiles = repository.loadAgentProfiles().first()
            val agent = profiles[agentId] ?: return@launch
            val updated = agent.copy(name = name)
            repository.saveAgentProfile(updated)
                .onSuccess {
                    syncNameToProfileMd(agentId, name)
                    refreshAgents()
                }
                .onFailure { e ->
                    Timber.e(e, "Failed to update agent name")
                }
        }
    }

    private suspend fun syncNameToProfileMd(agentId: String, name: String) {
        try {
            val result = repository.readCoreFile(agentId, "PROFILE.md")
            result.onSuccess { content ->
                val updated = content.replace(
                    NAME_LINE_REGEX,
                    "$1$name"
                )
                if (updated != content) {
                    repository.writeCoreFile(agentId, "PROFILE.md", updated)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync name to PROFILE.md")
        }
    }

    fun updateAgentEnabled(agentId: String, enabled: Boolean) {
        viewModelScope.launch {
            val profiles = repository.loadAgentProfiles().first()
            val agent = profiles[agentId] ?: return@launch
            val updated = agent.copy(enabled = enabled)
            repository.saveAgentProfile(updated)
                .onSuccess {
                    agentFactory.reloadAgent(agentId)
                    refreshAgents()
                }
                .onFailure { e ->
                    Timber.e(e, "Failed to update agent enabled")
                }
        }
    }

    fun refreshSettings() {
        loadSettings()
    }

    fun updateLanguage(language: String) {
        val langCode = when (language) {
            application.getString(R.string.settings_lang_zh) -> "zh"
            application.getString(R.string.settings_lang_en) -> "en"
            application.getString(R.string.settings_lang_ja) -> "ja"
            application.getString(R.string.settings_lang_ko) -> "ko"
            else -> "zh"
        }
        prefs.edit().putString("language", langCode).apply()
        _uiState.update { it.copy(language = language) }
    }

    fun updateNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notifications_enabled", enabled).apply()
        _uiState.update { it.copy(notificationsEnabled = enabled) }
    }
}

