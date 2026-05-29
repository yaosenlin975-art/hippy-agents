package com.lin.hippyagent.ui.conversation

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.AgentProfile
import com.lin.hippyagent.core.agent.AgentStatus
import com.lin.hippyagent.core.agent.collaboration.GroupInfo
import com.lin.hippyagent.core.agent.collaboration.GroupRegistry
import com.lin.hippyagent.core.agent.session.Session
import com.lin.hippyagent.core.agent.session.SessionGroupEntity
import com.lin.hippyagent.core.agent.session.SessionGroupDao
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.agent.session.UnreadSummary
import com.lin.hippyagent.data.repository.AgentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class ConversationListUiState(
    val sessions: List<Session> = emptyList(),
    val allSessions: List<Session> = emptyList(),
    val groups: List<GroupInfo> = emptyList(),
    val allGroups: List<GroupInfo> = emptyList(),
    val sessionGroups: List<SessionGroupEntity> = emptyList(),
    val collapsedGroups: Set<String> = emptySet(),
    val agents: Map<String, AgentProfile> = emptyMap(),
    val sessionStatuses: Map<String, AgentStatus> = emptyMap(),
    val currentAgentId: String? = null,
    val currentAgent: AgentProfile? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val unreadSummary: UnreadSummary = UnreadSummary(0, false, emptyMap()),
    /** 不活跃对话阈值（分钟），0 表示禁用折叠 */
    val inactiveThresholdMinutes: Int = 60
)

class ConversationListViewModel(
    private val context: Context,
    private val sessionStore: SessionStore,
    private val agentRepository: AgentRepository,
    private val agentFactory: AgentFactory,
    private val groupRegistry: GroupRegistry,
    private val sessionGroupDao: SessionGroupDao,
    private val notificationService: com.lin.hippyagent.core.notification.HippyAgentNotificationService? = null,
    private val prefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationListUiState(
        inactiveThresholdMinutes = prefs.getInt("inactive_threshold_minutes", 60)
    ))
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    /** 监听不活跃阈值变化，即使从其他页面修改也能即时刷新 */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "inactive_threshold_minutes") {
            val newThreshold = prefs.getInt("inactive_threshold_minutes", 60)
            _uiState.update { it.copy(inactiveThresholdMinutes = newThreshold) }
        }
    }

    private val observedAgents = mutableSetOf<String>()

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        loadSessions()
        observeAgentStatuses()
        observeSessionsFlow()
        observeGroups()
        observeUnreadSummary()
        observeSessionGroups()
        // 监听 AgentSelectionHolder 外部变更（例如创建智能体或在设置页切换智能体）
        viewModelScope.launch {
            com.lin.hippyagent.core.agent.AgentSelectionHolder.currentAgentId.collect { agentId ->
                if (agentId != null && agentId != _uiState.value.currentAgentId) {
                    switchAgent(agentId)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun observeGroups() {
        viewModelScope.launch {
            groupRegistry.groupsFlow.collect { groups ->
                val currentAgentId = _uiState.value.currentAgentId
                val filteredGroups = if (currentAgentId != null) {
                    groups.filter { currentAgentId in it.agentIds }
                } else {
                    groups
                }
                val sorted = sortGroupsByLastUpdated(filteredGroups, _uiState.value.sessions)
                _uiState.update { it.copy(groups = sorted, allGroups = sorted) }
            }
        }
    }

    private fun observeSessionsFlow() {
        viewModelScope.launch {
            combine(
                sessionStore.observeSessions(),
                _uiState.map { it.currentAgentId }.distinctUntilChanged()
            ) { sessions, currentAgentId ->
                if (currentAgentId != null) {
                    sessions.filter { it.agentId == currentAgentId || it.agentId == "group" }
                } else {
                    sessions
                }
            }.collect { filtered ->
                val sortedGroups = sortGroupsByLastUpdated(_uiState.value.groups, filtered)
                _uiState.update { it.copy(sessions = filtered, allSessions = filtered, groups = sortedGroups, allGroups = sortedGroups, isLoading = false) }
            }
        }
    }

    private fun observeUnreadSummary() {
        viewModelScope.launch {
            sessionStore.observeUnreadSummary().collect { summary ->
                _uiState.update { it.copy(unreadSummary = summary) }
            }
        }
    }

    private fun observeAgentStatuses() {
        viewModelScope.launch {
            agentRepository.getProfiles().collect { profiles ->
                _uiState.update {
                    it.copy(
                        agents = profiles,
                        currentAgent = it.currentAgentId?.let { id -> profiles[id] }
                    )
                }
                profiles.keys.forEach { agentId ->
                    if (agentId !in observedAgents) {
                        observedAgents.add(agentId)
                        launch {
                            val agent = agentFactory.getAgent(agentId)
                            if (agent != null) {
                                agent.state.collect { agentState ->
                                    val newSessionStatuses = agentState.sessionStates
                                        .filter { it.value.status != AgentStatus.IDLE }
                                        .mapValues { it.value.status }
                                    _uiState.update {
                                        it.copy(
                                            sessionStatuses = it.sessionStatuses - agentState.sessionStates.keys + newSessionStatuses
                                        )
                                    }
                                }
                            } else {
                                observedAgents.remove(agentId)
                            }
                        }
                    }
                }
            }
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val agents = agentRepository.loadAgentProfiles().first()
                // 优先选择已启用的智能体：当前智能体若已禁用则自动切换到第一个启用的
                val enabledAgentIds = agents.filter { it.value.enabled }.keys.toList()
                val currentAgentId = when {
                    _uiState.value.currentAgentId != null && agents[_uiState.value.currentAgentId]?.enabled == true ->
                        _uiState.value.currentAgentId
                    enabledAgentIds.isNotEmpty() -> enabledAgentIds.firstOrNull()
                    else -> agents.keys.firstOrNull() // 全部禁用时仍保留第一个（UI 层会提示）
                }
                val currentAgent = currentAgentId?.let { agents[it] }

                _uiState.update {
                    it.copy(
                        agents = agents,
                        currentAgentId = currentAgentId,
                        currentAgent = currentAgent
                    )
                }

                sessionStore.getAllSessions()
                    .onSuccess { allSessions ->
                        val filteredSessions = if (currentAgentId != null) {
                            // 始终显示默认智能体 hippy 的会话，无论当前选中哪个智能体
                            allSessions.filter { it.agentId == currentAgentId || it.agentId == "group" }
                        } else {
                            allSessions
                        }

                        val allGroups = groupRegistry.listGroups()
                        val filteredGroups = if (currentAgentId != null) {
                            allGroups.filter { currentAgentId in it.agentIds }
                        } else {
                            allGroups
                        }
                        val sortedGroups = sortGroupsByLastUpdated(filteredGroups, filteredSessions)

                        _uiState.update {
                            it.copy(
                                sessions = filteredSessions,
                                allSessions = filteredSessions,
                                groups = sortedGroups,
                                allGroups = sortedGroups,
                                isLoading = false
                            )
                        }
                    }
                    .onFailure { e ->
                        Timber.e(e, "Failed to load sessions")
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = e.message)
                        }
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load sessions")
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun searchSessions(query: String) {
        val allSessions = _uiState.value.allSessions
        if (query.isEmpty()) {
            _uiState.update { it.copy(sessions = allSessions) }
            return
        }
        val filtered = allSessions.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.lastMessage?.contains(query, ignoreCase = true) == true
        }
        _uiState.update { it.copy(sessions = filtered) }
    }

    fun switchAgent(agentId: String) {
        val agent = _uiState.value.agents[agentId]
        _uiState.update {
            it.copy(currentAgentId = agentId, currentAgent = agent)
        }
        com.lin.hippyagent.core.agent.AgentSelectionHolder.setCurrentAgent(agentId)
        loadSessions()
    }

    /** 切换智能体启用/禁用状态 */
    fun toggleAgentEnabled(agentId: String, enabled: Boolean) {
        viewModelScope.launch {
            val agents = _uiState.value.agents.toMutableMap()
            agents[agentId]?.let { profile ->
                val updated = profile.copy(enabled = enabled)
                agents[agentId] = updated
                agentRepository.saveAgentProfile(updated)
                agentFactory.reloadAgent(agentId)
                _uiState.update {
                    it.copy(
                        agents = agents,
                        currentAgent = if (it.currentAgentId == agentId) updated else it.currentAgent
                    )
                }
            }
        }
    }

    fun createNewSession(onCreated: (Session) -> Unit = {}) {
        viewModelScope.launch {
            val agentId = _uiState.value.currentAgentId
                ?: agentRepository.getProfiles().first().keys.firstOrNull()
                ?: AgentRepository.DEFAULT_AGENT_ID

            sessionStore.createSession(agentId, context.getString(R.string.chat_new_session))
                .onSuccess { session ->
                    onCreated(session)
                }
                .onFailure { e ->
                    Timber.e(e, "Failed to create session")
                }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            // 使用级联删除，同时删除关联的 chat_with_agent 私聊会话
            sessionStore.deleteSessionWithPrivateChats(sessionId)
                .onFailure { Timber.e(it, "Failed to delete session") }
        }
    }

    fun pinSession(sessionId: String, pinned: Boolean) {
        viewModelScope.launch {
            sessionStore.pinSession(sessionId, pinned)
                .onFailure { Timber.e(it, "Failed to pin session") }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            sessionStore.updateSessionTitle(sessionId, newTitle)
                .onFailure { Timber.e(it, "Failed to rename session") }
        }
    }

    fun setMuted(sessionId: String, muted: Boolean) {
        viewModelScope.launch {
            sessionStore.setMuted(sessionId, muted)
                .onFailure { Timber.e(it, "Failed to set muted") }
        }
    }

    fun markAsRead(sessionId: String) {
        viewModelScope.launch {
            sessionStore.resetUnread(sessionId)
                .onFailure { Timber.e(it, "Failed to mark as read") }

            // 同步消除该会话对应的系统通知
            notificationService?.cancelNotification(
                com.lin.hippyagent.core.notification.HippyAgentNotificationService.NOTIFICATION_AGENT_MESSAGE + sessionId.hashCode()
            )
        }
    }

    /** 更新不活跃对话折叠阈值（分钟），0 = 禁用折叠 */
    fun setInactiveThreshold(minutes: Int) {
        prefs.edit().putInt("inactive_threshold_minutes", minutes).apply()
        _uiState.update { it.copy(inactiveThresholdMinutes = minutes) }
    }

    private fun observeSessionGroups() {
        viewModelScope.launch {
            val groups = sessionGroupDao.getAll()
            val collapsed = groups.filter { it.isCollapsed }.map { it.id }.toSet()
            _uiState.update { it.copy(sessionGroups = groups, collapsedGroups = collapsed) }
        }
    }

    fun toggleGroupCollapsed(groupId: String) {
        viewModelScope.launch {
            val isCollapsed = groupId in _uiState.value.collapsedGroups
            val newCollapsed = if (isCollapsed) {
                _uiState.value.collapsedGroups - groupId
            } else {
                _uiState.value.collapsedGroups + groupId
            }
            sessionGroupDao.updateCollapsed(groupId, !isCollapsed)
            _uiState.update { it.copy(collapsedGroups = newCollapsed) }
        }
    }

    fun createSessionGroup(name: String) {
        viewModelScope.launch {
            val agentId = _uiState.value.currentAgentId ?: AgentRepository.DEFAULT_AGENT_ID
            val id = com.lin.hippyagent.core.pool.FastId.next()
            val sortOrder = _uiState.value.sessionGroups.size
            val group = SessionGroupEntity(id = id, agentId = agentId, name = name, sortOrder = sortOrder)
            sessionGroupDao.insert(group)
            observeSessionGroups()
        }
    }

    fun deleteSessionGroup(groupId: String) {
        viewModelScope.launch {
            sessionGroupDao.deleteById(groupId)
            observeSessionGroups()
        }
    }

    fun moveSessionToGroup(sessionId: String, groupId: String?) {
        viewModelScope.launch {
            sessionGroupDao.updateSessionGroup(sessionId, groupId)
            loadSessions()
        }
    }

    fun renameSessionGroup(groupId: String, newName: String) {
        viewModelScope.launch {
            val group = _uiState.value.sessionGroups.find { it.id == groupId } ?: return@launch
            sessionGroupDao.update(group.copy(name = newName))
            observeSessionGroups()
        }
    }

    private fun sortGroupsByLastUpdated(groups: List<GroupInfo>, sessions: List<Session>): List<GroupInfo> {
        val sessionMap = sessions.associateBy { it.id }
        return groups.sortedByDescending { sessionMap[it.groupId]?.lastUpdatedAt }
    }
}

