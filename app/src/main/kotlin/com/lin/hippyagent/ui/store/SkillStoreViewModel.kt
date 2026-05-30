package com.lin.hippyagent.ui.store

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.skill.SkillManager
import com.lin.hippyagent.core.skill.store.InstallQueue
import com.lin.hippyagent.core.skill.store.SkillSource
import com.lin.hippyagent.core.skill.store.SkillStoreService
import com.lin.hippyagent.core.skill.store.StoreSkillItem
import com.lin.hippyagent.core.skill.store.provider.MarketProviderInfo
import com.lin.hippyagent.core.skill.store.provider.MarketSearchError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class SkillStoreViewModel(
    private val storeService: SkillStoreService,
    private val skillManager: SkillManager,
    private val sourceAgentId: String? = null,
    private val workspaceManager: com.lin.hippyagent.core.storage.WorkspaceManager? = null
) : ViewModel() {
    companion object {
        private val skillIdCleanupRegex = Regex("[^a-z0-9_-]")
    }

    private val _uiState = MutableStateFlow(SkillStoreUiState())
    val uiState: StateFlow<SkillStoreUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    private val cursors = mutableMapOf<String, Int>()

    private val _installQueue = InstallQueue(
        scope = viewModelScope,
        storeService = storeService,
        sourceAgentId = sourceAgentId,
        workspaceManager = workspaceManager
    )
    val installQueue: StateFlow<List<InstallQueue.QueueItem>> = _installQueue.items

    private val _npxReady = MutableStateFlow(false)
    val npxReady: StateFlow<Boolean> = _npxReady.asStateFlow()

    private val _providers = MutableStateFlow<List<MarketProviderInfo>>(emptyList())
    val providers: StateFlow<List<MarketProviderInfo>> = _providers.asStateFlow()

    private val _selectedProviderKeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedProviderKeys: StateFlow<Set<String>> = _selectedProviderKeys.asStateFlow()

    fun setNodeStatus(status: NodeStatus) {
        _uiState.update { it.copy(nodeStatus = status) }
    }

    fun checkAndPrepareNpx() {
        viewModelScope.launch(Dispatchers.IO) {
            val linuxManager = storeService.getLinuxManager()
            if (!linuxManager.isReady.value) return@launch
            val (code, _) = linuxManager.exec("which npx && npx --version", timeout = 10_000)
            if (code == 0) {
                _npxReady.value = true
                loadHotSkills()
                return@launch
            }
            _uiState.update { it.copy(nodeStatus = NodeStatus.Installing) }
            val (installCode, _) = linuxManager.exec(
                "apt-get update -qq && apt-get install -y --no-install-recommends nodejs npm",
                timeout = 120_000
            )
            if (installCode == 0) {
                _npxReady.value = true
                _uiState.update { it.copy(nodeStatus = NodeStatus.Ready) }
                loadHotSkills()
            } else {
                _uiState.update { it.copy(nodeStatus = NodeStatus.Failed) }
            }
        }
    }

    init {
        refreshInstalledStatus()
        loadProviders()
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        cursors.clear()
        if (query.isBlank()) {
            loadHotSkills()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            doSearch(query)
        }
    }

    fun setSource(source: SkillSource?) {
        _uiState.update { it.copy(activeSource = source, isLoading = true) }
        cursors.clear()
        val query = _uiState.value.searchQuery
        if (query.isBlank()) loadHotSkills() else {
            viewModelScope.launch { doSearch(query) }
        }
    }

    fun setSortType(sort: SortType) {
        _uiState.update { it.copy(sortType = sort) }
        val currentSkills = _uiState.value.skills
        if (currentSkills.isNotEmpty()) {
            _uiState.update { it.copy(skills = sortSkills(currentSkills, sort)) }
        }
    }

    fun showInstallDialog(skill: StoreSkillItem) {
        _uiState.update { it.copy(showInstallDialog = skill) }
    }

    fun dismissInstallDialog() {
        _uiState.update { it.copy(showInstallDialog = null) }
    }

    fun selectSkill(skill: StoreSkillItem?) {
        _uiState.update { it.copy(selectedSkill = skill) }
    }

    fun installSkill(skill: StoreSkillItem) {
        _uiState.update { it.copy(showInstallDialog = null) }
        _installQueue.enqueue(listOf(skill), _uiState.value.installTarget)
        refreshInstalledStatus()
    }

    fun cancelInstall(id: String) {
        _installQueue.cancel(id)
    }

    fun retryInstall(id: String) {
        _installQueue.retry(id)
        refreshInstalledStatus()
    }

    fun clearCompletedInstalls() {
        _installQueue.clearCompleted()
        refreshInstalledStatus()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setInstallTarget(target: InstallTarget) {
        _uiState.update { it.copy(installTarget = target) }
    }

    fun loadMore() {
        if (_uiState.value.isLoadingMore) return
        val query = _uiState.value.searchQuery
        val pages = cursors.filterValues { it > 0 }.toMap()
        if (pages.isEmpty()) return

        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            try {
                val resp = storeService.searchAll(query, _selectedProviderKeys.value, pages)
                _uiState.update { state ->
                    val existing = state.skills.associateBy { it.identifier }.toMutableMap()
                    resp.results.forEach { existing[it.identifier] = it }
                    state.copy(skills = existing.values.toList())
                }
                for ((key, info) in resp.byProvider) {
                    if (info.hasMore) cursors[key] = (pages[key] ?: 1) + 1
                    else cursors.remove(key)
                }
                _uiState.update { it.copy(isLoadingMore = false, hasMore = cursors.isNotEmpty()) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingMore = false, error = "加载更多失败: ${e.message}") }
            }
        }
    }

    fun loadHotSkills() {
        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, error = null, providerErrors = emptyList(), hasMore = false) }
        cursors.clear()
        viewModelScope.launch {
            try {
                val source = state.activeSource
                val keys = _selectedProviderKeys.value
                val filteredKeys = when (source) {
                    SkillSource.LOBEHUB -> keys intersect setOf("lobehub")
                    SkillSource.SKILLS_SH -> keys intersect setOf("skills_sh")
                    SkillSource.CLAWHUB -> keys intersect setOf("clawhub")
                    null -> keys
                }
                val result = storeService.searchAll("popular", filteredKeys)
                val errors = result.errors.map { MarketSearchError(it.provider, it.message) }
                val all = result.results.sortedByDescending { it.installCount }
                _uiState.update { current ->
                    current.copy(
                        hotSkills = all.take(10),
                        skills = if (current.searchQuery.isBlank()) all else current.skills,
                        isLoading = false,
                        providerErrors = errors,
                        hasMore = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load hot skills")
                _uiState.update { it.copy(isLoading = false, error = "加载失败: ${e.message}") }
            }
        }
    }

    private suspend fun doSearch(query: String) {
        _uiState.update { it.copy(isLoading = true, error = null, providerErrors = emptyList()) }
        cursors.clear()
        try {
            val result = storeService.searchAll(query, _selectedProviderKeys.value)
            _uiState.update { state ->
                val existing = state.skills.associateBy { it.identifier }.toMutableMap()
                result.results.forEach { existing[it.identifier] = it }
                state.copy(skills = existing.values.toList())
            }
            if (result.errors.isNotEmpty()) {
                _uiState.update { state ->
                    state.copy(providerErrors = result.errors.map {
                        MarketSearchError(it.provider, it.message)
                    })
                }
            }
            for ((key, info) in result.byProvider) {
                if (info.hasMore) {
                    cursors[key] = 2
                } else {
                    cursors.remove(key)
                }
            }
            _uiState.update { it.copy(hasMore = cursors.isNotEmpty()) }
        } catch (e: Exception) {
            Timber.e(e, "Search failed")
            _uiState.update { it.copy(error = "搜索失败: ${e.message}") }
        } finally {
            _uiState.update {
                it.copy(
                    skills = sortSkills(it.skills, it.sortType),
                    isLoading = false
                )
            }
        }
    }

    private fun sortSkills(skills: List<StoreSkillItem>, sort: SortType): List<StoreSkillItem> {
        return when (sort) {
            SortType.HOT -> skills.sortedByDescending { it.installCount + it.starsCount }
            SortType.NEW -> skills.sortedByDescending { it.updatedAt }
            SortType.RATING -> skills.sortedByDescending { it.rating }
            SortType.INSTALLS -> skills.sortedByDescending { it.installCount }
        }
    }

    private fun refreshInstalledStatus() {
        viewModelScope.launch {
            try {
                val installed = skillManager.listSkills().map { it.id }.toSet()
                _uiState.update { it.copy(installedIds = installed) }
            } catch (e: Exception) {
                Timber.w(e, "Failed to list installed skills")
            }
        }
    }

    private fun loadProviders() {
        viewModelScope.launch {
            val providerList = storeService.listProviders()
            _providers.value = providerList
            _selectedProviderKeys.value = providerList.filter { it.available }.map { it.key }.toSet()
        }
    }

    fun toggleProvider(key: String) {
        val current = _selectedProviderKeys.value.toMutableSet()
        if (current.contains(key)) current.remove(key) else current.add(key)
        _selectedProviderKeys.value = current
        val query = _uiState.value.searchQuery
        if (query.isNotBlank()) {
            viewModelScope.launch { doSearch(query) }
        } else {
            loadHotSkills()
        }
    }
}
