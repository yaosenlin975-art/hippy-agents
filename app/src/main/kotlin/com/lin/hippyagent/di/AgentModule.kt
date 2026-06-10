package com.lin.hippyagent.di

import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.AgentRegistry
import com.lin.hippyagent.core.agent.AgentSessionManager
import com.lin.hippyagent.core.agent.AgentMessageRouter
import com.lin.hippyagent.core.agent.proactive.ProactiveTrigger

import com.lin.hippyagent.core.agent.group.TeamTaskHandoff
import com.lin.hippyagent.core.agent.group.ProcessingMarker
import com.lin.hippyagent.core.agent.group.GroupCollaborationProtocol
import com.lin.hippyagent.core.agent.collaboration.LLMSpeakerSelector
import com.lin.hippyagent.core.agent.collaboration.AgentDescriptionProvider
import com.lin.hippyagent.core.agent.subagent.SubAgentAggregator
import com.lin.hippyagent.core.agent.subagent.SubAgentOrchestrator
import com.lin.hippyagent.core.heartbeat.HeartbeatScheduler
import com.lin.hippyagent.core.cron.CronJobManager
import com.lin.hippyagent.core.memory.MemoryStore
import com.lin.hippyagent.core.memory.LocalMemoryStore
import com.lin.hippyagent.core.memory.EmbeddingModel
import com.lin.hippyagent.core.memory.LocalEmbeddingModel
import com.lin.hippyagent.core.memory.ProactiveMemoryManager
import com.lin.hippyagent.core.model.ModelProviderMatcher
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.model.ModelProvider
import com.lin.hippyagent.core.model.AuthProfileManager
import com.lin.hippyagent.core.storage.SecureStorage
import com.lin.hippyagent.core.storage.ConfigStorage
import com.lin.hippyagent.core.skill.SkillLifecycleManager
import com.lin.hippyagent.core.skill.WorkspaceSkillConfigManager
import com.lin.hippyagent.core.tools.ToolGuardian
import com.lin.hippyagent.core.tools.ToolRegistry
import com.lin.hippyagent.core.model.TokenUsageManager
import com.lin.hippyagent.data.repository.AgentRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

val agentModule = module {
    single<MemoryStore> {
        LocalMemoryStore(
            memoryDir = File(androidContext().filesDir, "memory")
        )
    }

    single<EmbeddingModel> { LocalEmbeddingModel() }

    single {
        AgentFactory(
            context = androidContext(),
            repository = get(),
            modelClientProvider = { profile ->
                val providerStore = get<ModelProviderStore>()
                val secureStorage = get<SecureStorage>()
                val providers: List<ModelProvider> = providerStore.providers.first()
                Timber.d("AgentFactory: profile.modelProvider='${profile.modelProvider}', available providers=${providers.map { "${it.id}(protocol=${it.protocol})" }}")
                val matchedProvider = if (profile.modelProvider.isNotBlank()) {
                    ModelProviderMatcher.findMatchingProvider(providers, profile.modelProvider)
                } else {
                    ModelProviderMatcher.findProviderForModel(providers, profile.modelName)
                        ?: ModelProviderMatcher.findMatchingProvider(providers, "")
                }
                Timber.d("AgentFactory: matchedProvider=${matchedProvider?.id}, protocol=${matchedProvider?.protocol}, baseUrl=${matchedProvider?.baseUrl}")
                if (matchedProvider != null) {
                    com.lin.hippyagent.core.model.ModelClientFactory.create(
                        provider = matchedProvider,
                        secureStorage = secureStorage,
                        onDeviceModelManager = getOrNull<com.lin.hippyagent.core.ondevice.OnDeviceModelManager>()
                    )
                } else {
                    Timber.w("AgentFactory: No provider matched, falling back to default OpenAI")
                    com.lin.hippyagent.core.model.OpenAIModelClient(
                        baseUrl = "https://api.openai.com/v1",
                        apiKey = secureStorage.getApiKey("openai") ?: ""
                    )
                }
            },
            toolRegistry = get(),
            memoryStore = get(),
            embeddingModel = get(),
            channelManager = get(),
            sessionStore = get(),
            authProfileManager = getOrNull<AuthProfileManager>(),
            modelProviderStore = getOrNull<ModelProviderStore>(),
            secureStorage = getOrNull<SecureStorage>(),
            commonMemoryRepo = getOrNull<com.lin.hippyagent.core.memory.commonmemory.MemoryRepository>(),
            tokenUsageManager = getOrNull<com.lin.hippyagent.core.model.TokenUsageManager>(),
            skillLifecycleManager = getOrNull<SkillLifecycleManager>(),        
            configStorage = getOrNull<ConfigStorage>(),
            toolGuardian = getOrNull<ToolGuardian>(),
            approvalManager = getOrNull<com.lin.hippyagent.core.security.ToolApprovalManager>(),
            onDeviceModelManager = getOrNull<com.lin.hippyagent.core.ondevice.OnDeviceModelManager>(),
            sessionManager = getOrNull<AgentSessionManager>()
        )
    }

    single { AgentRegistry() }

    single { AgentSessionManager() }

    single { AgentMessageRouter() }

    single {
        ProactiveTrigger(
            sessionStore = get(),
            memoryManager = com.lin.hippyagent.core.memory.MemoryManager(
                store = get(),
                retriever = com.lin.hippyagent.core.memory.HybridRetriever(
                    semanticRetriever = com.lin.hippyagent.core.memory.SemanticRetriever(get(), get()),
                    keywordRetriever = com.lin.hippyagent.core.memory.KeywordRetriever(get())
                )
            ),
            channelManager = get()
        )
    }

    single {
        TeamTaskHandoff(
            workspaceDir = java.io.File(androidContext().filesDir, "workspace")
        )
    }

    single { ProcessingMarker() }

    single { GroupCollaborationProtocol() }

    single { AgentDescriptionProvider() }

    single(named("groupModelClients")) {
        ConcurrentHashMap<String, com.lin.hippyagent.core.model.ModelClient>()
    }

    single {
        val modelClients = get<ConcurrentHashMap<String, com.lin.hippyagent.core.model.ModelClient>>(named("groupModelClients"))
        val applicationScope = get<kotlinx.coroutines.CoroutineScope>(org.koin.core.qualifier.named("applicationScope"))
        applicationScope.launch {
            val providerStore = get<ModelProviderStore>()
            val secureStorage = get<SecureStorage>()
            val providers = runCatching { providerStore.providers.first() }.getOrNull()
            if (providers != null) {
                for (provider in providers) {
                    if (!provider.enabled) continue
                    val client = runCatching {
                        com.lin.hippyagent.core.model.ModelClientFactory.create(
                            provider = provider,
                            secureStorage = secureStorage,
                            onDeviceModelManager = getOrNull<com.lin.hippyagent.core.ondevice.OnDeviceModelManager>()
                        )
                    }.getOrNull() ?: continue
                    for (model in provider.models) {
                        modelClients[model.name] = client
                        modelClients["${provider.id}/${model.name}"] = client
                    }
                }
            }
        }
        LLMSpeakerSelector(
            modelClients = modelClients,
            descriptionProvider = get(),
            defaultModelId = null
        )
    }

    single { SubAgentAggregator(dao = get<com.lin.hippyagent.core.agent.session.AppDatabase>().hippyJobDao()) }

    single { SubAgentOrchestrator(jobQueue = get(), dao = get<com.lin.hippyagent.core.agent.session.AppDatabase>().hippyJobDao(), aggregator = get()) }

    single { HeartbeatScheduler(agentFactory = get(), sessionStore = get(), storageManager = get(), agentGroupManager = getOrNull()) }

    single { CronJobManager(context = androidContext(), agentFactory = get(), sessionStore = get()) }

    single { com.lin.hippyagent.core.scheduler.ScheduledTaskStore(context = androidContext()) }

    single {
        val applicationScope = get<kotlinx.coroutines.CoroutineScope>(
            org.koin.core.qualifier.named("applicationScope")
        )
        val modelClientsShared = get<ConcurrentHashMap<String, com.lin.hippyagent.core.model.ModelClient>>(
            named("groupModelClients")
        )
        com.lin.hippyagent.core.agent.mode.ModeRouter(
            modelClients = modelClientsShared,
            defaultModelName = "",
            applicationScope = applicationScope
        ).also { router ->
            applicationScope.launch {
                val providerStore = get<ModelProviderStore>()
                val secureStorage = get<SecureStorage>()
                val providers = runCatching { providerStore.providers.first() }.getOrNull()
                val defaultProvider = providers?.let { ModelProviderMatcher.findMatchingProvider(it, "") }
                    ?: providers?.let { ModelProviderMatcher.findProviderForModel(it, "gpt-4o-mini") }
                    ?: providers?.firstOrNull { it.enabled }
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
                    modelClientsShared[modelName] = client
                    modelClientsShared["${defaultProvider.id}/$modelName"] = client
                    router.configure(modelClientsShared, modelName)
                }
            }
        }
    }

    single {
        ProactiveMemoryManager(
            context = androidContext(),
            sessionStore = get(),
            notificationService = get()
        )
    }

    single {
        WorkspaceSkillConfigManager(
            workspaceDir = java.io.File(androidContext().filesDir, "workspace")
        )
    }

    single { com.lin.hippyagent.core.agent.mode.ModeAwareSkillActivator(skillConfig = get(), skillLoader = get()) }

    single { com.lin.hippyagent.core.agent.mode.ModeAwareToolFilter(skillConfig = get(), toolRegistry = get()) }

    single {
        com.lin.hippyagent.core.agent.task.TaskApprovalService(
            dao = get<com.lin.hippyagent.core.agent.session.AppDatabase>().taskDao(),
            auditLogger = get<com.lin.hippyagent.core.agent.task.AuditLogger>(),
            notificationCenter = getOrNull<com.lin.hippyagent.core.notification.NotificationCenter>(),
            applicationScope = get(kotlinx.coroutines.CoroutineScope::class, org.koin.core.qualifier.named("applicationScope"))
        )
    }

    single { com.lin.hippyagent.core.agent.mode.ModeOnboarding(skillConfig = get(), prefs = get()) }

    single {
        com.lin.hippyagent.core.agent.mode.ModeOrchestrator(
            modeRouter = get(),
            promptInjector = com.lin.hippyagent.core.agent.mode.ModeSystemPromptInjector(),
            skillActivator = get(),
            toolFilter = get(),
            toolRegistry = get()
        )
    }
}
