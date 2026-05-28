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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.AgentProfile
import com.lin.hippyagent.ui.components.HippyTopBar

private data class SettingsGroup(
    val name: String,
    val icon: ImageVector,
    val items: List<SettingsItem>
)

private data class SettingsItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val onClickLabel: String = ""
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
            SettingsGroup("通用", Icons.Default.Language, listOf(
                SettingsItem(Icons.Default.Language, "语言", uiState.language, "语言"),
                SettingsItem(Icons.Default.Notifications, "通知", if (uiState.notificationsEnabled) "已开启" else "已关闭", "通知"),
                SettingsItem(Icons.Default.Palette, "界面设置", "不活跃对话时长等界面偏好", "界面设置"),
                SettingsItem(Icons.Default.Storage, "数据存储", uiState.storagePath, "数据存储"),
            )),
            SettingsGroup("模型与 AI", Icons.Default.SmartToy, listOf(
                SettingsItem(Icons.Default.Dns, "模型供应商", "管理 API Key 和模型", "模型供应商"),
                SettingsItem(Icons.Default.Description, "全局规则", "对所有智能体生效的通用规则", "全局规则"),
                SettingsItem(Icons.Default.SmartToy, "公共技能池", "管理智能体技能", "公共技能池"),
                SettingsItem(Icons.Default.Lightbulb, "公共记忆", "所有智能体的共享记忆", "公共记忆"),
            )),
            SettingsGroup("系统与权限", Icons.Default.Lock, listOf(
                SettingsItem(Icons.Default.Shield, "权限中心", "统一管理所有系统权限", "权限中心"),
                SettingsItem(Icons.Default.Dns, "无障碍设置", "配置手机自动化权限", "无障碍设置"),
                SettingsItem(Icons.Default.Terminal, "环境检测", "检测常用环境安装状态", "环境检测"),
                SettingsItem(Icons.Default.Storage, "环境变量", "管理全局环境变量", "环境变量"),
                SettingsItem(Icons.Default.Check, "工具审批", "审批工具执行请求", "工具审批"),
                SettingsItem(Icons.Default.Shield, "安全规则", "管理工具审批的「不再询问」规则", "安全规则"),
                SettingsItem(Icons.Default.Extension, "工具列表", "查看所有可用工具", "工具列表"),
                SettingsItem(Icons.Default.Extension, "插件管理", "管理已安装插件", "插件管理"),
                SettingsItem(Icons.Default.Timer, "事件监听", "管理系统事件 Hook 和静默时间", "事件监听"),
                SettingsItem(Icons.Default.Description, "日志", "", "日志"),
            )),
            SettingsGroup("关于", Icons.Default.Info, listOf(
                SettingsItem(Icons.Default.Info, "关于", "版本 ${uiState.appVersion}", "关于"),
                SettingsItem(Icons.Default.BugReport, "调试信息", "收集诊断数据", "调试信息"),
            )),
        )
    }

    fun onClick(label: String) {
        when (label) {
            "语言" -> onNavigateToLanguage()
            "通知" -> onNavigateToNotification()
            "界面设置" -> onNavigateToUiSettings()
            "全局规则" -> onNavigateToGlobalRules()
            "数据存储" -> onNavigateToDataStorage()
            "模型供应商" -> onNavigateToModelProvider()
            "公共技能池" -> onNavigateToSkillPool()
            "公共记忆" -> onNavigateToCommonMemory(null)
            "权限中心" -> onNavigateToPermissionCenter()
            "无障碍设置" -> onNavigateToAccessibilitySetup()
            "环境检测" -> onNavigateToEnvCheck()
            "环境变量" -> onNavigateToEnvVars()
            "日志" -> onNavigateToExportLog()
            "关于" -> onNavigateToAbout()
            "调试信息" -> onNavigateToDebugInfo()
            "工具审批" -> onNavigateToToolApprovals()
            "安全规则" -> onNavigateToSecurityRules()
            "工具列表" -> onNavigateToToolsList()
            "插件管理" -> onNavigateToPlugins()
            "事件监听" -> onNavigateToSystemHooks()
        }
    }

    val filteredGroups by remember {
        derivedStateOf {
            if (searchText.isBlank()) {
                groups
            } else {
                groups.mapNotNull { group ->
                    val matched = group.items.filter {
                        it.title.contains(searchText, ignoreCase = true) ||
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
                title = "设置",
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
                    placeholder = { Text("搜索设置…", fontSize = 14.sp) },
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
                val isExpanded = expandedGroups[group.name] ?: true
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
                                    .clickable { expandedGroups[group.name] = !isExpanded }
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
                                    group.name,
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
                                            .clickable { onClick(item.onClickLabel) }
                                            .padding(horizontal = 16.dp, vertical = 11.dp)
                                            .padding(start = 30.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                item.title,
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
