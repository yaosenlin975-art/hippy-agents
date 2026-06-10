package com.lin.hippyagent.di

import android.content.Context
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.lin.hippyagent.core.auth.AuthManager
import com.lin.hippyagent.core.auth.SecretMigrationManager
import com.lin.hippyagent.core.backup.BackupManager
import com.lin.hippyagent.core.debug.DebugInfoCollector
import com.lin.hippyagent.core.i18n.LanguageManager
import com.lin.hippyagent.core.log.LogExporter
import com.lin.hippyagent.core.migration.MigrationManager
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.model.ModelProviderMatcher
import com.lin.hippyagent.core.model.ModelClientFactory
import com.lin.hippyagent.core.model.AuthProfileManager
import com.lin.hippyagent.core.model.TokenUsageManager
import com.lin.hippyagent.core.model.routing.BudgetManager
import com.lin.hippyagent.core.model.routing.ModelRouter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import com.lin.hippyagent.core.notification.HippyAgentNotificationService
import com.lin.hippyagent.core.notification.BadgeManager
import com.lin.hippyagent.core.onboarding.OnboardingManager
import com.lin.hippyagent.core.storage.ConfigStorage
import com.lin.hippyagent.core.storage.SecureStorage
import com.lin.hippyagent.core.storage.StorageManager
import com.lin.hippyagent.core.stats.StatsManager
import com.lin.hippyagent.core.stats.AgentStatsManager
import com.lin.hippyagent.core.mission.MissionManager
import com.lin.hippyagent.data.repository.AgentRepository
import com.lin.hippyagent.ui.agent.AgentConfigViewModel
import com.lin.hippyagent.ui.chat.ChatViewModel
import com.lin.hippyagent.ui.chat.ChatInputViewModel
import com.lin.hippyagent.ui.chat.ChatSearchViewModel
import com.lin.hippyagent.ui.chat.PermissionViewModel
import com.lin.hippyagent.ui.chat.PlanViewModel
import com.lin.hippyagent.ui.conversation.ConversationListViewModel
import com.lin.hippyagent.ui.conversation.GroupSettingsViewModel
import com.lin.hippyagent.ui.settings.agent.HeartbeatViewModel
import com.lin.hippyagent.ui.settings.mcp.MCPViewModel
import com.lin.hippyagent.ui.settings.agent.RunningConfigViewModel
import com.lin.hippyagent.ui.settings.ModelProviderViewModel
import com.lin.hippyagent.ui.settings.SettingsViewModel
import com.lin.hippyagent.ui.settings.hooks.SystemHookViewModel
import com.lin.hippyagent.ui.workspace.CoreFilesViewModel
import com.lin.hippyagent.core.agent.session.AppDatabase
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { (agentId: String) ->
        RunningConfigViewModel(repository = get(), agentId = agentId, agentFactory = get())
    }

    viewModel { (agentId: String) ->
        HeartbeatViewModel(repository = get(), agentId = agentId, heartbeatScheduler = get())
    }

    viewModel { (agentId: String) ->
        MCPViewModel(repository = get(), agentId = agentId)
    }

    viewModel { (agentId: String) ->
        CoreFilesViewModel(repository = get(), agentId = agentId)
    }

    viewModel { ModelProviderViewModel(store = get()) }

    viewModel { SettingsViewModel(repository = get(), secureStorage = get(), application = get(), storageManager = get(), agentFactory = get()) }

    viewModel { SystemHookViewModel(hookManager = get()) }

    viewModel { com.lin.hippyagent.ui.settings.general.GlobalRulesViewModel(configStorage = get(), application = get()) }

    viewModel { (agentId: String) ->
        AgentConfigViewModel(repository = get(), sessionStore = get(), modelProviderStore = get(), application = get(), agentId = agentId, agentFactory = get())
    }

    viewModel { com.lin.hippyagent.ui.settings.env.EnvVarsViewModel(configStorage = get()) }

    single { androidContext().getSharedPreferences("ui_settings", android.content.Context.MODE_PRIVATE) }
    viewModel { ConversationListViewModel(context = androidContext(), sessionStore = get(), agentRepository = get(), agentFactory = get(), groupRegistry = get(), sessionGroupDao = get<AppDatabase>().sessionGroupDao(), notificationService = get(), prefs = get()) }

    viewModel {
        GroupSettingsViewModel(
            groupManager = get(),
            agentRepository = get(),
            modelProviderStore = get()
        )
    }

    viewModel {
        ChatViewModel(
            context = androidContext() as android.app.Application,
            sessionStore = get(),
            agentFactory = get(),
            agentRepository = get(),
            modelProviderStore = get(),
            notificationService = get(),
            missionManager = get(),
            proactiveMemory = get(),
            backupManager = get(),
            statsManager = get(),
            groupRegistry = get(),
            agentGroupManager = get(),
            onDeviceModelManager = get(),
            modeOrchestrator = getOrNull<com.lin.hippyagent.core.agent.mode.ModeOrchestrator>(),
            toolApprovalManager = get(),
            taskDao = getOrNull<com.lin.hippyagent.core.agent.session.AppDatabase>().let { it?.taskDao() }
        )
    }

    viewModel {
        PermissionViewModel(
            actionApprover = get(),
            agentFactory = get(),
            permissionManager = get()
        )
    }

    viewModel {
        PlanViewModel(
            agentFactory = get()
        )
    }

    viewModel {
        ChatInputViewModel(
            context = androidContext()
        )
    }

    viewModel {
        ChatSearchViewModel(
            sessionStore = get()
        )
    }

    viewModel {
        com.lin.hippyagent.ui.insights.InsightsViewModel(insightsEngine = get())
    }

    viewModel {
        com.lin.hippyagent.ui.inbox.InboxViewModel(inboxStore = get())
    }

    viewModel {
        com.lin.hippyagent.ui.notification.NotificationCenterViewModel(center = get())
    }

    viewModel {
        com.lin.hippyagent.ui.task.TaskListViewModel(
            dao = get<com.lin.hippyagent.core.agent.session.AppDatabase>().taskDao(),
            engine = getOrNull<com.lin.hippyagent.core.agent.task.TaskExecutionEngine>()
        )
    }

    viewModel {
        com.lin.hippyagent.ui.task.TaskDetailViewModel(
            dao = get<com.lin.hippyagent.core.agent.session.AppDatabase>().taskDao(),
            approvalService = getOrNull<com.lin.hippyagent.core.agent.task.TaskApprovalService>()
        )
    }

    viewModel {
        com.lin.hippyagent.ui.plugin.PluginViewModel(pluginManager = get())
    }

    viewModel { (agentId: String) ->
        val memoryRepository = getOrNull<com.lin.hippyagent.core.memory.commonmemory.MemoryRepository>()
            ?: throw IllegalStateException("MemoryRepository not available")
        com.lin.hippyagent.ui.memory.CommonMemoryViewModel(
            memoryRepository = memoryRepository,
            agentId = agentId
        )
    }

    viewModel {
        val context = androidContext()
        val database = get<AppDatabase>()
        val mdManager = com.lin.hippyagent.core.agent.AgentMdManager(context)
        val memoryRepository = getOrNull<com.lin.hippyagent.core.memory.commonmemory.MemoryRepository>()
        val dreamManager = com.lin.hippyagent.core.memory.DreamMemoryManager(
            context = context,
            mdManager = mdManager,
            database = database,
            memoryRepository = memoryRepository
        )
        com.lin.hippyagent.ui.settings.DreamViewModel(
            dreamManager = dreamManager,
            database = database
        )
    }

    viewModel {
        com.lin.hippyagent.ui.settings.SecurityRulesViewModel(
            approvalManager = get()
        )
    }

    viewModel { (agentId: String) ->
        com.lin.hippyagent.ui.settings.agent.CronJobsViewModel(
            cronJobManager = get(),
            agentId = agentId,
            sessionStore = get()
        )
    }

    viewModel { (agentId: String, sessionId: String) ->
        com.lin.hippyagent.core.scheduler.ScheduleCreateViewModel(
            agentId = agentId,
            modelProviderStore = get(),
            secureStorage = get(),
            scheduledTaskStore = get(),
            onDeviceModelManager = getOrNull(),
            sessionId = sessionId,
            cronService = getOrNull()
        )
    }
}

val linuxModule = module {
    single { com.lin.hippyagent.core.linux.LinuxManager(context = androidContext()) }

    single { com.lin.hippyagent.core.linux.tools.ExecuteBashTool(linuxManager = get()) }
    single { com.lin.hippyagent.core.linux.tools.InstallPackageTool(linuxManager = get()) }
    single { com.lin.hippyagent.core.linux.tools.ExecutePythonTool(linuxManager = get()) }

    single { com.lin.hippyagent.core.linux.tools.FileTransferTool(context = androidContext(), linuxManager = get()) }
    single { com.lin.hippyagent.core.linux.tools.ClipboardSyncTool(context = androidContext(), linuxManager = get()) }
    single { com.lin.hippyagent.core.linux.tools.DeviceAccessTool(context = androidContext(), linuxManager = get()) }

    single { com.lin.hippyagent.core.linux.tools.SshServerTool(linuxManager = get()) }
}

val appModule = module {
    single {
        com.lin.hippyagent.core.hooks.system.SystemHookManager(
            context = androidContext(),
            eventCallback = com.lin.hippyagent.core.hooks.system.SystemEventDispatcher(
                context = androidContext(),
                agentRegistry = get(),
                agentFactory = get(),
                sessionStore = get()
            )
        )
    }

    single { com.lin.hippyagent.core.prompt.StandingOrdersManager.createDefault() }

    single {
        com.lin.hippyagent.core.skill.curator.CuratorEngine(
            context = androidContext(),
            historyStore = com.lin.hippyagent.core.skill.curator.ExecutionHistoryStore(androidContext()),
            extractor = com.lin.hippyagent.core.skill.curator.SkillExtractor(),
            merger = com.lin.hippyagent.core.skill.curator.SkillMerger(),
            optimizer = com.lin.hippyagent.core.skill.curator.SkillOptimizer(),
            skillManager = null
        )
    }

    single { com.lin.hippyagent.core.skill.curator.skillopt.SkillAudit(androidContext()) }

    single {
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
            prettyPrint = true
        }
    }

    single { ModelProviderStore(context = androidContext()) }

    single { com.lin.hippyagent.core.ondevice.OnDeviceModelStore(context = androidContext()) }

    single<com.lin.hippyagent.core.ondevice.OnDeviceModelCatalog> { com.lin.hippyagent.core.ondevice.BuiltinOnDeviceModelCatalog(context = androidContext()) }

    single { com.lin.hippyagent.core.ondevice.OnDeviceModelManager(
        context = androidContext(),
        modelStore = get(),
        providerStore = get(),
        catalog = get()
    ) }

    single { com.lin.hippyagent.core.voice.VoiceExtensionManager(context = androidContext()) }

    single { com.lin.hippyagent.core.voice.SttRouter(context = androidContext(), voiceManager = get(), onDeviceModelManager = get()) }

    single { com.lin.hippyagent.core.voice.LiteRTLMTranscriber(
        context = androidContext(),
        onDeviceModelManager = get(),
        serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    ) }

    single { com.lin.hippyagent.core.voice.STTService(
        context = androidContext(),
        voiceManager = get(),
        sttRouter = get(),
        litertlmTranscriber = get()
    ) }

    single { com.lin.hippyagent.core.model.ModelProviderRegistry().apply {
        register(com.lin.hippyagent.core.model.builtin.OpenAIProviderPlugin())
        register(com.lin.hippyagent.core.model.builtin.AnthropicProviderPlugin())
        register(com.lin.hippyagent.core.model.builtin.OllamaProviderPlugin())
    } }

    single { AuthProfileManager(modelProviderStore = get(), secureStorage = get()) }

    single { AgentRepository(context = androidContext(), storageManager = get(), json = get()) }

    single { StorageManager(context = androidContext()) }

    single { SecureStorage(context = androidContext()) }

    single { OnboardingManager(context = androidContext()) }

    single { ConfigStorage(context = androidContext()) }

    single { BudgetManager() }

    single { ModelRouter() }

    single { AuthManager(context = androidContext()) }

    single { BackupManager(context = androidContext()) }

    single { StatsManager(context = androidContext()) }

    single {
        AgentStatsManager(
            sessionStore = get(),
            tokenUsageManager = get()
        )
    }

    single {
        MissionManager(
            context = androidContext(),
            sessionStore = get()
        )
    }

    single {
        HippyAgentNotificationService(
            context = androidContext()
        )
    }

    single {
        com.lin.hippyagent.core.notification.NotificationAggregator()
    }

    single<kotlinx.coroutines.CoroutineScope>(qualifier = org.koin.core.qualifier.named("applicationScope")) {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
        )
    }

    single {
        com.lin.hippyagent.core.notification.NotificationCenter(
            dao = get<com.lin.hippyagent.core.agent.session.AppDatabase>().notificationEventDao(),
            aggregator = get(),
            applicationScope = get(kotlinx.coroutines.CoroutineScope::class, org.koin.core.qualifier.named("applicationScope")),
            notificationService = get()
        )
    }

    single {
        BadgeManager(
            context = androidContext(),
            sessionStore = get()
        )
    }

    single {
        MigrationManager(context = androidContext()).also {
            MigrationManager.registerDefaultMigrations(it)
        }
    }

    single { DebugInfoCollector(context = androidContext()) }

    single { LogExporter(context = androidContext()) }

    single { com.lin.hippyagent.core.cron.CronRunLog(context = androidContext()) }

    single { com.lin.hippyagent.core.cron.CronScheduleParser() }

    single {
        com.lin.hippyagent.core.cron.CronService(
            applicationScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
            ),
            workManager = androidx.work.WorkManager.getInstance(androidContext()),
            runLog = get(),
            taskStore = get(),
            taskEngine = getOrNull<com.lin.hippyagent.core.agent.task.TaskExecutionEngine>(),
            parser = get()
        )
    }

    single { LanguageManager(context = androidContext()) }

    single {
        val providerStore = get<com.lin.hippyagent.core.model.ModelProviderStore>()
        val fallbackClient = com.lin.hippyagent.core.model.OpenAIModelClient(
            baseUrl = "https://api.openai.com/v1",
            apiKey = ""
        )

        // 同步预读 default provider 第一个 default model，作为 fallbackModelName 注入 TaskPlanner
        // 避免早期 planTask 走硬编码 gpt-4o-mini（P0 Bug: 自动决策错误使用小模型）
        val initialProviders = runCatching {
            kotlinx.coroutines.runBlocking { providerStore.providers.first() }
        }.onFailure { Timber.w(it, "TaskPlanner: 同步预读 provider 失败, fallbackModelName 为空") }
            .getOrNull()
        val initialDefaultProvider = initialProviders?.let {
            com.lin.hippyagent.core.model.ModelProviderMatcher.findMatchingProvider(it, "")
        } ?: initialProviders?.let { providers ->
            providers.firstOrNull { p -> p.isDefault && p.enabled }
                ?: providers.firstOrNull { p -> p.enabled }
        }
        val fallbackModelName = initialDefaultProvider?.let { dp ->
            dp.models.firstOrNull { it.isDefault }?.name
                ?: dp.models.firstOrNull { it.enabled }?.name
                ?: dp.models.firstOrNull()?.name
        } ?: ""

        com.lin.hippyagent.core.agent.task.TaskPlanner(
            modelClient = fallbackClient,
            modelName = "",
            fallbackModelName = fallbackModelName
        ).also { planner ->
            val appScope = get<kotlinx.coroutines.CoroutineScope>(
                org.koin.core.qualifier.named("applicationScope")
            )
            appScope.launch {
                val secureStorage = get<com.lin.hippyagent.core.storage.SecureStorage>()
                val providers = runCatching { providerStore.providers.first() }.getOrNull()
                val defaultProvider = providers?.let { com.lin.hippyagent.core.model.ModelProviderMatcher.findMatchingProvider(it, "") }
                    ?: providers?.let { providers ->
                        providers.firstOrNull { p -> p.isDefault && p.enabled }
                            ?: providers.firstOrNull { p -> p.enabled }
                    }
                if (defaultProvider != null) {
                    val client = runCatching {
                        com.lin.hippyagent.core.model.ModelClientFactory.create(
                            provider = defaultProvider,
                            secureStorage = secureStorage,
                            onDeviceModelManager = getOrNull<com.lin.hippyagent.core.ondevice.OnDeviceModelManager>()
                        )
                    }.getOrNull() ?: return@launch
                    val modelName = defaultProvider.models.firstOrNull { it.isDefault }?.name
                        ?: defaultProvider.models.firstOrNull { it.enabled }?.name
                        ?: defaultProvider.models.firstOrNull()?.name
                        ?: return@launch
                    planner.configure(client, modelName)
                }
            }
        }
    }

    single { SecretMigrationManager(context = androidContext(), secureStorage = get()) }

    single { com.lin.hippyagent.core.agent.task.AuditLogger(context = androidContext()) }

    single {
        TokenUsageManager(
            workingDir = androidContext().filesDir
        )
    }

    single<WorkerFactory> {
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): androidx.work.ListenableWorker? {
                return when (workerClassName) {
                    com.lin.hippyagent.core.memory.DreamWorker::class.java.name -> {
                        com.lin.hippyagent.core.memory.DreamWorker(
                            context = appContext,
                            params = workerParameters
                        )
                    }
                    com.lin.hippyagent.core.service.BootSetupWorker::class.java.name -> {
                        com.lin.hippyagent.core.service.BootSetupWorker(
                            context = appContext,
                            workerParams = workerParameters,
                            agentRepository = get(),
                            heartbeatScheduler = get()
                        )
                    }
                    com.lin.hippyagent.core.cron.CronJobWorker::class.java.name -> {
                        com.lin.hippyagent.core.cron.CronJobWorker(
                            context = appContext,
                            params = workerParameters,
                            agentFactory = get(),
                            sessionStore = get(),
                            cronJobManager = get()
                        )
                    }
                    com.lin.hippyagent.core.cron.CronServiceWorker::class.java.name -> {
                        com.lin.hippyagent.core.cron.CronServiceWorker(
                            context = appContext,
                            params = workerParameters,
                            cronService = get()
                        )
                    }
                    com.lin.hippyagent.core.security.ToolApprovalCleanupWorker::class.java.name -> {
                        com.lin.hippyagent.core.security.ToolApprovalCleanupWorker(
                            context = appContext,
                            params = workerParameters
                        )
                    }
                    else -> null
                }
            }
        }
    }
} + agentModule + securityModule + toolModule + channelModule + databaseModule + viewModelModule + linuxModule
