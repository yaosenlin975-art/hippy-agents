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
import com.lin.hippyagent.core.tools.ToolGuardian
import com.lin.hippyagent.core.tools.ToolRegistry
import com.lin.hippyagent.core.model.TokenUsageManager
import com.lin.hippyagent.data.repository.AgentRepository
import org.koin.android.ext.koin.androidContext
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

    single {
        val modelClients = ConcurrentHashMap<String, com.lin.hippyagent.core.model.ModelClient>()
        LLMSpeakerSelector(
            modelClients = modelClients,
            descriptionProvider = get(),
            defaultModelId = null
        ).also {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
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
        }
    }

    single { SubAgentAggregator(dao = get<com.lin.hippyagent.core.agent.session.AppDatabase>().hippyJobDao()) }

    single { SubAgentOrchestrator(jobQueue = get(), dao = get<com.lin.hippyagent.core.agent.session.AppDatabase>().hippyJobDao(), aggregator = get()) }

    single { HeartbeatScheduler(agentFactory = get(), sessionStore = get(), storageManager = get()) }

    single { CronJobManager(context = androidContext(), agentFactory = get(), sessionStore = get()) }

    single {
        ProactiveMemoryManager(
            context = androidContext(),
            sessionStore = get(),
            notificationService = get()
        )
    }
}
