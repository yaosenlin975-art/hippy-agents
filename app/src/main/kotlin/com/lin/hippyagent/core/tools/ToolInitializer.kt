package com.lin.hippyagent.core.tools

import android.content.Context
import com.lin.hippyagent.core.tools.android.*
import com.lin.hippyagent.core.tools.builtin.*
import com.lin.hippyagent.core.tools.builtin.ViewImageTool
import com.lin.hippyagent.core.tools.builtin.ViewVideoTool
import com.lin.hippyagent.core.linux.tools.ExecuteBashTool
import com.lin.hippyagent.core.linux.tools.InstallPackageTool
import com.lin.hippyagent.core.linux.tools.ExecutePythonTool
import com.lin.hippyagent.core.model.TokenUsageManager
import com.lin.hippyagent.core.accessibility.AccessibilityController
import com.lin.hippyagent.core.accessibility.ScreenObserveTool
import com.lin.hippyagent.core.accessibility.ScreenInteractTool
import com.lin.hippyagent.core.accessibility.PhoneAutomator
import com.lin.hippyagent.core.accessibility.PhoneAutomateTool
import com.lin.hippyagent.core.accessibility.SmartPerceptionLayer
import com.lin.hippyagent.core.accessibility.SmartPerceptionLayerFactory
import com.lin.hippyagent.core.tools.web.BrowserAutomationTool
import com.lin.hippyagent.core.tools.web.PageContentExtractor
import com.lin.hippyagent.core.tools.web.WebViewController
import com.lin.hippyagent.core.tools.web.WebFetchTool
import com.lin.hippyagent.core.tools.web.WebSearchTool
import com.lin.hippyagent.core.agent.collaboration.AcpClientStore
import com.lin.hippyagent.core.agent.collaboration.DelegateExternalAgentTool
import com.lin.hippyagent.core.agent.subagent.SpawnSubAgentTool
import com.lin.hippyagent.core.agent.subagent.CheckSubAgentTasksTool
import com.lin.hippyagent.core.agent.subagent.AggregateSubAgentResultsTool
import com.lin.hippyagent.core.agent.subagent.SubAgentOrchestrator
import com.lin.hippyagent.core.agent.subagent.SubAgentAggregator
import com.lin.hippyagent.core.cron.CronJobManager
import timber.log.Timber

class ToolInitializer(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val tokenUsageManager: TokenUsageManager,
    private val accessibilityController: AccessibilityController? = null,
    private val phoneAutomator: PhoneAutomator? = null,
    private val acpClientStore: AcpClientStore? = null,
    private val linuxManager: com.lin.hippyagent.core.linux.LinuxManager? = null,
    private val fileLockManager: FileLockManager? = null,
    private val skillLifecycleManager: com.lin.hippyagent.core.skill.SkillLifecycleManager? = null,
    private val subAgentOrchestrator: SubAgentOrchestrator? = null,
    private val subAgentAggregator: SubAgentAggregator? = null,
    private val agentFactory: com.lin.hippyagent.core.agent.AgentFactory? = null,
    private val cronJobManager: CronJobManager? = null,
    private val configStorage: com.lin.hippyagent.core.storage.ConfigStorage? = null,
    private val skillManager: com.lin.hippyagent.core.skill.SkillManager? = null,
    private val inboxStore: com.lin.hippyagent.core.inbox.InboxStore? = null,
    private val sessionStore: com.lin.hippyagent.core.agent.session.SessionStore? = null,
    private val agentRepository: com.lin.hippyagent.data.repository.AgentRepository? = null,
    private val memoryRepository: com.lin.hippyagent.core.memory.commonmemory.MemoryRepository? = null
) {
    fun registerAllBuiltinTools() {
        toolRegistry.register(GetCurrentTimeTool())
        toolRegistry.register(AskClarificationTool())

        skillManager?.let { mgr ->
            toolRegistry.register(LoadSkillTool(mgr, agentRepository, skillLifecycleManager))
        }

        toolRegistry.register(ToolSearchTool(toolRegistry.deferredToolRegistry))

        toolRegistry.register(ReadFileTool(fileLockManager))
        toolRegistry.register(WriteFileTool(context, fileLockManager))
        toolRegistry.register(EditFileTool(fileLockManager))
        toolRegistry.register(AppendFileTool(context, fileLockManager))
        toolRegistry.register(DeleteFileTool(fileLockManager))
        toolRegistry.register(GlobSearchTool())
        toolRegistry.register(GrepSearchTool())
        toolRegistry.register(ListDirectoryTool())

        toolRegistry.register(GetWorkingDirectoryTool())
        toolRegistry.register(GetEnvironmentTool(configStorage))
        if (configStorage != null) {
            toolRegistry.register(SetEnvironmentTool(configStorage))
            toolRegistry.register(DeleteEnvironmentTool(configStorage))
        }

        toolRegistry.register(GetSystemInfoTool(context))
        toolRegistry.register(VibrateTool(context), deferred = true)
        toolRegistry.register(GetVolumeTool(context), deferred = true)
        toolRegistry.register(SetVolumeTool(context), deferred = true)
        toolRegistry.register(LaunchAppTool(context), deferred = true)
        toolRegistry.register(ListAppsTool(context), deferred = true)
        toolRegistry.register(ReadClipboardTool(context), deferred = true)
        toolRegistry.register(WriteClipboardTool(context), deferred = true)
        toolRegistry.register(GetScreenInfoTool(context), deferred = true)
        toolRegistry.register(SetUserTimezoneTool(context), deferred = true)
        toolRegistry.register(SendFileToUserTool(context), deferred = true)
        toolRegistry.register(SendImageToUserTool(context), deferred = true)
        toolRegistry.register(GetTokenUsageTool(tokenUsageManager))

        memoryRepository?.let { repo ->
            toolRegistry.register(MemorySearchTool(repo))
        }
        toolRegistry.register(ReadLogcatTool())

        toolRegistry.register(SendNotificationTool(context, inboxStore), deferred = true)

        toolRegistry.register(ViewImageTool(context))
        toolRegistry.register(ViewVideoTool(context))

        toolRegistry.register(GetWifiInfoTool(context), deferred = true)
        toolRegistry.register(BluetoothControlTool(context), deferred = true)
        toolRegistry.register(GetPairedDevicesTool(context), deferred = true)

        toolRegistry.register(TakePhotoTool(context), deferred = true)
        toolRegistry.register(RecordVideoTool(context), deferred = true)
        toolRegistry.register(TakeScreenshotTool(context), deferred = true)
        toolRegistry.register(StartRecordingTool(context), deferred = true)

        toolRegistry.register(ReadSensorTool(context), deferred = true)
        toolRegistry.register(NotificationReadTool(context), deferred = true)
        toolRegistry.register(NotificationReplyTool(context), deferred = true)

        toolRegistry.register(ContactListTool(context), deferred = true)
        toolRegistry.register(ContactSearchTool(context), deferred = true)
        toolRegistry.register(SmsListTool(context), deferred = true)
        toolRegistry.register(SmsSendTool(context), deferred = true)
        toolRegistry.register(MediaControlTool(context), deferred = true)
        toolRegistry.register(SearchMediaTool(context), deferred = true)
        toolRegistry.register(GetCurrentLocationTool(context), deferred = true)
        toolRegistry.register(SetAlarmTool(context), deferred = true)
        toolRegistry.register(ReadCalendarTool(context), deferred = true)
        toolRegistry.register(WriteCalendarTool(context), deferred = true)
        toolRegistry.register(MakeCallTool(context), deferred = true)
        toolRegistry.register(ReadCallLogTool(context), deferred = true)

        toolRegistry.register(com.lin.hippyagent.core.tools.builtin.GetFileSizeTool())
        toolRegistry.register(com.lin.hippyagent.core.tools.builtin.GetTextLinesCountTool())

        toolRegistry.register(WebFetchTool(context))
        toolRegistry.register(WebSearchTool(), deferred = true)

        val webViewController = WebViewController(context)
        val pageExtractor = PageContentExtractor(webViewController)
        toolRegistry.register(BrowserAutomationTool(webViewController, pageExtractor))

        cronJobManager?.let { manager ->
            toolRegistry.register(CronTool(manager))
        }

        acpClientStore?.let { store ->
            toolRegistry.register(DelegateExternalAgentTool(store), deferred = true)
        }

        accessibilityController?.let { ctrl ->
            val smartLayer = SmartPerceptionLayerFactory.create(
                controller = ctrl,
                modelClient = null,
                vlmModelName = null
            )
            toolRegistry.register(ScreenObserveTool(ctrl, smartLayer), deferred = true)
            toolRegistry.register(ScreenInteractTool(ctrl), deferred = true)
        }

        phoneAutomator?.let { automator ->
            toolRegistry.register(PhoneAutomateTool(automator), deferred = true)
        }

        subAgentOrchestrator?.let { orchestrator ->
            subAgentAggregator?.let { aggregator ->
                toolRegistry.register(SpawnSubAgentTool(orchestrator))
                toolRegistry.register(CheckSubAgentTasksTool(orchestrator))
                toolRegistry.register(AggregateSubAgentResultsTool(orchestrator, aggregator))
            }
        }

        agentFactory?.let { factory ->
            toolRegistry.register(com.lin.hippyagent.core.agent.collaboration.ChatWithAgentTool(factory, sessionStore))
            toolRegistry.register(com.lin.hippyagent.core.agent.collaboration.ListAgentsTool(factory))
        }

        Timber.i("All builtin tools registered: ${toolRegistry.getAllDefinitions().size} tools, ${toolRegistry.getVisibleDefinitions().size} visible")
    }

    fun registerLinuxTools(
        executeBashTool: ExecuteBashTool,
        installPackageTool: InstallPackageTool,
        executePythonTool: ExecutePythonTool,
        fileTransferTool: com.lin.hippyagent.core.linux.tools.FileTransferTool? = null,
        clipboardSyncTool: com.lin.hippyagent.core.linux.tools.ClipboardSyncTool? = null,
        deviceAccessTool: com.lin.hippyagent.core.linux.tools.DeviceAccessTool? = null,
        sshServerTool: com.lin.hippyagent.core.linux.tools.SshServerTool? = null
    ) {
        toolRegistry.register(executeBashTool)
        toolRegistry.register(installPackageTool)
        toolRegistry.register(executePythonTool)

        // Android 集成工具
        fileTransferTool?.let { toolRegistry.register(it) }
        clipboardSyncTool?.let { toolRegistry.register(it) }
        deviceAccessTool?.let { toolRegistry.register(it) }

        // SSH 服务器工具
        sshServerTool?.let { toolRegistry.register(it) }

        Timber.i("Linux tools registered")
    }

    fun getToolSummary(): Map<String, List<String>> {
        val allDefs = toolRegistry.getAllDefinitions()
        return mapOf(
            "builtin" to allDefs.filter { !it.isAndroidSpecific }.map { it.name },
            "android" to allDefs.filter { it.isAndroidSpecific }.map { it.name }
        )
    }
}

