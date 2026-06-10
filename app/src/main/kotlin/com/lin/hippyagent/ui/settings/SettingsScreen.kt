package com.lin.hippyagent.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.AgentProfile
import com.lin.hippyagent.ui.components.HippyTopBar

private data class SettingsGroup(
    val nameRes: Int,
    val icon: ImageVector,
    val items: List<SettingsItem>
)

private data class SettingsItem(
    val icon: ImageVector,
    val titleRes: Int,
    val subtitle: String? = null,
    val onClickKey: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToRunningConfig: (String) -> Unit,
    onNavigateToHeartbeat: (String) -> Unit,
    onNavigateToMCP: (String) -> Unit,
    onNavigateToCoreFiles: (String) -> Unit,
    onNavigateToACP: (String) -> Unit,
    onNavigateToModelProvider: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToDebugInfo: () -> Unit = {},
    onNavigateToDataStorage: () -> Unit = {},
    onNavigateToExportLog: () -> Unit = {},
    onNavigateToLanguage: () -> Unit = {},
    onNavigateToNotification: () -> Unit = {},
    onNavigateToToolSecurity: (String) -> Unit = {},
    onNavigateToAccessibilitySetup: () -> Unit = {},
    onNavigateToSkillPool: () -> Unit = {},
    onNavigateToEnvCheck: () -> Unit = {},
    onNavigateToEnvVars: () -> Unit = {},
    onNavigateToUiSettings: () -> Unit = {},
    onNavigateToGlobalRules: () -> Unit = {},
    onNavigateToPermissionCenter: () -> Unit = {},
    onNavigateToCommonMemory: (String?) -> Unit = {},
    agentId: String? = null,
    onNavigateToDream: (String) -> Unit = {},
    onNavigateToCronJobs: (String) -> Unit = {},
    onNavigateToStore: () -> Unit = {},
    onNavigateToChannelConfig: (String) -> Unit = {},
    onNavigateToToolApprovals: () -> Unit = {},
    onNavigateToSecurityRules: () -> Unit = {},
    onNavigateToToolsList: () -> Unit = {},
    onNavigateToPlugins: () -> Unit = {},
    onNavigateToSkillManagement: (String) -> Unit = {},
    onNavigateToMemoryCompaction: (String) -> Unit = {},
    onNavigateToSystemHooks: () -> Unit = {},
    onAgentSwitched: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var searchText by remember { mutableStateOf("") }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    // 从 AgentSelectionHolder 同步初始值（会话管理切换智能体后回到设置页时生效）
    val holderAgentId by com.lin.hippyagent.core.agent.AgentSelectionHolder.currentAgentId.collectAsStateWithLifecycle()
    var currentAgentId by remember(holderAgentId, agentId) {
        mutableStateOf(agentId ?: holderAgentId ?: uiState.agents.firstOrNull()?.agentId)
    }
    // 外部 agentId 或 AgentSelectionHolder 改变时同步
    LaunchedEffect(agentId, holderAgentId) {
        val newId = agentId ?: holderAgentId
        if (newId != null) currentAgentId = newId
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshSettings()
    }

    val groups = remember {
        listOf(
            SettingsGroup(R.string.settings_general, Icons.Default.Language, listOf(
                SettingsItem(Icons.Default.Language, R.string.language, uiState.language, R.string.language),
                SettingsItem(Icons.Default.Notifications, R.string.notifications, if (uiState.notificationsEnabled) context.getString(R.string.enabled) else context.getString(R.string.settings_closed), R.string.notifications),
                SettingsItem(Icons.Default.Palette, R.string.settings_ui_settings, context.getString(R.string.settings_ui_settings_desc), R.string.settings_ui_settings),
                SettingsItem(Icons.Default.Storage, R.string.data_storage, uiState.storagePath, R.string.data_storage),
            )),
            SettingsGroup(R.string.settings_model_and_ai, Icons.Default.SmartToy, listOf(
                SettingsItem(Icons.Default.Dns, R.string.model_provider, context.getString(R.string.settings_model_provider_desc_new), R.string.model_provider),
                SettingsItem(Icons.Default.Description, R.string.settings_global_rules, context.getString(R.string.settings_global_rules_desc), R.string.settings_global_rules),
                SettingsItem(Icons.Default.SmartToy, R.string.skill_pool_management, context.getString(R.string.settings_skill_pool_desc_new), R.string.skill_pool_management),
                SettingsItem(Icons.Default.Lightbulb, R.string.settings_common_memory, context.getString(R.string.settings_common_memory_desc), R.string.settings_common_memory),
            )),
            SettingsGroup(R.string.settings_system_and_permissions, Icons.Default.Lock, listOf(
                SettingsItem(Icons.Default.Shield, R.string.settings_permission_center, context.getString(R.string.settings_permission_center_desc), R.string.settings_permission_center),
                SettingsItem(Icons.Default.Dns, R.string.settings_accessibility_setup, context.getString(R.string.settings_accessibility_setup_desc), R.string.settings_accessibility_setup),
                SettingsItem(Icons.Default.Terminal, R.string.settings_env_check, context.getString(R.string.settings_env_check_desc), R.string.settings_env_check),
                SettingsItem(Icons.Default.Storage, R.string.settings_env_vars, context.getString(R.string.settings_env_vars_desc), R.string.settings_env_vars),
                SettingsItem(Icons.Default.Check, R.string.settings_tool_approvals, context.getString(R.string.settings_tool_approvals_desc), R.string.settings_tool_approvals),
                SettingsItem(Icons.Default.Shield, R.string.settings_security_rules, context.getString(R.string.settings_security_rules_desc), R.string.settings_security_rules),
                SettingsItem(Icons.Default.Extension, R.string.settings_tools_list, context.getString(R.string.settings_tools_list_desc), R.string.settings_tools_list),
                SettingsItem(Icons.Default.Extension, R.string.settings_plugins, context.getString(R.string.settings_plugins_desc), R.string.settings_plugins),
                SettingsItem(Icons.Default.Timer, R.string.settings_event_hooks, context.getString(R.string.settings_event_hooks_desc), R.string.settings_event_hooks),
                SettingsItem(Icons.Default.Description, R.string.settings_logs, "", R.string.settings_logs),
            )),
            SettingsGroup(R.string.about_section, Icons.Default.Info, listOf(
                SettingsItem(Icons.Default.Info, R.string.about, context.getString(R.string.settings_version_format, uiState.appVersion), R.string.about),
                SettingsItem(Icons.Default.BugReport, R.string.debug_info, context.getString(R.string.settings_debug_info_desc_new), R.string.debug_info),
            )),
        )
    }

    fun onClick(key: Int) {
        when (key) {
            R.string.language -> onNavigateToLanguage()
            R.string.notifications -> onNavigateToNotification()
            R.string.settings_ui_settings -> onNavigateToUiSettings()
            R.string.settings_global_rules -> onNavigateToGlobalRules()
            R.string.data_storage -> onNavigateToDataStorage()
            R.string.model_provider -> onNavigateToModelProvider()
            R.string.skill_pool_management -> onNavigateToSkillPool()
            R.string.settings_common_memory -> onNavigateToCommonMemory(null)
            R.string.settings_permission_center -> onNavigateToPermissionCenter()
            R.string.settings_accessibility_setup -> onNavigateToAccessibilitySetup()
            R.string.settings_env_check -> onNavigateToEnvCheck()
            R.string.settings_env_vars -> onNavigateToEnvVars()
            R.string.settings_logs -> onNavigateToExportLog()
            R.string.about -> onNavigateToAbout()
            R.string.debug_info -> onNavigateToDebugInfo()
            R.string.settings_tool_approvals -> onNavigateToToolApprovals()
            R.string.settings_security_rules -> onNavigateToSecurityRules()
            R.string.settings_tools_list -> onNavigateToToolsList()
            R.string.settings_plugins -> onNavigateToPlugins()
            R.string.settings_event_hooks -> onNavigateToSystemHooks()
        }
    }

    val filteredGroups by remember {
        derivedStateOf {
            if (searchText.isBlank()) {
                groups
            } else {
                groups.mapNotNull { group ->
                    val matched = group.items.filter {
                        context.getString(it.titleRes).contains(searchText, ignoreCase = true) ||
                        (it.subtitle?.contains(searchText, ignoreCase = true) ?: false)
                    }
                    if (matched.isNotEmpty()) group.copy(items = matched) else null
                }
            }
        }
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.settings),
                actions = {
                    Text(
                        text = uiState.appVersion,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.settings_search_hint), fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.outline) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                )
                Spacer(Modifier.height(12.dp))
        }

        if (currentAgentId != null) {
            item(key = "agent_config_${currentAgentId}") {
                val agentConfigViewModel: com.lin.hippyagent.ui.agent.AgentConfigViewModel = org.koin.androidx.compose.koinViewModel(
                    key = currentAgentId,
                    parameters = { org.koin.core.parameter.parametersOf(currentAgentId!!) }
                )
                AgentConfigSection(
                    viewModel = agentConfigViewModel,
                    agents = uiState.agents,
                    currentAgentId = currentAgentId!!,
                    onSwitchAgent = { newId ->
                        currentAgentId = newId
                        com.lin.hippyagent.core.agent.AgentSelectionHolder.setCurrentAgent(newId)
                        onAgentSwitched(newId)
                    },
                    onDeleteAgent = { deletedId ->
                        if (uiState.agents.size > 1) {
                            val idx = uiState.agents.indexOfFirst { it.agentId == deletedId }
                            val nextIdx = if (idx > 0) idx - 1 else (idx + 1).coerceAtMost(uiState.agents.size - 1)
                            if (nextIdx != idx) {
                                currentAgentId = uiState.agents[nextIdx].agentId
                            }
                        }
                        viewModel.refreshAgents()
                    },
                    onNavigateToRunningConfig = onNavigateToRunningConfig,
                    onNavigateToHeartbeat = onNavigateToHeartbeat,
                    onNavigateToDream = onNavigateToDream,
                    onNavigateToMCP = onNavigateToMCP,
                    onNavigateToACP = onNavigateToACP,
                    onNavigateToCronJobs = onNavigateToCronJobs,
                    onNavigateToStore = onNavigateToStore,
                    onNavigateToChannelConfig = onNavigateToChannelConfig,
                    onNavigateToToolSecurity = onNavigateToToolSecurity,
                    onNavigateToCommonMemory = onNavigateToCommonMemory,
                    onNavigateToModelProvider = onNavigateToModelProvider
                )
            }
        }

        filteredGroups.forEach { group ->
                val isExpanded = expandedGroups[group.nameRes.toString()] ?: true
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedGroups[group.nameRes.toString()] = !isExpanded }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    group.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(group.nameRes),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    if (isExpanded) Icons.Default.KeyboardArrowDown
                                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (isExpanded) {
                                group.items.forEachIndexed { index, item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onClick(item.onClickKey) }
                                            .padding(horizontal = 16.dp, vertical = 11.dp)
                                            .padding(start = 30.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                stringResource(item.titleRes),
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (!item.subtitle.isNullOrBlank()) {
                                                Text(
                                                    item.subtitle,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }
                                        }
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}
