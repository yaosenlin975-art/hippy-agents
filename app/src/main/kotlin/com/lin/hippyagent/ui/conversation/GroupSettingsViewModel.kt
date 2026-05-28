package com.lin.hippyagent.ui.conversation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.AgentProfile
import com.lin.hippyagent.core.agent.collaboration.AgentGroupManager
import com.lin.hippyagent.core.agent.collaboration.GroupInfo
import com.lin.hippyagent.core.bootstrap.BootstrapHook
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.data.repository.AgentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class GroupSettingsUiState(
    val group: GroupInfo? = null,
    val groupName: String = "",
    val memberAgents: List<AgentProfile> = emptyList(),
    val availableAgents: List<AgentProfile> = emptyList(),
    val isLoading: Boolean = false,
    val showAddMemberDialog: Boolean = false,
    val showDissolveDialog: Boolean = false,
    val errorMessage: String? = null,
    val navigateBack: Boolean = false,
    val mentionOnly: Boolean = true,
    val llmSelectorProviderId: String? = null,
    val llmSelectorModelName: String? = null,
    val availableModels: List<Triple<String, String, String>> = emptyList(),
    val showModelSwitchSheet: Boolean = false,
    val bootstrapAgentIds: Set<String> = emptySet()
)

class GroupSettingsViewModel(
    private val groupManager: AgentGroupManager,
    private val agentRepository: AgentRepository,
    private val modelProviderStore: ModelProviderStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupSettingsUiState())
    val uiState: StateFlow<GroupSettingsUiState> = _uiState.asStateFlow()

    private var groupId: String = ""

    fun loadGroup(groupId: String) {
        this.groupId = groupId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val group = groupManager.getGroup(groupId)
            if (group == null) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "群组不存在")
                }
                return@launch
            }

            val allAgents = agentRepository.loadAgentProfiles().first()
            val memberAgents = group.agentIds.mapNotNull { id -> allAgents[id] }
            val availableAgents = allAgents.values.filter { it.agentId !in group.agentIds }
            val availableModels = loadAvailableModels()

            _uiState.update {
                it.copy(
                    group = group,
                    groupName = group.groupName,
                    memberAgents = memberAgents,
                    availableAgents = availableAgents,
                    isLoading = false,
                    mentionOnly = group.mentionOnlyAgentIds.isNotEmpty(),
                    llmSelectorProviderId = group.llmSelectorProviderId,
                    llmSelectorModelName = group.llmSelectorModelName,
                    availableModels = availableModels
                )
            }
        }
    }

    private suspend fun loadAvailableModels(): List<Triple<String, String, String>> {
        val providers = modelProviderStore.providers.first().filter { it.enabled }
        return providers.flatMap { provider ->
            provider.models.map { model ->
                Triple(model.name, provider.id, provider.name)
            }
        }
    }

    fun updateGroupName(name: String) {
        _uiState.update { it.copy(groupName = name) }
    }

    fun saveGroupName() {
        val group = _uiState.value.group ?: return
        val newName = _uiState.value.groupName.trim()
        if (newName.isEmpty() || newName == group.groupName) return

        viewModelScope.launch {
            groupManager.renameGroup(groupId, newName)

            val updatedGroup = groupManager.getGroup(groupId)
            if (updatedGroup != null) {
                _uiState.update { it.copy(group = updatedGroup) }
                Timber.i("Group name updated: $newName")
            }
        }
    }

    fun showAddMemberDialog() {
        viewModelScope.launch {
            val allAgents = agentRepository.loadAgentProfiles().first()
            val currentMembers = _uiState.value.group?.agentIds ?: emptyList()
            val available = allAgents.values.filter { it.agentId !in currentMembers }
            val bootstrapIds = available
                .filter { BootstrapHook(agentRepository.getAgentWorkspaceDir(it.agentId)).isBootstrapMode() }
                .map { it.agentId }
                .toSet()
            _uiState.update {
                it.copy(showAddMemberDialog = true, availableAgents = available, bootstrapAgentIds = bootstrapIds)
            }
        }
    }

    fun hideAddMemberDialog() {
        _uiState.update { it.copy(showAddMemberDialog = false) }
    }

    fun addMember(agentId: String) {
        viewModelScope.launch {
            groupManager.addAgent(groupId, agentId)
            refreshGroup()
        }
    }

    fun removeMember(agentId: String) {
        viewModelScope.launch {
            groupManager.removeAgent(groupId, agentId)
            refreshGroup()
        }
    }

    fun showDissolveDialog() {
        _uiState.update { it.copy(showDissolveDialog = true) }
    }

    fun hideDissolveDialog() {
        _uiState.update { it.copy(showDissolveDialog = false) }
    }

    fun setMentionOnly(enabled: Boolean) {
        _uiState.update { it.copy(mentionOnly = enabled) }
        viewModelScope.launch {
            val group = _uiState.value.group ?: return@launch
            val updatedAgentIds = if (enabled) group.agentIds else emptyList()
            groupManager.updateGroupMentionOnly(groupId, updatedAgentIds)
            // 更新缓存的 AgentGroup
            val agentGroup = groupManager.getAgentGroup(groupId)
            agentGroup?.mentionOnlyAgentIds = updatedAgentIds
            refreshGroup()
        }
    }

    fun showModelSwitchSheet() {
        _uiState.update { it.copy(showModelSwitchSheet = true) }
    }

    fun hideModelSwitchSheet() {
        _uiState.update { it.copy(showModelSwitchSheet = false) }
    }

    fun setLlmSelector(modelName: String, providerId: String) {
        _uiState.update { it.copy(llmSelectorProviderId = providerId, llmSelectorModelName = modelName) }
        viewModelScope.launch {
            groupManager.updateGroupLlmSelector(groupId, providerId, modelName)
            refreshGroup()
        }
    }

    fun clearLlmSelector() {
        _uiState.update { it.copy(llmSelectorProviderId = null, llmSelectorModelName = null) }
        viewModelScope.launch {
            groupManager.updateGroupLlmSelector(groupId, null, null)
            refreshGroup()
        }
    }

    fun dissolveGroup() {
        viewModelScope.launch {
            groupManager.deleteGroup(groupId)
            Timber.i("Group dissolved: $groupId")
            _uiState.update { it.copy(navigateBack = true) }
        }
    }

    private fun refreshGroup() {
        val group = groupManager.getGroup(groupId) ?: return
        viewModelScope.launch {
            val allAgents = agentRepository.loadAgentProfiles().first()
            val memberAgents = group.agentIds.mapNotNull { id -> allAgents[id] }
            val availableAgents = allAgents.values.filter { it.agentId !in group.agentIds }

            _uiState.update {
                it.copy(
                    group = group,
                    memberAgents = memberAgents,
                    availableAgents = availableAgents,
                    llmSelectorProviderId = group.llmSelectorProviderId,
                    llmSelectorModelName = group.llmSelectorModelName
                )
            }
        }
    }
}

