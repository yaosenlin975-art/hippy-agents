package com.lin.hippyagent.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import com.lin.hippyagent.ui.OnboardingScreen
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lin.hippyagent.R
import com.lin.hippyagent.core.storage.PreviousDataScanner
import com.lin.hippyagent.core.storage.PreviousDataLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    deepLinkSessionId: String? = null
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    var lastActiveAgentId by remember { mutableStateOf<String?>(null) }

    remember {
        deepLinkSessionId?.let { sessionId ->
            navController.navigate(Screen.Chat.createRoute(sessionId, com.lin.hippyagent.data.repository.AgentRepository.DEFAULT_AGENT_ID))
        }
    }

    val tabRoutes = listOf(Screen.Sessions.route, Screen.Settings.route)
    val pagerState = rememberPagerState(initialPage = 0) { tabRoutes.size }
    val coroutineScope = rememberCoroutineScope()

    val linuxManager: com.lin.hippyagent.core.linux.LinuxManager = org.koin.compose.koinInject()

    var showCreateGroupDialog by remember { mutableStateOf(false) }
    val agentRepository: com.lin.hippyagent.data.repository.AgentRepository = org.koin.compose.koinInject()
    val groupManager: com.lin.hippyagent.core.agent.collaboration.AgentGroupManager = org.koin.compose.koinInject()

    val previousDataScanner: PreviousDataScanner = remember { PreviousDataScanner(navController.context) }
    var previousDataLocations by remember { mutableStateOf<List<PreviousDataLocation>>(emptyList()) }
    var showPreviousDataDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!previousDataScanner.hasScanned()) {
            val locations = withContext(Dispatchers.IO) {
                previousDataScanner.scanForPreviousData()
            }
            if (locations.isNotEmpty()) {
                previousDataLocations = locations
                showPreviousDataDialog = true
            }
            previousDataScanner.markScanned()
        }
    }

    if (showPreviousDataDialog && previousDataLocations.isNotEmpty()) {
        PreviousDataDialog(
            locations = previousDataLocations,
            onUseData = { location, mode ->
                showPreviousDataDialog = false
                val ctx = navController.context
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        val workingDir = ctx.filesDir
                        val sourceDir = File(location.path)
                        when (mode) {
                            DataMergeMode.KEEP_CURRENT -> {}
                            DataMergeMode.MERGE -> {
                                mergeDataDir(sourceDir, workingDir)
                            }
                            DataMergeMode.KEEP_HISTORY -> {
                                if (workingDir.exists()) workingDir.deleteRecursively()
                                mergeDataDir(sourceDir, workingDir)
                            }
                        }
                    }
                    val msg = when (mode) {
                        DataMergeMode.KEEP_CURRENT -> ctx.getString(R.string.app_data_kept)
                        DataMergeMode.MERGE -> ctx.getString(R.string.app_data_merged)
                        DataMergeMode.KEEP_HISTORY -> ctx.getString(R.string.app_data_overwritten)
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = {
                showPreviousDataDialog = false
            }
        )
    }

    val onboardingManager: com.lin.hippyagent.core.onboarding.OnboardingManager = org.koin.compose.koinInject()
    var showOnboarding by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val completed = onboardingManager.isOnboardingCompleted.first()
            showOnboarding = !completed
        }
    }

    var currentAgentId by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier) {
        NavHost(
            navController = navController,
            startDestination = Screen.Sessions.route
        ) {
            composable(Screen.Sessions.route) {
                val sessionsViewModel: com.lin.hippyagent.ui.conversation.ConversationListViewModel = org.koin.androidx.compose.koinViewModel()
                val mainPagerState = rememberPagerState(initialPage = 0) { 4 }

                LaunchedEffect(lastActiveAgentId) {
                    lastActiveAgentId?.let { agentId ->
                        if (sessionsViewModel.uiState.value.currentAgentId != agentId) {
                            sessionsViewModel.switchAgent(agentId)
                        }
                    }
                }

                MainScreen(
                    navController = navController,
                    coroutineScope = coroutineScope,
                    pagerState = mainPagerState,
                    sessionsViewModel = sessionsViewModel,
                    lastActiveAgentId = lastActiveAgentId,
                    currentAgentId = currentAgentId,
                    onCurrentAgentIdChanged = { currentAgentId = it },
                    onNavigateToChat = { sessionId, agentId ->
                        navController.navigate(Screen.Chat.createRoute(sessionId, agentId))
                    },
                    onNavigateToGroupChat = { groupId ->
                        navController.navigate(Screen.Chat.createRoute(groupId, "group"))
                    },
                    onNavigateToCreateGroup = {
                        showCreateGroupDialog = true
                    },
                    onNavigateToCreateAgent = {
                        navController.navigate(Screen.CreateAgent.route)
                    },
                    onNavigateToAgentConfig = { agentId ->
                        currentAgentId = agentId
                        coroutineScope.launch { mainPagerState.animateScrollToPage(3) }
                    },
                    showCreateGroupDialog = showCreateGroupDialog,
                    onCreateGroupDialogDismiss = { showCreateGroupDialog = false },
                    onCreateGroup = { name, agentIds ->
                        groupManager.createGroup(name, agentIds)
                        showCreateGroupDialog = false
                        Toast.makeText(
                            navController.context,
                            navController.context.getString(R.string.group_created_success, name),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    agentRepository = agentRepository,
                    groupManager = groupManager
                )
            }

            composable(
                route = Screen.Chat.route,
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("agentId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
                val agentId = backStackEntry.arguments?.getString("agentId") ?: ""

                if (agentId == "group") {
                    val groupChatVm: com.lin.hippyagent.ui.chat.ChatViewModel = org.koin.androidx.compose.koinViewModel()
                    val groupPermVm: com.lin.hippyagent.ui.chat.PermissionViewModel = org.koin.androidx.compose.koinViewModel()
                    val groupPlanVm: com.lin.hippyagent.ui.chat.PlanViewModel = org.koin.androidx.compose.koinViewModel()
                    groupChatVm.attachSubViewModels(groupPermVm, groupPlanVm)
                    com.lin.hippyagent.ui.chat.GroupChatScreen(
                        viewModel = groupChatVm,
                        permissionViewModel = groupPermVm,
                        inputViewModel = org.koin.androidx.compose.koinViewModel(),
                        sessionId = sessionId,
                        agentId = agentId,
                        onBackClick = {
                            val chatVm = try { org.koin.java.KoinJavaComponent.getKoin().get<com.lin.hippyagent.ui.chat.ChatViewModel>() } catch (_: Exception) { null }
                            chatVm?.cleanupEmptySession()
                            lastActiveAgentId = agentId
                            navController.popBackStack()
                        },
                        onNavigateToAgentConfig = {
                            navController.navigate(Screen.GroupSettings.createRoute(sessionId))
                        }
                    )
                } else {
                    val chatVm: com.lin.hippyagent.ui.chat.ChatViewModel = org.koin.androidx.compose.koinViewModel()
                    val permVm: com.lin.hippyagent.ui.chat.PermissionViewModel = org.koin.androidx.compose.koinViewModel()
                    val planVm: com.lin.hippyagent.ui.chat.PlanViewModel = org.koin.androidx.compose.koinViewModel()
                    chatVm.attachSubViewModels(permVm, planVm)
                    com.lin.hippyagent.ui.chat.ChatScreen(
                        viewModel = chatVm,
                        permissionViewModel = permVm,
                        planViewModel = planVm,
                        inputViewModel = org.koin.androidx.compose.koinViewModel(),
                        sessionId = sessionId,
                        agentId = agentId,
                        onBackClick = {
                            val chatVm = try { org.koin.java.KoinJavaComponent.getKoin().get<com.lin.hippyagent.ui.chat.ChatViewModel>() } catch (_: Exception) { null }
                            chatVm?.cleanupEmptySession()
                            lastActiveAgentId = agentId
                            navController.popBackStack()
                        },
                        onNavigateToAgentConfig = { configAgentId ->
                            if (configAgentId == "group") {
                                navController.navigate(Screen.GroupSettings.createRoute(sessionId))
                            } else {
                                com.lin.hippyagent.core.agent.AgentSelectionHolder.setCurrentAgent(configAgentId)
                                lastActiveAgentId = configAgentId
                                navController.navigate(Screen.Sessions.route) {
                                    popUpTo(Screen.Sessions.route) { inclusive = true }
                                }
                            }
                        },
                        onNavigateToChat = { sId, aId ->
                            navController.navigate(Screen.Chat.createRoute(sId, aId)) {
                                popUpTo(Screen.Chat.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onNavigateToModelProvider = {
                            navController.navigate(Screen.ModelProvider.route)
                        }
                    )
                }
            }

            composable(Screen.ModelProvider.route) {
                val viewModel: com.lin.hippyagent.ui.settings.ModelProviderViewModel = org.koin.androidx.compose.koinViewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                com.lin.hippyagent.ui.settings.ModelProviderScreen(
                    providers = uiState.providers,
                    onAddProvider = viewModel::addProvider,
                    onUpdateProvider = viewModel::updateProvider,
                    onDeleteProvider = viewModel::deleteProvider,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) },
                    onProviderClick = { providerId ->
                        navController.navigate(Screen.ProviderDetail.createRoute(providerId))
                    }
                )
            }

            composable(
                route = Screen.ProviderDetail.route,
                arguments = listOf(navArgument("providerId") { type = NavType.StringType })
            ) { backStackEntry ->
                val providerId = backStackEntry.arguments?.getString("providerId") ?: ""
                val viewModel: com.lin.hippyagent.ui.settings.ModelProviderViewModel = org.koin.androidx.compose.koinViewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val provider = uiState.providers.find { it.id == providerId }
                if (provider != null) {
                    com.lin.hippyagent.ui.settings.ProviderDetailScreen(
                        provider = provider,
                        viewModel = viewModel,
                        onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                    )
                }
            }

            composable(Screen.About.route) {
                com.lin.hippyagent.ui.settings.AboutScreen(
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(
                route = Screen.RunningConfig.route,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType })
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                com.lin.hippyagent.ui.settings.agent.RunningConfigScreen(
                    viewModel = org.koin.androidx.compose.koinViewModel(
                        parameters = { org.koin.core.parameter.parametersOf(agentId) }
                    ),
                    onBack = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(
                route = Screen.Heartbeat.route,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType })
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                com.lin.hippyagent.ui.settings.agent.HeartbeatScreen(
                    viewModel = org.koin.androidx.compose.koinViewModel(
                        parameters = { org.koin.core.parameter.parametersOf(agentId) }
                    ),
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(
                route = Screen.MCP.route,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType })
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                com.lin.hippyagent.ui.settings.mcp.MCPScreen(
                    viewModel = org.koin.androidx.compose.koinViewModel(
                        parameters = { org.koin.core.parameter.parametersOf(agentId) }
                    ),
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(
                route = Screen.CoreFiles.route,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType })
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                com.lin.hippyagent.ui.workspace.CoreFilesScreen(
                    viewModel = org.koin.androidx.compose.koinViewModel(
                        parameters = { org.koin.core.parameter.parametersOf(agentId) }
                    ),
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) },
                    onFileClick = { }
                )
            }

            composable(
                route = Screen.ACP.route,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType })
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                com.lin.hippyagent.ui.settings.acp.ACPScreen(
                    agentId = agentId,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) },
                    onNavigateToAcpClient = { navController.navigate(Screen.AcpClient.route) }
                )
            }

            composable(Screen.DebugInfo.route) {
                com.lin.hippyagent.ui.settings.general.DebugInfoScreen(onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) })
            }
            composable(Screen.DataStorage.route) {
                com.lin.hippyagent.ui.settings.general.DataStorageScreen(onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) })
            }
            composable(Screen.ExportLog.route) {
                com.lin.hippyagent.ui.settings.general.ExportLogScreen(onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) })
            }
            composable(Screen.Language.route) {
                com.lin.hippyagent.ui.settings.general.LanguageScreen(onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) })
            }
            composable(Screen.Notification.route) {
                com.lin.hippyagent.ui.settings.general.NotificationScreen(onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) })
            }
            composable(
                route = Screen.AgentToolSecurity.route,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType })
            ) { backStackEntry ->
                com.lin.hippyagent.ui.settings.general.ToolSecurityScreen(onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) })
            }
            composable(Screen.AccessibilitySetup.route) {
                com.lin.hippyagent.ui.settings.general.AccessibilitySetupScreen(onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) })
            }
            composable(Screen.EnvCheck.route) {
                val linuxManager: com.lin.hippyagent.core.linux.LinuxManager = org.koin.java.KoinJavaComponent.get(com.lin.hippyagent.core.linux.LinuxManager::class.java)
                com.lin.hippyagent.ui.settings.general.EnvCheckScreen(
                    linuxManager = linuxManager,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }
            composable(Screen.UiSettings.route) {
                val conversationListVm: com.lin.hippyagent.ui.conversation.ConversationListViewModel = org.koin.androidx.compose.koinViewModel()
                com.lin.hippyagent.ui.settings.general.UiSettingsScreen(
                    onBack = { navController.popBackStack(Screen.Sessions.route, inclusive = false) },
                    onThresholdChanged = { minutes -> conversationListVm.setInactiveThreshold(minutes) }
                )
            }
            composable(Screen.GlobalRules.route) {
                val vm: com.lin.hippyagent.ui.settings.general.GlobalRulesViewModel = org.koin.androidx.compose.koinViewModel()
                com.lin.hippyagent.ui.settings.general.GlobalRulesScreen(
                    viewModel = vm,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }
            composable(Screen.SkillPool.route) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val skillManager = com.lin.hippyagent.core.skill.SkillManager(context)
                com.lin.hippyagent.ui.settings.SkillPoolScreen(
                    skillManager = skillManager,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) },
                    onNavigateToStore = { navController.navigate(Screen.SkillStore.createRoute()) }
                )
            }
            composable(
                route = Screen.SkillStore.route,
                arguments = listOf(navArgument("sourceAgentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) { backStackEntry ->
                val sourceAgentId = backStackEntry.arguments?.getString("sourceAgentId")
                val context = androidx.compose.ui.platform.LocalContext.current
                val skillManager = com.lin.hippyagent.core.skill.SkillManager(context)
                val linuxManager: com.lin.hippyagent.core.linux.LinuxManager = org.koin.java.KoinJavaComponent.get(com.lin.hippyagent.core.linux.LinuxManager::class.java)
                val isLinuxReady by linuxManager.isReady.collectAsState(initial = false)
                val workspaceManager = com.lin.hippyagent.core.storage.WorkspaceManager(
                    context,
                    com.lin.hippyagent.core.storage.StorageManager(context)
                )
                val viewModel = remember(sourceAgentId) {
                    val clawHubProvider = com.lin.hippyagent.core.skill.store.provider.ClawHubProvider(linuxManager)
                    val providers = mapOf(
                        "lobehub" to com.lin.hippyagent.core.skill.store.provider.LobeHubProvider(linuxManager),
                        "skills_sh" to com.lin.hippyagent.core.skill.store.provider.SkillsShProvider(linuxManager),
                        "clawhub" to clawHubProvider,
                        "skillhub" to com.lin.hippyagent.core.skill.store.provider.SkillHubProvider(linuxManager, clawHubProvider)
                    )
                    val storeService = com.lin.hippyagent.core.skill.store.SkillStoreService(linuxManager, providers)
                    com.lin.hippyagent.ui.store.SkillStoreViewModel(context.applicationContext as android.app.Application, storeService, skillManager, sourceAgentId, workspaceManager)
                }
                com.lin.hippyagent.ui.store.SkillStoreScreen(
                    viewModel = viewModel,
                    isLinuxReady = isLinuxReady,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) },
                    onNavigateToEnvCheck = { navController.navigate(Screen.EnvCheck.route) }
                )
            }
            composable(
                route = Screen.ChannelConfig.route,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType })
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                com.lin.hippyagent.ui.channel.ChannelConfigScreen(
                    agentId = agentId,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) },
                    onQrAuthClick = { aid, cid ->
                        navController.navigate(Screen.QrAuth.createRoute(aid, cid))
                    }
                )
            }
            composable(
                route = Screen.QrAuth.route,
                arguments = listOf(
                    navArgument("agentId") { type = NavType.StringType },
                    navArgument("channelId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                val channelId = backStackEntry.arguments?.getString("channelId") ?: ""
                val channelName = com.lin.hippyagent.ui.channel.SUPPORTED_CHANNELS.find { it.id == channelId }?.name ?: channelId
                val secureStorage: com.lin.hippyagent.core.storage.SecureStorage = org.koin.compose.koinInject()
                val okHttpClient: okhttp3.OkHttpClient = org.koin.compose.koinInject()
                val context = androidx.compose.ui.platform.LocalContext.current
                val configStore = androidx.compose.runtime.remember(context, agentId) {
                    com.lin.hippyagent.core.channel.ChannelConfigStore(
                        java.io.File(
                            com.lin.hippyagent.core.storage.StorageManager(context).getWorkingDir(),
                            "workspaces/$agentId/channels"
                        )
                    )
                }
                val provider = remember(channelId) {
                    when (channelId) {
                        "weixin" -> com.lin.hippyagent.core.channel.qr.WeixinQrAuthProvider(
                            com.lin.hippyagent.core.channel.ILinkClient()
                        )
                        "feishu" -> com.lin.hippyagent.core.channel.qr.FeishuQrAuthProvider(okHttpClient)
                        "dingtalk" -> com.lin.hippyagent.core.channel.qr.DingtalkQrAuthProvider(okHttpClient)
                        "wechat" -> com.lin.hippyagent.core.channel.qr.WecomQrAuthProvider(okHttpClient)
                        else -> throw IllegalArgumentException("Unsupported channel: $channelId")
                    }
                }
                val viewModel = remember(channelId, agentId) {
                    com.lin.hippyagent.ui.channel.QrAuthViewModel(
                        provider = provider,
                        secureStorage = secureStorage,
                        configStore = configStore,
                        agentId = agentId
                    )
                }
                com.lin.hippyagent.ui.channel.QrAuthScreen(
                    channelId = channelId,
                    channelName = channelName,
                    viewModel = viewModel,
                    onBackClick = { navController.popBackStack() },
                    onAuthSuccess = {
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("refresh_channel", channelId)
                        navController.popBackStack()
                    }
                )
            }
            composable(Screen.AcpClient.route) {
                val clientStore: com.lin.hippyagent.core.agent.collaboration.AcpClientStore = org.koin.java.KoinJavaComponent.get(com.lin.hippyagent.core.agent.collaboration.AcpClientStore::class.java)
                val viewModel = com.lin.hippyagent.ui.settings.acp.AcpClientViewModel(clientStore, androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application)
                com.lin.hippyagent.ui.settings.acp.AcpClientScreen(
                    viewModel = viewModel,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(
                route = Screen.SkillManagement.route,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType })
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                val skillManager: com.lin.hippyagent.core.skill.SkillManager = org.koin.compose.koinInject()
                val agentRepository: com.lin.hippyagent.data.repository.AgentRepository = org.koin.compose.koinInject()
                com.lin.hippyagent.ui.agent.AgentSkillScreen(
                    agentId = agentId,
                    skillManager = skillManager,
                    agentRepository = agentRepository,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(
                route = Screen.Dream.route,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType })
            ) { backStackEntry ->
                val dreamViewModel: com.lin.hippyagent.ui.settings.DreamViewModel = org.koin.androidx.compose.koinViewModel()
                com.lin.hippyagent.ui.settings.DreamScreen(
                    viewModel = dreamViewModel,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(
                route = Screen.MemoryCompaction.route,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType })
            ) { backStackEntry ->
                com.lin.hippyagent.ui.settings.MemoryCompactionScreen(
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(
                route = Screen.CommonMemory.route,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType })
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                com.lin.hippyagent.ui.memory.CommonMemoryScreen(
                    viewModel = org.koin.androidx.compose.koinViewModel(
                        parameters = { org.koin.core.parameter.parametersOf(agentId) }
                    ),
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(Screen.ToolApprovals.route) {
                com.lin.hippyagent.ui.settings.ToolApprovalsScreen(
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(Screen.SecurityRules.route) {
                com.lin.hippyagent.ui.settings.SecurityRulesScreen(
                    onBack = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(Screen.ToolsList.route) {
                val toolRegistry = org.koin.compose.koinInject<com.lin.hippyagent.core.tools.ToolRegistry>()
                com.lin.hippyagent.ui.settings.ToolsListScreen(
                    toolRegistry = toolRegistry,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) },
                    onToolClick = { toolName ->
                        navController.navigate(Screen.ToolDetail.createRoute(toolName))
                    }
                )
            }

            composable(
                route = Screen.ToolDetail.route,
                arguments = listOf(navArgument("toolName") { type = NavType.StringType })
            ) { backStackEntry ->
                val toolName = backStackEntry.arguments?.getString("toolName") ?: ""
                val toolRegistry = org.koin.compose.koinInject<com.lin.hippyagent.core.tools.ToolRegistry>()
                com.lin.hippyagent.ui.settings.ToolDetailScreen(
                    toolName = toolName,
                    toolRegistry = toolRegistry,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(Screen.Insights.route) {
                val insightsViewModel: com.lin.hippyagent.ui.insights.InsightsViewModel = org.koin.androidx.compose.koinViewModel()
                com.lin.hippyagent.ui.insights.InsightsScreen(
                    viewModel = insightsViewModel,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(Screen.Plugins.route) {
                val pluginViewModel: com.lin.hippyagent.ui.plugin.PluginViewModel = org.koin.androidx.compose.koinViewModel()
                com.lin.hippyagent.ui.plugin.PluginScreen(
                    viewModel = pluginViewModel,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(
                route = Screen.CronJobs.route,
                arguments = listOf(navArgument("agentId") { type = NavType.StringType })
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                com.lin.hippyagent.ui.settings.agent.CronJobsScreen(
                    viewModel = org.koin.androidx.compose.koinViewModel(
                        parameters = { org.koin.core.parameter.parametersOf(agentId) }
                    ),
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(
                route = Screen.GroupSettings.route,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType })
            ) { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
                com.lin.hippyagent.ui.conversation.GroupSettingsScreen(
                    viewModel = org.koin.androidx.compose.koinViewModel(),
                    groupId = groupId,
                    onNavigateBack = { navController.popBackStack(Screen.Sessions.route, inclusive = false) },
                    onGroupDissolved = {
                        navController.popBackStack(Screen.Sessions.route, inclusive = false)
                    }
                )
            }

            composable(Screen.PermissionCenter.route) {
                com.lin.hippyagent.ui.settings.PermissionCenterScreen(
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(Screen.Inbox.route) {
                val inboxViewModel: com.lin.hippyagent.ui.inbox.InboxViewModel = org.koin.androidx.compose.koinViewModel()
                com.lin.hippyagent.ui.inbox.InboxScreen(
                    viewModel = inboxViewModel,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(Screen.CreateAgent.route) {
                val createViewModel: com.lin.hippyagent.ui.agent.AgentConfigViewModel = org.koin.androidx.compose.koinViewModel(
                    key = "new",
                    parameters = { org.koin.core.parameter.parametersOf("new") }
                )
                com.lin.hippyagent.ui.agent.CreateAgentScreen(
                    viewModel = createViewModel,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) },
                    onAgentCreated = { agentId ->
                        com.lin.hippyagent.core.agent.AgentSelectionHolder.setCurrentAgent(agentId)
                        navController.popBackStack()
                    },
                    onNavigateToModelProvider = { navController.navigate(Screen.ModelProvider.route) }
                )
            }

            composable(Screen.EnvVars.route) {
                val envViewModel: com.lin.hippyagent.ui.settings.env.EnvVarsViewModel = org.koin.androidx.compose.koinViewModel()
                com.lin.hippyagent.ui.settings.env.EnvVarsScreen(
                    viewModel = envViewModel,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }

            composable(Screen.SystemHooks.route) {
                val hookViewModel: com.lin.hippyagent.ui.settings.hooks.SystemHookViewModel =
                    org.koin.androidx.compose.koinViewModel()
                com.lin.hippyagent.ui.settings.hooks.SystemHookSettingsScreen(
                    viewModel = hookViewModel,
                    onBackClick = { navController.popBackStack(Screen.Sessions.route, inclusive = false) }
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                if (!linuxManager.isReady.value) {
                    linuxManager.initialize()
                }
            } catch (_: Exception) {
            }
        }
    }

    if (showOnboarding == true) {
        OnboardingScreen(
            onComplete = {
                showOnboarding = false
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    onboardingManager.completeOnboarding()
                }
            },
            onNavigateToModelProvider = {
                navController.navigate(Screen.ModelProvider.route)
            },
            onNavigateToPermissionCenter = {
                navController.navigate(Screen.PermissionCenter.route)
            }
        )
    }
}
