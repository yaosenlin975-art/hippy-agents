package com.lin.hippyagent.ui.navigation

sealed class Screen(val route: String) {
    object Sessions : Screen("sessions")
    object Settings : Screen("settings")
    object Chat : Screen("chat/{sessionId}/{agentId}") {
        fun createRoute(sessionId: String, agentId: String) = "chat/$sessionId/$agentId"
    }
    object RunningConfig : Screen("agent/{agentId}/running-config") {
        fun createRoute(agentId: String) = "agent/$agentId/running-config"
    }
    object Heartbeat : Screen("agent/{agentId}/heartbeat") {
        fun createRoute(agentId: String) = "agent/$agentId/heartbeat"
    }
    object MCP : Screen("agent/{agentId}/mcp") {
        fun createRoute(agentId: String) = "agent/$agentId/mcp"
    }
    object CoreFiles : Screen("agent/{agentId}/core-files") {
        fun createRoute(agentId: String) = "agent/$agentId/core-files"
    }
    object ACP : Screen("agent/{agentId}/acp") {
        fun createRoute(agentId: String) = "agent/$agentId/acp"
    }
    object CreateGroup : Screen("create-group")
    object GroupSettings : Screen("group/{groupId}/settings") {
        fun createRoute(groupId: String) = "group/$groupId/settings"
    }
    object ModelProvider : Screen("settings/model-provider")
    object ProviderDetail : Screen("settings/model-provider/{providerId}") {
        fun createRoute(providerId: String) = "settings/model-provider/$providerId"
    }
    object About : Screen("settings/about")
    object DebugInfo : Screen("settings/debug-info")
    object DataStorage : Screen("settings/data-storage")
    object ExportLog : Screen("settings/export-log")
    object Language : Screen("settings/language")
    object Notification : Screen("settings/notification")
    object AgentToolSecurity : Screen("agent/{agentId}/tool-security") {
        fun createRoute(agentId: String) = "agent/$agentId/tool-security"
    }
    object AccessibilitySetup : Screen("settings/accessibility-setup")
    object SkillPool : Screen("settings/skill-pool")
    object SkillStore : Screen("settings/skill-store?sourceAgentId={sourceAgentId}") {
        fun createRoute(sourceAgentId: String? = null) =
            if (sourceAgentId != null) "settings/skill-store?sourceAgentId=$sourceAgentId"
            else "settings/skill-store"
    }
    object AcpClient : Screen("settings/acp-client")
    object ChannelConfig : Screen("agent/{agentId}/channel-config") {
        fun createRoute(agentId: String) = "agent/$agentId/channel-config"
    }
    object QrAuth : Screen("agent/{agentId}/channel-qr-auth/{channelId}") {
        fun createRoute(agentId: String, channelId: String) = "agent/$agentId/channel-qr-auth/$channelId"
    }
    object SkillManagement : Screen("agent/{agentId}/skill-management") {
        fun createRoute(agentId: String) = "agent/$agentId/skill-management"
    }
    object Dream : Screen("agent/{agentId}/dream") {
        fun createRoute(agentId: String) = "agent/$agentId/dream"
    }
    object MemoryCompaction : Screen("agent/{agentId}/memory-compaction") {
        fun createRoute(agentId: String) = "agent/$agentId/memory-compaction"
    }
    object ToolApprovals : Screen("settings/tool-approvals")
    object SecurityRules : Screen("settings/security-rules")
    object ToolsList : Screen("settings/tools-list")
    object ToolDetail : Screen("settings/tools-list/{toolName}") {
        fun createRoute(toolName: String) = "settings/tools-list/$toolName"
    }
    object Insights : Screen("settings/insights")
    object Plugins : Screen("settings/plugins")
    object CronJobs : Screen("agent/{agentId}/cronjobs") {
        fun createRoute(agentId: String) = "agent/$agentId/cronjobs"
    }
    object ScheduleCreate : Screen("agent/{agentId}/schedule/create?sessionId={sessionId}") {
        fun createRoute(agentId: String, sessionId: String = "") =
            if (sessionId.isBlank()) "agent/$agentId/schedule/create?sessionId="
            else "agent/$agentId/schedule/create?sessionId=$sessionId"
    }
    object EnvCheck : Screen("settings/env-check")
    object UiSettings : Screen("settings/ui-settings")
    object GlobalRules : Screen("settings/global-rules")
    object PermissionCenter : Screen("settings/permission-center")
    object CommonMemory : Screen("settings/second-brain/{agentId}") {
        fun createRoute(agentId: String) = "settings/second-brain/$agentId"
    }
    object Inbox : Screen("inbox?tab={tab}") {
        fun createRoute(tab: String = "events") = "inbox?tab=$tab"
    }
    object CreateAgent : Screen("agent/create")
    object EnvVars : Screen("settings/env-vars")
    object SystemHooks : Screen("settings/system-hooks")
    object TaskList : Screen("settings/task-center")
    object TaskDetail : Screen("settings/task-center/{taskId}") {
        fun createRoute(taskId: String) = "settings/task-center/$taskId"
    }
    object NotificationCenter : Screen("settings/notification-center")
}

