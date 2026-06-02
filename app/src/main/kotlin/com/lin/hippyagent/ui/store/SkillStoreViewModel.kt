package com.lin.hippyagent.ui.store

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.R
import com.lin.hippyagent.core.skill.SkillManager
import com.lin.hippyagent.core.skill.store.InstallQueue
import com.lin.hippyagent.core.skill.store.SkillSource
import com.lin.hippyagent.core.skill.store.SkillStoreService
import com.lin.hippyagent.core.skill.store.StoreSkillItem
import com.lin.hippyagent.core.skill.store.SkillIdNormalizer
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
    application: Application,
    private val storeService: SkillStoreService,
    private val skillManager: SkillManager,
    private val sourceAgentId: String? = null,
    private val workspaceManager: com.lin.hippyagent.core.storage.WorkspaceManager? = null
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SkillStoreUiState())
    val uiState: StateFlow<SkillStoreUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    private val cursors = mutableMapOf<String, Int>()

    private val descriptionCache = mutableMapOf<String, String>()

    private val _installQueue = InstallQueue(
        scope = viewModelScope,
        storeService = storeService,
        context = application,
        skillManager = skillManager,
        sourceAgentId = sourceAgentId,
        workspaceManager = workspaceManager
    ).also { queue ->
        queue.listener = object : InstallQueue.Listener {
            override fun onInstallCompleted(skill: StoreSkillItem) {
                refreshInstalledStatus()
                val msg = getApplication<Application>().getString(R.string.store_install_success, skill.name)
                _uiState.update { it.copy(installMessage = msg) }
            }
            override fun onInstallFailed(skill: StoreSkillItem, error: String?) {
                val app = getApplication<Application>()
                val msg = if (error != null) {
                    app.getString(R.string.store_install_failed, skill.name, error)
                } else {
                    app.getString(R.string.store_install_failed_unknown, skill.name)
                }
                _uiState.update { it.copy(installMessage = msg) }
            }
        }
    }
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
        viewModelScope.launch {
            _installQueue.items.collect { items ->
                val installing = items.filter { it.status == InstallQueue.QueueItem.Status.INSTALLING }.map { it.skill.identifier }.toSet()
                val queued = items.filter { it.status == InstallQueue.QueueItem.Status.QUEUED }.map { it.skill.identifier }.toSet()
                _uiState.update { it.copy(installingIds = installing, queuedIds = queued) }
            }
        }
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
        val isAlreadyInQueue = _installQueue.items.value.any {
            it.skill.identifier == skill.identifier && (it.status == InstallQueue.QueueItem.Status.QUEUED || it.status == InstallQueue.QueueItem.Status.INSTALLING)
        }
        if (isAlreadyInQueue) return
        _uiState.update { it.copy(showInstallDialog = skill) }
    }

    fun dismissInstallDialog() {
        _uiState.update { it.copy(showInstallDialog = null) }
    }

    fun selectSkill(skill: StoreSkillItem?) {
        _uiState.update { it.copy(selectedSkill = skill, isLoadingDetail = skill != null && skill.description.isBlank()) }
        if (skill != null && skill.description.isBlank()) {
            fetchSkillDetail(skill)
        }
    }

    private fun fetchSkillDetail(skill: StoreSkillItem) {
        val cached = descriptionCache[skill.identifier]
        if (cached != null) {
            _uiState.update { it.copy(selectedSkill = skill.copy(description = cached), isLoadingDetail = false) }
            return
        }
        viewModelScope.launch {
            val providerKey = when (skill.source) {
                SkillSource.LOBEHUB -> "lobehub"
                SkillSource.SKILLS_SH -> "skills_sh"
                SkillSource.CLAWHUB -> "clawhub"
                SkillSource.SKILLHUB -> "skillhub"
            }
            storeService.getDetail(providerKey, skill.identifier).onSuccess { detail ->
                if (detail != null && _uiState.value.selectedSkill?.identifier == skill.identifier) {
                    val merged = skill.copy(
                        description = detail.description.ifBlank { skill.description },
                        author = detail.author.ifBlank { skill.author },
                        name = detail.name.ifBlank { skill.name }
                    )
                    descriptionCache[skill.identifier] = detail.description
                    _uiState.update { it.copy(selectedSkill = merged, isLoadingDetail = false) }
                } else {
                    _uiState.update { it.copy(isLoadingDetail = false) }
                }
            }.onFailure {
                _uiState.update { it.copy(isLoadingDetail = false) }
            }
        }
    }

    fun installSkill(skill: StoreSkillItem) {
        val isAlreadyInQueue = _installQueue.items.value.any {
            it.skill.identifier == skill.identifier && (it.status == InstallQueue.QueueItem.Status.QUEUED || it.status == InstallQueue.QueueItem.Status.INSTALLING)
        }
        if (isAlreadyInQueue) {
            _uiState.update { it.copy(showInstallDialog = null) }
            return
        }
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

    fun clearInstallMessage() {
        _uiState.update { it.copy(installMessage = null) }
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
                    SkillSource.SKILLHUB -> keys intersect setOf("skillhub")
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
                prefetchDescriptions(all)
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
            refreshSkillListInstallStatus()
            prefetchDescriptions(_uiState.value.skills)
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
                _uiState.update { it.copy(installedIds = installed, installedNormalizedIds = SkillIdNormalizer.normalizeAll(installed)) }
                refreshSkillListInstallStatus()
            } catch (e: Exception) {
                Timber.w(e, "Failed to list installed skills")
            }
        }
    }

    private fun refreshSkillListInstallStatus() {
        _uiState.update { state ->
            val installedExact = state.installedIds
            val installedNormalized = state.installedNormalizedIds.ifEmpty { SkillIdNormalizer.normalizeAll(installedExact) }
            state.copy(
                installedNormalizedIds = installedNormalized,
                hotSkills = state.hotSkills.map { item -> item.copy(isInstalled = installedExact.contains(item.identifier) || installedNormalized.contains(SkillIdNormalizer.normalize(item.identifier))) },
                skills = state.skills.map { item -> item.copy(isInstalled = installedExact.contains(item.identifier) || installedNormalized.contains(SkillIdNormalizer.normalize(item.identifier))) }
            )
        }
    }

    private fun prefetchDescriptions(skills: List<StoreSkillItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            skills.filter { it.description.isBlank() }.forEach { skill ->
                if (descriptionCache.containsKey(skill.identifier)) return@forEach
                val providerKey = when (skill.source) {
                    SkillSource.LOBEHUB -> "lobehub"
                    SkillSource.SKILLS_SH -> "skills_sh"
                    SkillSource.CLAWHUB -> "clawhub"
                    SkillSource.SKILLHUB -> "skillhub"
                }
                runCatching {
                    storeService.getDetail(providerKey, skill.identifier).onSuccess { detail ->
                        if (detail != null) {
                            descriptionCache[skill.identifier] = detail.description
                            _uiState.update { state ->
                                state.copy(
                                    skills = state.skills.map { if (it.identifier == skill.identifier) it.copy(description = detail.description) else it },
                                    hotSkills = state.hotSkills.map { if (it.identifier == skill.identifier) it.copy(description = detail.description) else it }
                                )
                            }
                        }
                    }
                }
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

