package com.lin.hippyagent.ui.agent

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.AgentProfile
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.data.repository.AgentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import timber.log.Timber
import java.io.File

@Immutable
data class AgentConfigUiState(
    val agent: AgentProfile? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isEditing: Boolean = false,
    val availableModels: List<Triple<String, String, String>> = emptyList(),
    val providerNames: Map<String, String> = emptyMap(),
    val workspaceMdFiles: List<String> = emptyList()
)

class AgentConfigViewModel(
    private val repository: AgentRepository,
    private val sessionStore: SessionStore,
    private val modelProviderStore: ModelProviderStore,
    private val application: Application,
    private val agentId: String,
    private val agentFactory: com.lin.hippyagent.core.agent.AgentFactory? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentConfigUiState())
    val uiState: StateFlow<AgentConfigUiState> = _uiState.asStateFlow()

    private var autoSaveJob: Job? = null

    init {
        loadAgent()
        loadAvailableModels()
        // loadWorkspaceMdFiles() 由 loadAgent() 成功后调用，避免并发读空 agent
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(500)
            val agent = _uiState.value.agent ?: return@launch
            repository.saveAgentProfile(agent)
                .onSuccess {
                    _uiState.update { it.copy(isEditing = false) }
                    Timber.d("Auto-saved agent: $agentId")
                    agentFactory?.reloadAgent(agentId)
                }
                .onFailure { e ->
                    Timber.e(e, "Auto-save failed for agent: $agentId")
                }
        }
    }

    fun loadAgent() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val profiles = repository.loadAgentProfiles().first()
                val agent = profiles[agentId]
                if (agent != null) {
                    val workspaceMdFiles = loadWorkspaceMdFilesSync(agent)
                    _uiState.update {
                        it.copy(agent = agent, isLoading = false, isEditing = false, workspaceMdFiles = workspaceMdFiles)
                    }
                } else if (agentId == "new") {
                    val coreKit = listOf(
                        "read_file", "write_file", "edit_file", "append_file", "delete_file",
                        "glob_search", "grep_search", "list_directory",
                        "memory_search", "web_fetch", "web_search",
                        "get_current_time", "ask", "tool_search"
                    )
                    val newAgent = AgentProfile(
                        agentId = "agent_${System.currentTimeMillis()}",
                        name = "",
                        modelName = "",
                        defaultToolKit = coreKit,
                        modelProvider = "",
                        isDefault = false,
                        enabled = true
                    )
                    _uiState.update {
                        it.copy(agent = newAgent, isLoading = false, isEditing = true)
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "智能体不存在: $agentId")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load agent: $agentId")
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "加载失败: ${e.message}")
                }
            }
        }
    }

    private fun loadAvailableModels() {
        viewModelScope.launch {
            try {
                val providers = modelProviderStore.providers.first()
                val models = providers.filter { it.enabled }.flatMap { provider ->
                    provider.models.filter { it.enabled }.map { model ->
                        Triple(model.name, provider.id, provider.name)
                    }
                }
                val providerNameMap = providers.associate { it.id to it.name }
                _uiState.update { it.copy(availableModels = models, providerNames = providerNameMap) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load models")
            }
        }
    }

    fun refreshAvailableModels() {
        loadAvailableModels()
    }

    private suspend fun loadWorkspaceMdFilesSync(agent: AgentProfile): List<String> = withContext(Dispatchers.IO) {
        val mdFiles = mutableSetOf<String>()
        try {
            val agentWorkspaceDir = repository.getAgentWorkspaceDir(agentId)
            if (agentWorkspaceDir.exists()) {
                agentWorkspaceDir.listFiles()
                    ?.filter { it.isFile && it.extension == "md" }
                    ?.forEach { mdFiles.add(it.name) }
                val memoryDir = File(agentWorkspaceDir, "memory")
                if (memoryDir.exists()) {
                    memoryDir.listFiles()
                        ?.filter { it.isFile && it.extension == "md" }
                        ?.forEach { mdFiles.add("memory/${it.name}") }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to scan workspace md files")
        }
        // 只添加实际存在的 CoreFileType 文件（被删除的不显示）
        val workspaceDir = repository.getAgentWorkspaceDir(agentId)
        com.lin.hippyagent.core.agent.config.CoreFileType.entries.forEach { cft ->
            val f = File(workspaceDir, cft.filename)
            if (f.exists() || cft.filename in agent.coreFiles) {
                mdFiles.add(cft.filename)
            }
        }
        mdFiles.addAll(agent.coreFiles)
        mdFiles.sorted().distinct()
    }

    /** @deprecated 使用 loadWorkspaceMdFilesSync 替代，已内联到 loadAgent 流程中 */
    @Deprecated("Use loadWorkspaceMdFilesSync instead", ReplaceWith("loadWorkspaceMdFilesSync(agent)"))
    private fun loadWorkspaceMdFiles() {
        viewModelScope.launch {
            val currentAgent = _uiState.value.agent ?: return@launch
            val result = loadWorkspaceMdFilesSync(currentAgent)
            _uiState.update { it.copy(workspaceMdFiles = result) }
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(agent = it.agent?.copy(name = name), isEditing = true) }
        scheduleAutoSave()
        syncNameToProfileMd(name)
    }

    private fun syncNameToProfileMd(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            syncNameToProfileMdSync(agentId, name)
        }
    }

    /** 同步版本，用于 createAgent 中确保 PROFILE.md 更新在导航前完成 */
    private suspend fun syncNameToProfileMdSync(agentId: String, name: String) {
        if (name.isBlank()) return
        repository.readCoreFile(agentId, "PROFILE.md")
            .onSuccess { content ->
                val nameRegex = Regex("""(- \*\*名字[：:]\*\*\s*)(.+)""")
                val updated = if (nameRegex.containsMatchIn(content)) {
                    nameRegex.replace(content) { match ->
                        "${match.groupValues[1]}$name"
                    }
                } else {
                    val nameLine = "- **名字**: $name\n"
                    if (content.isNotBlank()) nameLine + content else nameLine
                }
                repository.writeCoreFile(agentId, "PROFILE.md", updated)
            }
    }

    fun createAgent(name: String, onCreated: (String) -> Unit) {
        val currentAgent = _uiState.value.agent ?: return
        val effectiveName = name.ifBlank { "新建智能体" }
        val newAgent = currentAgent.copy(name = effectiveName)
        viewModelScope.launch {
            repository.saveAgentProfile(newAgent)
            agentFactory?.reloadAgent(newAgent.agentId)
            syncNameToProfileMdSync(newAgent.agentId, effectiveName)
            val selectedSkills = _initialSkills
            if (selectedSkills.isNotEmpty()) {
                repository.saveSkills(newAgent.agentId, selectedSkills)
            }
            val disabledTools = _initialDisabledTools
            if (disabledTools.isNotEmpty()) {
                val toolRegistry: com.lin.hippyagent.core.tools.ToolRegistry? = try {
                    org.koin.java.KoinJavaComponent.getKoin().get()
                } catch (_: Exception) { null }
                toolRegistry?.setAgentToolAccess(newAgent.agentId, disabledTools.toSet())
            }
            onCreated(newAgent.agentId)
        }
    }

    /** 创建时选择的初始技能，在调用 createAgent 前设置 */
    private var _initialSkills: List<String> = emptyList()
    fun setInitialSkills(skills: List<String>) { _initialSkills = skills }

    /** 创建时选择的初始禁用工具，在调用 createAgent 前设置 */
    private var _initialDisabledTools: List<String> = emptyList()
    fun setInitialDisabledTools(tools: List<String>) { _initialDisabledTools = tools }

    /** 获取可用技能列表（名称列表，供 UI 展示） */
    fun getAvailableSkills(): List<String> {
        return try {
            val skillManager: com.lin.hippyagent.core.skill.SkillManager =
                org.koin.java.KoinJavaComponent.getKoin().get()
            skillManager.listSkills().map { it.displayName.ifEmpty { it.name } }
        } catch (_: Exception) { emptyList() }
    }

    /** 获取可用工具名称列表（供 UI 展示） */
    fun getAvailableToolNames(): List<String> {
        return try {
            val toolRegistry: com.lin.hippyagent.core.tools.ToolRegistry =
                org.koin.java.KoinJavaComponent.getKoin().get()
            toolRegistry.getAllDefinitions().map { it.name }.sorted()
        } catch (_: Exception) { emptyList() }
    }

    /** 获取工具定义列表，含中文名 */
    fun getToolDefinitions(): List<com.lin.hippyagent.core.tools.ToolDefinition> {
        return try {
            val toolRegistry: com.lin.hippyagent.core.tools.ToolRegistry =
                org.koin.java.KoinJavaComponent.getKoin().get()
            toolRegistry.getAllDefinitions().sortedBy { it.name }
        } catch (_: Exception) { emptyList() }
    }

    fun updateAvatarUrl(avatarUrl: String) {
        _uiState.update { it.copy(agent = it.agent?.copy(avatarUrl = avatarUrl), isEditing = true) }
        scheduleAutoSave()
    }

    fun saveAvatarToInternalStorage(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val persistentPath = withContext(Dispatchers.IO) {
                    val avatarsDir = File(application.filesDir, "avatars")
                    avatarsDir.mkdirs()
                    val outputFile = File(avatarsDir, "${agentId}_avatar.jpg")
                    application.contentResolver.openInputStream(uri)?.use { input ->
                        outputFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    outputFile.absolutePath
                }
                updateAvatarUrl(persistentPath)
                onResult(persistentPath)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save avatar to internal storage")
                _uiState.update { it.copy(errorMessage = "保存头像失败: ${e.message}") }
            }
        }
    }

    fun updateEnabled(enabled: Boolean) {
        _uiState.update { it.copy(agent = it.agent?.copy(enabled = enabled), isEditing = true) }
        scheduleAutoSave()
    }

    fun updateModel(modelName: String, modelProvider: String) {
        _uiState.update {
            it.copy(
                agent = it.agent?.copy(modelName = modelName, modelProvider = modelProvider),
                isEditing = true
            )
        }
        scheduleAutoSave()
    }

    fun updateModelName(modelName: String) {
        _uiState.update { it.copy(agent = it.agent?.copy(modelName = modelName), isEditing = true) }
        scheduleAutoSave()
    }

    fun updateModelProvider(modelProvider: String) {
        _uiState.update { it.copy(agent = it.agent?.copy(modelProvider = modelProvider), isEditing = true) }
        scheduleAutoSave()
    }

    fun updateFallbackModel(modelName: String, modelProvider: String) {
        _uiState.update { it.copy(agent = it.agent?.copy(fallbackModelName = modelName, fallbackModelProvider = modelProvider), isEditing = true) }
        scheduleAutoSave()
    }

    fun updateComplexModel(modelName: String, modelProvider: String) {
        _uiState.update { it.copy(agent = it.agent?.copy(complexModelName = modelName, complexModelProvider = modelProvider), isEditing = true) }
        scheduleAutoSave()
    }

    fun updateDecisionModel(modelName: String, modelProvider: String) {
        _uiState.update { it.copy(agent = it.agent?.copy(decisionModelName = modelName, decisionModelProvider = modelProvider), isEditing = true) }
        scheduleAutoSave()
    }

    fun updateSkills(skills: List<String>) {
        _uiState.update { it.copy(agent = it.agent?.copy(skills = skills), isEditing = true) }
        scheduleAutoSave()
    }

    fun toggleSkillEnabled(skillId: String, enabled: Boolean) {
        val agent = _uiState.value.agent ?: return
        val disabled = agent.disabledSkills.toMutableList()
        if (enabled) {
            disabled.remove(skillId)
        } else {
            if (skillId !in disabled) disabled.add(skillId)
        }
        _uiState.update { it.copy(agent = agent.copy(disabledSkills = disabled), isEditing = true) }
        scheduleAutoSave()
    }

    fun toggleToolEnabled(toolName: String, enabled: Boolean) {
        val agent = _uiState.value.agent ?: return
        val disabled = agent.disabledTools.toMutableList()
        if (enabled) {
            disabled.remove(toolName)
        } else {
            if (toolName !in disabled) disabled.add(toolName)
        }
        _uiState.update { it.copy(agent = agent.copy(disabledTools = disabled), isEditing = true) }
        scheduleAutoSave()
    }

    /** 切换 Agent 的工具包模式（null = 全部工具可见） */
    fun toggleToolKitMode(useKit: Boolean) {
        val agent = _uiState.value.agent ?: return
        val updated = if (!useKit) {
            agent.copy(defaultToolKit = null)
        } else {
            // 当前工具包或默认核心包
            val kit = agent.defaultToolKit ?: listOf(
                "read_file", "write_file", "edit_file", "append_file", "delete_file",
                "glob_search", "grep_search", "list_directory",
                "memory_search", "web_fetch", "web_search",
                "get_current_time", "ask", "tool_search"
            )
            agent.copy(defaultToolKit = kit)
        }
        _uiState.update { it.copy(agent = updated, isEditing = true) }
        scheduleAutoSave()
    }

    /** 在工具包中添加/移除工具 */
    fun toggleToolInKit(toolName: String, included: Boolean) {
        val agent = _uiState.value.agent ?: return
        val kit = (agent.defaultToolKit ?: return).toMutableList()
        if (included) {
            if (toolName !in kit) kit.add(toolName)
        } else {
            kit.remove(toolName)
        }
        _uiState.update { it.copy(agent = agent.copy(defaultToolKit = kit), isEditing = true) }
        scheduleAutoSave()
    }

    fun updateCoreFiles(coreFiles: List<String>) {
        _uiState.update { it.copy(agent = it.agent?.copy(coreFiles = coreFiles), isEditing = true) }
        scheduleAutoSave()
    }

    fun updateMaxIters(value: Int) {
        _uiState.update {
            it.copy(
                agent = it.agent?.copy(running = it.agent!!.running.copy(maxIters = value)),
                isEditing = true
            )
        }
        scheduleAutoSave()
    }

    fun updateMaxInputLength(value: Int) {
        _uiState.update {
            it.copy(
                agent = it.agent?.copy(running = it.agent!!.running.copy(maxInputLength = value)),
                isEditing = true
            )
        }
        scheduleAutoSave()
    }

    fun loadCoreFileContent(filename: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            repository.readCoreFile(agentId, filename)
                .onSuccess { onResult(it) }
                .onFailure { Timber.e(it, "Failed to read core file: $filename") }
        }
    }

    fun saveCoreFile(filename: String, content: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.writeCoreFile(agentId, filename, content)
                .onSuccess { onDone() }
                .onFailure { e ->
                    Timber.e(e, "Failed to save core file: $filename")
                    _uiState.update { it.copy(errorMessage = "保存失败: ${e.message}") }
                }
        }
    }

    fun deleteCoreFile(filename: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                val workspaceDir = File(application.filesDir, "workspaces/$agentId")
                val file = if (filename.startsWith("memory/")) {
                    File(workspaceDir, filename)
                } else {
                    File(workspaceDir, filename)
                }
                if (file.exists()) {
                    file.delete()
                }
                val agent = _uiState.value.agent
                if (agent != null && agent.coreFiles.contains(filename)) {
                    val updatedCoreFiles = agent.coreFiles.filter { it != filename }
                    _uiState.update { it.copy(agent = agent.copy(coreFiles = updatedCoreFiles)) }
                    scheduleAutoSave()
                }
                loadWorkspaceMdFiles()
                onDone()
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete core file: $filename")
                _uiState.update { it.copy(errorMessage = "删除失败: ${e.message}") }
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val agent = _uiState.value.agent ?: return
        viewModelScope.launch {
            repository.saveAgentProfile(agent)
                .onSuccess {
                    _uiState.update { it.copy(isEditing = false) }
                    agentFactory?.reloadAgent(agentId)
                    onSaved()
                }
                .onFailure { e ->
                    Timber.e(e, "Failed to save agent: $agentId")
                    _uiState.update { it.copy(errorMessage = "保存失败: ${e.message}") }
                }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                val result = sessionStore.getSessionsForAgent(agentId)
                result.getOrNull()?.forEach { session ->
                    sessionStore.deleteSession(session.id)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete sessions for agent: $agentId")
            }
            repository.deleteAgentProfile(agentId)
                .onSuccess { onDeleted() }
                .onFailure { e ->
                    Timber.e(e, "Failed to delete agent: $agentId")
                    _uiState.update { it.copy(errorMessage = "删除失败: ${e.message}") }
                }
        }
    }
}
