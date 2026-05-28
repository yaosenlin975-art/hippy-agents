package com.lin.hippyagent.di

import com.lin.hippyagent.core.channel.ChannelManager
import com.lin.hippyagent.core.channel.ChannelHealthService
import com.lin.hippyagent.core.network.NetworkMonitor
import com.lin.hippyagent.core.network.OfflineMessageQueue
import com.lin.hippyagent.core.agent.collaboration.AgentGroupManager
import com.lin.hippyagent.core.agent.collaboration.AgentStatusManager
import com.lin.hippyagent.core.agent.collaboration.AcpClientStore
import com.lin.hippyagent.core.agent.collaboration.MessageRelay
import com.lin.hippyagent.core.agent.collaboration.GroupRegistry
import com.lin.hippyagent.core.agent.group.GroupCollaborationProtocol
import com.lin.hippyagent.core.agent.session.AppDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val channelModule = module {
    single { okhttp3.OkHttpClient() }

    single { ChannelManager() }

    single { NetworkMonitor(context = androidContext()) }

    single { OfflineMessageQueue(context = androidContext()) }

    single { ChannelHealthService(channelManager = get()) }

    single { GroupRegistry(dao = get<AppDatabase>().groupDao(), context = androidContext()) }

    single<com.lin.hippyagent.core.agent.collaboration.AgentMessageBus> { com.lin.hippyagent.core.agent.collaboration.InMemoryAgentMessageBus() }

    single {
        AgentGroupManager(
            groupRegistry = get(),
            agentFactory = get(),
            sessionStore = get(),
            messageBus = get(),
            context = androidContext(),
            speakerSelector = getOrNull(),
            collaborationProtocol = getOrNull(),
            descriptionProvider = getOrNull()
        )
    }

    single { AgentStatusManager() }

    single {
        MessageRelay(
            channelManager = get()
        )
    }

    single { AcpClientStore(context = androidContext()) }
}
