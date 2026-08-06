package com.lin.hippyagent.ui.chat

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.lin.hippyagent.core.model.ModelProvider
import timber.log.Timber
import com.lin.hippyagent.R

private val TITLE_PREFIX_REGEX = Regex("^(你好|hello|hi|嗨|hey|请问|请|帮我|帮我想|我想|我要|告诉|说说|聊聊)")

private val VISION_MODEL_REGEX = Regex("(?i)(vision|vl|gemini|gpt-4o|gpt-5|claude-3|claude-4|qwen-vl|llava|deepseek-vl|flash-image)")

internal fun ChatViewModel.loadAvailableModels() {
    viewModelScope.launch {
        try {
            val providers = modelProviderStore?.providers?.first()
            cachedProviders = providers
            if (providers != null) {
                val models = providers.filter { it.enabled }.flatMap { provider ->
                    provider.models.filter { it.enabled }.map { model ->
                        Triple(model.name, provider.id, provider.name)
                    }
                }
                val currentSelected = _uiState.value.selectedModel
                val matchedModel = if (currentSelected.isNotEmpty()) {
                    models.find { it.first == currentSelected || it.first.endsWith("/$currentSelected") }
                } else null
                _uiState.update {
                    it.copy(
                        availableModels = models,
                        selectedModel = matchedModel?.first ?: currentSelected
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load available models")
        }
    }
}

internal fun ChatViewModel.deriveTitleFromFirstMessage(text: String): String {
    val cleaned = text.trim()
        .replace(TITLE_PREFIX_REGEX, "")
        .trim()
    val result = cleaned.ifEmpty { text }
    return if (result.length <= 20) result else result.take(20) + "…"
}

internal fun ChatViewModel.selectModel(modelName: String, providerId: String = "") {
    _uiState.update { it.copy(selectedModel = modelName, selectedProviderId = providerId) }
    // 检查是否为免费模型，是则弹出提
    checkFreeModelWarning(modelName, providerId)
    val sessionId = _uiState.value.sessionId
    if (sessionId.isNotEmpty()) {
        viewModelScope.launch {
            sessionStore.updateSessionModel(sessionId, modelName)
        }
    }
}

internal fun ChatViewModel.dismissFreeModelWarning() {
    val pending = _uiState.value.pendingSendText
    val pendingChips = _uiState.value.pendingSendChips
    _uiState.update { it.copy(showFreeModelWarning = false, pendingSendText = null, pendingSendChips = emptyList()) }
    if (pending != null) {
        sendMessage(pending, chips = pendingChips)
    }
}

internal fun ChatViewModel.suppressFreeModelWarning() {
    val prefs = context.getSharedPreferences("free_model_warning", Application.MODE_PRIVATE)
    prefs.edit().putBoolean("suppressed", true).apply()
    _uiState.update { it.copy(showFreeModelWarning = false) }
}

internal fun ChatViewModel.checkFreeModelWarning(modelName: String, providerId: String) {
    val prefs = context.getSharedPreferences("free_model_warning", Application.MODE_PRIVATE)
    if (prefs.getBoolean("suppressed", false)) return
    viewModelScope.launch {
        val allProviders = modelProviderStore?.providers?.first() ?: return@launch
        var foundFree = false
        for (provider in allProviders) {
            if (providerId.isNotBlank() && provider.id != providerId) continue
            for (m in provider.models) {
                if (m.name == modelName && m.free) {
                    foundFree = true
                    break
                }
            }
            if (foundFree) break
        }
        if (foundFree) {
            _uiState.update { it.copy(showFreeModelWarning = true, freeModelKeys = setOf("${providerId}:${modelName}")) }
        }
    }
}

internal fun ChatViewModel.isCurrentModelVisionCapable(): Boolean {
    val selectedModel = _uiState.value.selectedModel
    if (selectedModel.isBlank()) return false
    if (VISION_MODEL_REGEX.containsMatchIn(selectedModel)) return true
    val allProviders = cachedProviders ?: return false
    for (provider in allProviders) {
        for (m in provider.models) {
            if (m.name == selectedModel && com.lin.hippyagent.core.model.ModelCapability.VISION in m.capabilities) {
                return true
            }
        }
    }
    return false
}

internal fun ChatViewModel.checkFreeModelBeforeSend(text: String, chips: List<InputChip>): Boolean {
    val prefs = context.getSharedPreferences("free_model_warning", Application.MODE_PRIVATE)
    if (prefs.getBoolean("suppressed", false)) return true
    val selectedModel = _uiState.value.selectedModel
    if (selectedModel.isBlank()) return true
    val allProviders = cachedProviders ?: return true
    var foundFree = false
    for (provider in allProviders) {
        for (m in provider.models) {
            if (m.name == selectedModel && m.free) {
                foundFree = true
                break
            }
        }
        if (foundFree) break
    }
    if (foundFree) {
        _uiState.update { it.copy(showFreeModelWarning = true, freeModelKeys = setOf("send:${selectedModel}"), pendingSendText = text, pendingSendChips = chips) }
        return false
    }
    return true
}

internal fun ChatViewModel.setAvailableModels(models: List<Triple<String, String, String>>) {
    _uiState.update { it.copy(availableModels = models) }
}
