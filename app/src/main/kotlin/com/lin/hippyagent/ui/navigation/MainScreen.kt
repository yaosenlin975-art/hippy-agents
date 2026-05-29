package com.lin.hippyagent.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import androidx.navigation.NavController
import com.lin.hippyagent.ui.conversation.ConversationListScreen
import com.lin.hippyagent.ui.conversation.ConversationListViewModel
import com.lin.hippyagent.ui.settings.SettingsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    navController: NavController,
    coroutineScope: CoroutineScope,
    pagerState: PagerState,
    sessionsViewModel: ConversationListViewModel,
    lastActiveAgentId: String?,
    currentAgentId: String?,
    onCurrentAgentIdChanged: (String?) -> Unit,
    onNavigateToChat: (String, String) -> Unit,
    onNavigateToGroupChat: (String) -> Unit,
    onNavigateToCreateGroup: () -> Unit,
    onNavigateToCreateAgent: () -> Unit,
    onNavigateToAgentConfig: (String) -> Unit,
    showCreateGroupDialog: Boolean,
    onCreateGroupDialogDismiss: () -> Unit,
    onCreateGroup: (String, List<String>) -> Unit,
    agentRepository: com.lin.hippyagent.data.repository.AgentRepository,
    groupManager: com.lin.hippyagent.core.agent.collaboration.AgentGroupManager
) {
    var showCreateDrawer by remember { mutableStateOf(false) }
    var showCreateSessionGroupDialog by remember { mutableStateOf(false) }
    var newSessionGroupName by remember { mutableStateOf("") }

    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.height(56.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 2.dp,
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_sessions), fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                    icon = {
                        Box {
                            Icon(Icons.Default.Inbox, contentDescription = null)
                            val inboxVm: com.lin.hippyagent.ui.inbox.InboxViewModel = org.koin.androidx.compose.koinViewModel()
                            val unread by inboxVm.unreadCount.collectAsStateWithLifecycle()
                            if (unread > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error)
                                )
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.nav_inbox), fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(horizontal = 4.dp)
                ) {
                    FloatingActionButton(
                        onClick = { showCreateDrawer = true },
                        containerColor = Color(0xFFFF6D00),
                        contentColor = Color.White,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.nav_new),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                if (showCreateDrawer) {
                    com.lin.hippyagent.ui.components.CreateDrawer(
                        onNewChat = {
                            showCreateDrawer = false
                            coroutineScope.launch { pagerState.animateScrollToPage(0) }
                            sessionsViewModel.createNewSession { session ->
                                navController.navigate(Screen.Chat.createRoute(session.id, session.agentId))
                            }
                        },
                        onNewGroup = {
                            showCreateDrawer = false
                            onNavigateToCreateGroup()
                        },
                        onNewSessionGroup = {
                            showCreateDrawer = false
                            newSessionGroupName = ""
                            showCreateSessionGroupDialog = true
                        },
                        onNewAgent = {
                            showCreateDrawer = false
                            onNavigateToCreateAgent()
                        },
                        onDismiss = { showCreateDrawer = false }
                    )
                }
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } },
                    icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_insights), fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 3,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(3) } },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings), fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = true,
            modifier = Modifier.padding(innerPadding)
        ) { page ->
            val isNearCurrentPage = kotlin.math.abs(page - pagerState.currentPage) <= 1
            if (!isNearCurrentPage) {
                Box(modifier = Modifier.fillMaxSize())
            } else {
                when (page) {
                    0 -> ConversationListScreen(
                        viewModel = sessionsViewModel,
                        onNavigateToChat = onNavigateToChat,
                        onNavigateToCreateGroup = onNavigateToCreateGroup,
                        onNavigateToGroupChat = onNavigateToGroupChat,
                        onNavigateToAgentConfig = onNavigateToAgentConfig,
                        onNavigateToCreateAgent = onNavigateToCreateAgent,
                        onAgentSwitched = { agentId ->
                            onCurrentAgentIdChanged(agentId)
                        }
                    )
                    1 -> {
                        val inboxViewModel: com.lin.hippyagent.ui.inbox.InboxViewModel = org.koin.androidx.compose.koinViewModel()
                        com.lin.hippyagent.ui.inbox.InboxScreen(
                            viewModel = inboxViewModel,
                            onBackClick = {}
                        )
                    }
                    2 -> {
                        val insightsViewModel: com.lin.hippyagent.ui.insights.InsightsViewModel = org.koin.androidx.compose.koinViewModel()
                        com.lin.hippyagent.ui.insights.InsightsScreen(
                            viewModel = insightsViewModel,
                            onBackClick = {},
                            refreshTrigger = pagerState.currentPage == 2
                        )
                    }
                    3 -> SettingsScreen(
                        viewModel = org.koin.androidx.compose.koinViewModel(),
                        agentId = currentAgentId,
                        onNavigateToRunningConfig = { agentId -> navController.navigate(Screen.RunningConfig.createRoute(agentId)) },
                        onNavigateToHeartbeat = { agentId -> navController.navigate(Screen.Heartbeat.createRoute(agentId)) },
                        onNavigateToMCP = { agentId -> navController.navigate(Screen.MCP.createRoute(agentId)) },
                        onNavigateToCoreFiles = { agentId -> navController.navigate(Screen.CoreFiles.createRoute(agentId)) },
                        onNavigateToACP = { agentId -> navController.navigate(Screen.ACP.createRoute(agentId)) },
                        onNavigateToDream = { agentId -> navController.navigate(Screen.Dream.createRoute(agentId)) },
                        onNavigateToCronJobs = { agentId -> navController.navigate(Screen.CronJobs.createRoute(agentId)) },
                        onNavigateToStore = { navController.navigate(Screen.SkillStore.createRoute()) },
                        onNavigateToChannelConfig = { agentId ->
                            navController.navigate(Screen.ChannelConfig.createRoute(agentId))
                        },
                        onNavigateToModelProvider = { navController.navigate(Screen.ModelProvider.route) },
                        onNavigateToAbout = { navController.navigate(Screen.About.route) },
                        onNavigateToDebugInfo = { navController.navigate(Screen.DebugInfo.route) },
                        onNavigateToDataStorage = { navController.navigate(Screen.DataStorage.route) },
                        onNavigateToExportLog = { navController.navigate(Screen.ExportLog.route) },
                        onNavigateToLanguage = { navController.navigate(Screen.Language.route) },
                        onNavigateToNotification = { navController.navigate(Screen.Notification.route) },
                        onNavigateToToolSecurity = { agentId -> navController.navigate(Screen.AgentToolSecurity.createRoute(agentId)) },
                        onNavigateToAccessibilitySetup = { navController.navigate(Screen.AccessibilitySetup.route) },
                        onNavigateToEnvCheck = { navController.navigate(Screen.EnvCheck.route) },
                        onNavigateToEnvVars = { navController.navigate(Screen.EnvVars.route) },
                        onNavigateToSkillPool = { navController.navigate(Screen.SkillPool.route) },
                        onNavigateToUiSettings = { navController.navigate(Screen.UiSettings.route) },
                        onNavigateToGlobalRules = { navController.navigate(Screen.GlobalRules.route) },
                        onNavigateToPermissionCenter = { navController.navigate(Screen.PermissionCenter.route) },
                        onNavigateToCommonMemory = { agentId ->
                            navController.navigate(Screen.CommonMemory.createRoute(agentId ?: "all"))
                        },
                        onNavigateToToolApprovals = { navController.navigate(Screen.ToolApprovals.route) },
                        onNavigateToSecurityRules = { navController.navigate(Screen.SecurityRules.route) },
                        onNavigateToToolsList = { navController.navigate(Screen.ToolsList.route) },
                        onNavigateToPlugins = { navController.navigate(Screen.Plugins.route) },
                        onNavigateToSkillManagement = { agentId -> navController.navigate(Screen.SkillManagement.createRoute(agentId)) },
                        onNavigateToMemoryCompaction = { agentId -> navController.navigate(Screen.MemoryCompaction.createRoute(agentId)) },
                        onNavigateToSystemHooks = { navController.navigate(Screen.SystemHooks.route) },
                        onAgentSwitched = { agentId ->
                            onCurrentAgentIdChanged(agentId)
                            sessionsViewModel.switchAgent(agentId)
                        }
                    )
                }
            }
        }

        if (showCreateSessionGroupDialog) {
            AlertDialog(
                onDismissRequest = {
                    showCreateSessionGroupDialog = false
                    newSessionGroupName = ""
                },
                title = { Text(stringResource(R.string.conversation_new_group)) },
                text = {
                    TextField(
                        value = newSessionGroupName,
                        onValueChange = { newSessionGroupName = it },
                        placeholder = { Text(stringResource(R.string.conversation_group_name)) },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newSessionGroupName.isNotBlank()) {
                                sessionsViewModel.createSessionGroup(newSessionGroupName.trim())
                                newSessionGroupName = ""
                            }
                            showCreateSessionGroupDialog = false
                        },
                        enabled = newSessionGroupName.isNotBlank()
                    ) { Text(stringResource(R.string.common_create)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showCreateSessionGroupDialog = false
                        newSessionGroupName = ""
                    }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }

    if (showCreateGroupDialog) {
        com.lin.hippyagent.ui.group.CreateGroupDialog(
            agentRepository = agentRepository,
            onDismiss = onCreateGroupDialogDismiss,
            onCreateGroup = onCreateGroup
        )
    }
}
