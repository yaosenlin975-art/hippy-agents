package com.lin.hippyagent.di

import androidx.room.Room
import com.lin.hippyagent.core.agent.session.AppDatabase
import com.lin.hippyagent.core.agent.session.ALL_MIGRATIONS
import com.lin.hippyagent.core.agent.session.RoomSessionStore
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.flow.FlowEngine
import com.lin.hippyagent.core.insights.InsightsEngine
import com.lin.hippyagent.core.insights.PricingEngine
import com.lin.hippyagent.core.knowledge.KnowledgeGraphStore
import com.lin.hippyagent.core.memory.commonmemory.MemoryDatabase
import com.lin.hippyagent.core.memory.commonmemory.MemoryRepository
import com.lin.hippyagent.core.memory.commonmemory.RoomMemoryRepositoryImpl
import com.lin.hippyagent.core.memory.SymbolicRetriever
import org.koin.android.ext.koin.androidContext
import com.lin.hippyagent.core.task.HippyJobQueue
import com.lin.hippyagent.core.task.HippyJobWorker
import com.lin.hippyagent.core.task.StallDetector
import com.lin.hippyagent.core.task.RateLimiter
import com.lin.hippyagent.core.agent.task.TaskExecutionEngine
import com.lin.hippyagent.core.hooks.HippyHookManager
import com.lin.hippyagent.core.inbox.InboxStore
import org.koin.dsl.module

val databaseModule = module {
    single { MemoryDatabase.getInstance(androidContext()) }

    single<MemoryRepository> {
        val db = get<MemoryDatabase>()
        RoomMemoryRepositoryImpl(
            dao = db.memoryDao(),
            symbolicRetriever = SymbolicRetriever(memoryDao = db.memoryDao()),
            database = db
        )
    }

    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "hippy.db")
            .addMigrations(*ALL_MIGRATIONS.toTypedArray())
            .build()
    }

    single<SessionStore> {
        val db = get<AppDatabase>()
        RoomSessionStore(sessionDao = db.sessionDao(), sessionStatsDao = db.sessionStatsDao(), sessionCompressionDao = db.sessionCompressionDao(), messageDao = db.messageDao(), database = db)
    }

    single {
        val db = get<AppDatabase>()
        InboxStore(inboxDao = db.inboxDao())
    }

    single { HippyJobQueue(dao = get<AppDatabase>().hippyJobDao()) }

    single {
        val worker = HippyJobWorker(
            queue = get(),
            dao = get<AppDatabase>().hippyJobDao(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        )
        worker.register("subagent_loop", com.lin.hippyagent.core.agent.subagent.SubAgentLoopHandler(agentFactory = get(), sessionStore = get()))
        worker.register(
            com.lin.hippyagent.core.agent.task.TaskExecutionEngine.TASK_STEP_JOB_NAME,
            com.lin.hippyagent.core.agent.task.TaskStepJobHandler(
                engine = get<com.lin.hippyagent.core.agent.task.TaskExecutionEngine>(),
                agentFactory = get(),
            )
        )
        worker
    }

    single { StallDetector(dao = get<AppDatabase>().hippyJobDao()) }

    single {
        TaskExecutionEngine(
            dao = get<AppDatabase>().taskDao(),
            jobQueue = get(),
            stallDetector = get(),
            applicationScope = get(
                kotlinx.coroutines.CoroutineScope::class,
                org.koin.core.qualifier.named("applicationScope")
            ),
            taskPlanner = getOrNull<com.lin.hippyagent.core.agent.task.TaskPlanner>(),
            auditLogger = getOrNull<com.lin.hippyagent.core.agent.task.AuditLogger>(),
            approvalService = getOrNull<com.lin.hippyagent.core.agent.task.TaskApprovalService>()
        )
    }

    single { RateLimiter() }

    single { HippyHookManager() }

    single {
        val db = get<AppDatabase>()
        KnowledgeGraphStore(
            entityDao = db.graphEntityDao(),
            relationDao = db.graphRelationDao()
        )
    }

    single { PricingEngine() }

    single {
        val db = get<AppDatabase>()
        InsightsEngine(
            insightsDao = db.insightsDao(),
            pricingEngine = get(),
            skillManager = get(),
            agentRepository = get()
        )
    }

    single {
        val db = get<AppDatabase>()
        FlowEngine(
            flowRecordDao = db.flowRecordDao(),
            flowStepDao = db.flowStepDao()
        )
    }
}
