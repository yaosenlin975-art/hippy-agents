package com.lin.hippyagent

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.lin.hippyagent.core.accessibility.ActionApprover
import com.lin.hippyagent.core.security.ToolApprovalManager
import com.lin.hippyagent.core.tools.ToolGuardian
import com.lin.hippyagent.core.memory.dream.DreamMemoryProcessor
import com.lin.hippyagent.core.memory.commonmemory.MemoryDatabase
import com.lin.hippyagent.core.memory.commonmemory.MemoryRepository
import com.lin.hippyagent.core.skill.builtin.BuiltinSkillRegistry
import com.lin.hippyagent.core.linux.service.LinuxKeepAliveService
import com.lin.hippyagent.core.notification.HippyAgentNotificationService
import com.lin.hippyagent.core.tools.ToolInitializer
import com.lin.hippyagent.core.hooks.system.SystemHookManager
import com.lin.hippyagent.data.repository.AgentRepository
import com.lin.hippyagent.di.appModule
import com.lin.hippyagent.di.linuxModule
import com.lin.hippyagent.di.viewModelModule
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import timber.log.Timber

class HippyAgentApp : Application(), Configuration.Provider, KoinComponent {

    // 使用 Default dispatcher 处理后台任务，避免主线程阻塞
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isAppInForeground = true

    init {
        // 提前加载 PRoot 原生库
        try {
            com.lin.hippyagent.core.linux.PRootBridge
            Timber.d("PRootBridge initialized in Application init")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize PRootBridge in Application init")
        }
    }

    private fun migrateLegacyPrefs() {
        val migrations = listOf(
            "androidpaw_settings" to "hippy_settings",
            "androidpaw_config" to "hippy_config"
        )
        for ((oldName, newName) in migrations) {
            val newPrefs = getSharedPreferences(newName, MODE_PRIVATE)
            if (newPrefs.contains("_migrated")) continue
            val oldPrefs = getSharedPreferences(oldName, MODE_PRIVATE)
            if (oldPrefs.all.isEmpty()) continue
            val editor = newPrefs.edit()
            for ((key, value) in oldPrefs.all) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
                }
            }
            editor.putBoolean("_migrated", true)
            editor.apply()
            Timber.i("Migrated prefs: $oldName -> $newName (${oldPrefs.all.size} keys)")
        }

        val dbMigrations = listOf("androidpaw.db" to "hippy.db", "commonmemory.db" to "commonmemory.db")
        for ((oldDb, newDb) in dbMigrations) {
            val newDbFile = getDatabasePath(newDb)
            if (newDbFile.exists()) continue
            val oldDbFile = getDatabasePath(oldDb)
            if (!oldDbFile.exists()) continue
            try {
                oldDbFile.renameTo(newDbFile)
                val oldWal = File(oldDbFile.parent, "$oldDb-wal")
                val newWal = File(newDbFile.parent, "$newDb-wal")
                if (oldWal.exists()) oldWal.renameTo(newWal)
                val oldShm = File(oldDbFile.parent, "$oldDb-shm")
                val newShm = File(newDbFile.parent, "$newDb-shm")
                if (oldShm.exists()) oldShm.renameTo(newShm)
                Timber.i("Migrated database: $oldDb -> $newDb")
            } catch (e: Exception) {
                Timber.e(e, "Failed to migrate database: $oldDb -> $newDb")
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() {
            val workerFactory = get<androidx.work.WorkerFactory>()
            return Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()
        }

    override fun onCreate() {
        super.onCreate()

        migrateLegacyPrefs()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 监听前后台状态
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isAppInForeground = true
            }
            override fun onStop(owner: LifecycleOwner) {
                isAppInForeground = false
            }
        })

        // ══════ 阶段 1：同步 — DI 容器必须先就绪 ══════
        startKoin {
            androidLogger()
            androidContext(this@HippyAgentApp)
            modules(appModule)
        }

        // ══════ 阶段 2：有序关键初始化（串行，确保依赖满足） ══════
        appScope.launch {
            try {
                // 2a. AgentRepository & 默认 Agent
                val repository = get<AgentRepository>()
                repository.createDefaultAgentIfNeeded()

                // 2b. 权限管理器 — 后续操作可能依赖权限状态
                val permissionManager = get<com.lin.hippyagent.core.security.PermissionManager>()
                permissionManager.initialize()
                Timber.d("PermissionManager initialized")

                // 2c. 默认模型提供商
                val providerStore = get<com.lin.hippyagent.core.model.ModelProviderStore>()
                providerStore.ensureDefaults()

                // 2d. CommonMemory 记忆数据库
                val memoryDb = get<MemoryDatabase>()
                Timber.d("CommonMemory MemoryDatabase initialized")
            } catch (e: Exception) {
                Timber.e(e, "Critical initialization failed")
            }

            // 2e. 工具注册 — 依赖 Agent/权限已就绪
            try {
                val badgeManager = get<com.lin.hippyagent.core.notification.BadgeManager>()
                badgeManager.startObserving()

                val toolInitializer = get<ToolInitializer>()
                toolInitializer.registerAllBuiltinTools()

                // 注册 Linux 工具
                val executeBashTool = get<com.lin.hippyagent.core.linux.tools.ExecuteBashTool>()
                val installPackageTool = get<com.lin.hippyagent.core.linux.tools.InstallPackageTool>()
                val executePythonTool = get<com.lin.hippyagent.core.linux.tools.ExecutePythonTool>()
                val fileTransferTool = get<com.lin.hippyagent.core.linux.tools.FileTransferTool>()
                val clipboardSyncTool = get<com.lin.hippyagent.core.linux.tools.ClipboardSyncTool>()
                val deviceAccessTool = get<com.lin.hippyagent.core.linux.tools.DeviceAccessTool>()
                val sshServerTool = get<com.lin.hippyagent.core.linux.tools.SshServerTool>()
                toolInitializer.registerLinuxTools(executeBashTool, installPackageTool, executePythonTool, fileTransferTool, clipboardSyncTool, deviceAccessTool, sshServerTool)

                // 启动 Linux KeepAliveService
                LinuxKeepAliveService.start(this@HippyAgentApp)
            } catch (e: Exception) {
                Timber.e(e, "Tool registration failed")
            }

            // 2f. HippyJobWorker
            try {
                val hippyJobWorker = get<com.lin.hippyagent.core.task.HippyJobWorker>()
                hippyJobWorker.start()
                Timber.d("HippyJobWorker started")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start HippyJobWorker")
            }
        }

        // ══════ 阶段 3：非关键后台任务（可延迟，不阻塞启动） ══════

        // 3a. 权限请求通知
        appScope.launch {
            val actionApprover = get<ActionApprover>()
            val notificationService = get<HippyAgentNotificationService>()
            actionApprover.pendingRequest.collect { request ->
                if (request != null && !isAppInForeground) {
                    notificationService.sendPermissionRequestNotification(request)
                }
            }
        }

        // 3b. 工具审批请求通知
        appScope.launch {
            val approvalManager = get<ToolApprovalManager>()
            val notificationService = get<HippyAgentNotificationService>()
            val notifiedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            approvalManager.pendingApprovals.collect { pendingList ->
                for (pending in pendingList) {
                    if (notifiedIds.add(pending.requestId) && !isAppInForeground) {
                        val severity = when (pending.riskLevel) {
                            ToolGuardian.RiskLevel.CRITICAL -> "critical"
                            ToolGuardian.RiskLevel.HIGH -> "high"
                            else -> "medium"
                        }
                        val findingsSummary = pending.findings.take(3).joinToString(", ") { it.title }
                        notificationService.sendToolApprovalNotification(
                            requestId = pending.requestId,
                            toolName = pending.toolName,
                            agentId = pending.agentId,
                            severity = severity,
                            findingsSummary = findingsSummary
                        )
                    }
                }
            }
        }

        // 3c. 内置技能安装（IO 密集，可延迟）
        appScope.launch(Dispatchers.IO) {
            try {
                val skillsDir = File(filesDir, "skills")
                skillsDir.mkdirs()
                val installed = BuiltinSkillRegistry.installBuiltinSkills(skillsDir)
                Timber.d("Installed ${installed.size} builtin skills to pool")
                val skillManager = get<com.lin.hippyagent.core.skill.SkillManager>()
                skillManager.rebuildIndex()
            } catch (e: Exception) {
                Timber.e(e, "Failed to install builtin skills")
            }
        }

        // 3d. SystemHookManager — 系统事件监听（非关键，不影响启动）
        appScope.launch {
            try {
                val systemHookManager = get<SystemHookManager>()
                systemHookManager.initialize()
                Timber.d("SystemHookManager initialized")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize SystemHookManager")
            }
        }

        // 3e. 网络恢复监听 — 自动重发离线消息
        appScope.launch {
            val networkMonitor = get<com.lin.hippyagent.core.network.NetworkMonitor>()
            val offlineQueue = get<com.lin.hippyagent.core.network.OfflineMessageQueue>()
            val agentFactory = get<com.lin.hippyagent.core.agent.AgentFactory>()
            val sessionStore = get<com.lin.hippyagent.core.agent.session.SessionStore>()

            // 设置重发回调
            offlineQueue.onNetworkRestored = { msg ->
                runCatching {
                    val agent = agentFactory.getAgent(msg.sessionId.substringBefore("_"))
                    if (agent != null) {
                        agent.processMessage(msg.sessionId, msg.channelId, msg.content)
                    } else {
                        Result.failure(IllegalStateException("Agent not found for offline message"))
                    }
                }
            }

            networkMonitor.observeNetworkState().collect { state ->
                if (state.isConnected) {
                    try {
                        offlineQueue.flushPendingMessages()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to flush offline messages")
                    }
                }
            }
        }

        // 3e. Dream Memory — 最不关键，放最后，且不阻塞启动
        appScope.launch(Dispatchers.IO) {
            try {
                // 延迟 5 秒，让关键初始化先完成
                kotlinx.coroutines.delay(5_000)
                val storageManager = get<com.lin.hippyagent.core.storage.StorageManager>()
                val memoryStore = get<com.lin.hippyagent.core.memory.MemoryStore>()
                val workingDir = storageManager.getWorkingDir()
                val dreamProcessor = DreamMemoryProcessor(workingDir, memoryStore)
                dreamProcessor.dreamMemory()
                Timber.d("Dream memory processing completed")
            } catch (e: Exception) {
                Timber.e(e, "Failed to process dream memory")
            }
        }

        // 3f. 静默环境检查 — Linux 就绪后检查 Node.js/npx/Python，缺失则自动安装
        appScope.launch(Dispatchers.IO) {
            try {
                val linuxManager = get<com.lin.hippyagent.core.linux.LinuxManager>()
                linuxManager.isReady.collect { ready ->
                    if (ready) {
                        linuxManager.silentEnsureEnvironment()
                        return@collect
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Silent environment check failed")
            }
        }
    }


}


