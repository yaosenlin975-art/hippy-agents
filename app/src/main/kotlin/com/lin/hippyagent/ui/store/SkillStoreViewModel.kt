package com.lin.hippyagent.ui.store

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.skill.SkillManager
import com.lin.hippyagent.core.skill.store.SkillSource
import com.lin.hippyagent.core.skill.store.SkillStoreService
import com.lin.hippyagent.core.skill.store.StoreSkillItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
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

    private val _npxReady = MutableStateFlow(false)
    val npxReady: StateFlow<Boolean> = _npxReady.asStateFlow()

    /** 暴露 LinuxManager 给 UI 层自动安装 Node 时使用 */
    fun getLinuxManager() = storeService.getLinuxManager()

    /** 设置 Node.js 环境状态提示 */
    fun setNodeStatus(status: String?) {
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
            _uiState.update { it.copy(nodeStatus = "installing") }
            val (installCode, _) = linuxManager.exec(
                "apt-get update -qq && apt-get install -y --no-install-recommends nodejs npm",
                timeout = 120_000
            )
            if (installCode == 0) {
                _npxReady.value = true
                _uiState.update { it.copy(nodeStatus = null) }
                loadHotSkills()
            } else {
                _uiState.update { it.copy(nodeStatus = "failed") }
            }
        }
    }

    init {
        refreshInstalledStatus()
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            loadHotSkills()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // debounce
            doSearch(query)
        }
    }

    fun setSource(source: SkillSource?) {
        _uiState.update { it.copy(activeSource = source, isLoading = true) }
        val query = _uiState.value.searchQuery
        if (query.isBlank()) loadHotSkills() else {
            viewModelScope.launch { doSearch(query) }
        }
    }

    fun setSortType(sort: SortType) {
        _uiState.update { it.copy(sortType = sort) }
        // 立即对已有数据排序，不触发重新搜索
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
        _uiState.update {
            it.copy(
                installingIds = it.installingIds + skill.identifier,
                showInstallDialog = null
            )
        }
        viewModelScope.launch {
            val result = when (skill.source) {
                SkillSource.LOBEHUB -> storeService.installFromLobeHub(skill.identifier)
                SkillSource.SKILLS_SH -> {
                    val parts = skill.identifier.split("/")
                    if (parts.size >= 2) storeService.installFromSkillsSh(parts[0], parts[1])
                    else Result.failure(IllegalArgumentException("Invalid identifier"))
                }
                SkillSource.CLAWHUB -> storeService.installFromClawHub(skill.identifier)
            }
            _uiState.update { state ->
                state.copy(installingIds = state.installingIds - skill.identifier)
            }
            result.onSuccess {
                Timber.i("Skill installed: ${skill.name}")
                // 如果有 sourceAgentId，将技能同步到该智能体的工作区
                if (!sourceAgentId.isNullOrBlank() && workspaceManager != null) {
                    try {
                        val skillId = skill.identifier.substringAfterLast("@")
                            .substringAfterLast("/").replace(skillIdCleanupRegex, "_")
                        workspaceManager.syncAgentSkillJson(
                            sourceAgentId, skillId, skill.name, skill.description
                        )
                        Timber.i("Synced skill '$skillId' to agent '$sourceAgentId'")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to sync skill to agent workspace")
                    }
                }
                refreshInstalledStatus()
            }.onFailure { e ->
                Timber.e(e, "Failed to install skill: ${skill.name}")
                _uiState.update { it.copy(error = "安装失败: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun loadHotSkills() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val source = _uiState.value.activeSource
                val results = coroutineScope {
                    val deferred = mutableListOf<kotlinx.coroutines.Deferred<Result<List<StoreSkillItem>>>>()
                    if (source == null || source == SkillSource.LOBEHUB) {
                        deferred.add(async { storeService.searchLobeHub("popular", pageSize = 10) })
                    }
                    if (source == null || source == SkillSource.SKILLS_SH) {
                        deferred.add(async { storeService.searchSkillsSh("popular") })
                    }
                    if (source == null || source == SkillSource.CLAWHUB) {
                        deferred.add(async { storeService.searchClawHub("popular") })
                    }
                    deferred.awaitAll()
                }
                val linuxError = results.firstOrNull { it.exceptionOrNull() is com.lin.hippyagent.core.skill.store.LinuxNotReadyException }
                if (linuxError != null) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Linux 环境未初始化，技能商店需要 Linux 环境来执行搜索命令。请先在设置中完成初始化。")
                    }
                    return@launch
                }
                val all = results.mapNotNull { it.getOrNull() }.flatten().sortedByDescending { it.installCount }
                _uiState.update {
                    it.copy(
                        hotSkills = all.take(10),
                        skills = if (_uiState.value.searchQuery.isBlank()) all else it.skills,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load hot skills")
                _uiState.update { it.copy(isLoading = false, error = "加载失败: ${e.message}") }
            }
        }
    }

    private suspend fun doSearch(query: String) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            val results = coroutineScope {
                listOf(
                    async { storeService.searchLobeHub(query) },
                    async { storeService.searchSkillsSh(query) },
                    async { storeService.searchClawHub(query) }
                ).awaitAll()
            }
            results.forEach { it.onSuccess { items -> mergeResults(items) } }
        } catch (e: Exception) {
            Timber.e(e, "Search failed")
        } finally {
            _uiState.update {
                it.copy(
                    skills = sortSkills(it.skills, it.sortType),
                    isLoading = false
                )
            }
        }
    }

    private fun mergeResults(items: List<StoreSkillItem>) {
        _uiState.update { state ->
            val existing = state.skills.associateBy { it.identifier }
            val merged = existing.toMutableMap()
            items.forEach { merged[it.identifier] = it }
            state.copy(skills = merged.values.toList())
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
}

