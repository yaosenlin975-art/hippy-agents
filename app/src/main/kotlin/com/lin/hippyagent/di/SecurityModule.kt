package com.lin.hippyagent.di

import com.lin.hippyagent.core.security.PermissionManager
import com.lin.hippyagent.core.security.ToolGuardSettings
import com.lin.hippyagent.core.tools.ToolGuardian
import com.lin.hippyagent.core.accessibility.ActionApprover
import com.lin.hippyagent.core.accessibility.AccessibilityController
import com.lin.hippyagent.core.accessibility.DualTrackDecisionEngine
import com.lin.hippyagent.core.accessibility.AdUiGuard
import com.lin.hippyagent.core.accessibility.RefManager
import com.lin.hippyagent.core.accessibility.IncrementalSensor
import com.lin.hippyagent.core.accessibility.AppSpecializationManager
import com.lin.hippyagent.core.accessibility.VisionFrameBuffer
import com.lin.hippyagent.core.accessibility.ScreenFrameSampler
import com.lin.hippyagent.core.accessibility.LocalVoiceVisionHub
import com.lin.hippyagent.core.accessibility.ScreenCompanionController
import com.lin.hippyagent.core.model.ModelProviderMatcher
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.storage.SecureStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val securityModule = module {
    single { PermissionManager(context = androidContext()) }

    single { ToolGuardian(context = androidContext()) }

    single { ToolGuardSettings.getInstance(androidContext()) }

    single {
        com.lin.hippyagent.core.security.ToolApprovalManager(
            context = androidContext(),
            approvalService = get(),
            inboxStore = get()
        ).also {
            com.lin.hippyagent.core.security.ToolApprovalReceiver.manager = it
        }
    }

    single {
        ActionApprover(context = androidContext()).also {
            com.lin.hippyagent.core.accessibility.PermissionActionReceiver.actionApprover = it
        }
    }

    single {
        AccessibilityController(
            context = androidContext(),
            actionApprover = get()
        )
    }

    single { DualTrackDecisionEngine() }

    single { AdUiGuard() }

    single { RefManager() }

    single { IncrementalSensor() }

    single { AppSpecializationManager(androidContext()) }

    single { VisionFrameBuffer() }

    single { ScreenFrameSampler(get()) }

    single { LocalVoiceVisionHub() }

    single { ScreenCompanionController(get(), get(), get()) }

    single {
        val fallbackClient = com.lin.hippyagent.core.model.OpenAIModelClient(baseUrl = "https://api.openai.com/v1", apiKey = "")
        com.lin.hippyagent.core.accessibility.PhoneAutomator(
            controller = get(),
            modelClient = fallbackClient,
            modelName = "gpt-4o"
        ).also { automator ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                val providerStore = get<ModelProviderStore>()
                val secureStorage = get<SecureStorage>()
                val providers = runCatching { providerStore.providers.first() }.getOrNull()
                val defaultProvider = providers?.let { ModelProviderMatcher.findMatchingProvider(it, "") }
                    ?: providers?.let { ModelProviderMatcher.findProviderForModel(it, "gpt-4o") }
                if (defaultProvider != null) {
                    val client = runCatching {
                        com.lin.hippyagent.core.model.ModelClientFactory.create(defaultProvider, secureStorage)
                    }.getOrNull() ?: return@launch
                    val model = defaultProvider.models.firstOrNull { it.isDefault }?.name
                        ?: defaultProvider.models.firstOrNull()?.name
                        ?: return@launch
                    automator.configure(client, model)
                }
            }
        }
    }
}
