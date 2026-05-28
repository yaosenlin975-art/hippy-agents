package com.lin.hippyagent.core.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ToolOwnership {
    OWNER_ONLY,
    SHARED,
    SYSTEM
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParameter>,
    val requiredPermissions: List<String> = emptyList(),
    val isAndroidSpecific: Boolean = false,
    val ownership: ToolOwnership = ToolOwnership.SHARED,
    /** 中文显示名，为空时回退到 [name] */
    val displayName: String = ""
)

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val defaultValue: Any? = null,
    val items: Map<String, Any>? = null
)

data class ToolCall(
    val toolName: String,
    val arguments: Map<String, Any>,
    val callId: String = com.lin.hippyagent.core.pool.FastId.next()
)

data class ToolResult(
    val callId: String,
    val success: Boolean,
    val output: String? = null,
    val forLLM: String? = null,
    val forUser: String? = null,
    val silent: Boolean = false,
    val async: Boolean = false,
    val responseHandled: Boolean = false,
    val media: List<String> = emptyList(),
    val artifactTags: List<String> = emptyList(),
    val error: String? = null,
    /** 是否需要用户授权才能继续（PRoot 返回 PERMISSION_NEEDED 时设置） */
    val needsPermissionApproval: Boolean = false,
    /** 需要授权的命令（needsPermissionApproval=true 时有效） */
    val permissionCommand: String? = null,
    /** 缺失的 Android 运行时权限列表 */
    val missingAndroidPermissions: List<String> = emptyList()
) {
    fun contentForLLM(): String = when {
        !forLLM.isNullOrBlank() -> forLLM
        !error.isNullOrBlank() -> error
        !output.isNullOrBlank() -> output
        else -> ""
    }

    fun contentForUser(): String? = when {
        silent -> null
        !forUser.isNullOrBlank() -> forUser
        !forLLM.isNullOrBlank() -> forLLM
        !output.isNullOrBlank() -> output
        else -> null
    }
}

data class ToolContext(
    val channel: String = "",
    val chatId: String = "",
    val messageId: String = "",
    val sessionId: String = "",
    val agentId: String = "",
    val workspace: java.io.File? = null
)

abstract class Tool {
    abstract val definition: ToolDefinition

    abstract suspend fun execute(arguments: Map<String, Any>): ToolResult

    open suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        return execute(args)
    }

    protected fun getRequiredArgument(arguments: Map<String, Any>, key: String): String {
        val value = arguments[key]?.toString()
        if (value.isNullOrEmpty()) {
            throw IllegalArgumentException("Missing required argument: $key")
        }
        return value
    }

    protected fun getOptionalArgument(arguments: Map<String, Any>, key: String, defaultValue: String? = null): String? {
        return arguments[key]?.toString() ?: defaultValue
    }
}

/** 内置工具英文名→中文显示名映射 */
object BuiltinToolNames {
    private val map = mapOf(
        // 通用
        "read_file" to "读取文件",
        "write_file" to "写入文件",
        "edit_file" to "编辑文件",
        "append_file" to "追加文件",
        "delete_file" to "删除文件",
        "glob_search" to "文件名搜索",
        "grep_search" to "内容搜索",
        "list_directory" to "列出目录",
        "get_current_time" to "获取当前时间",
        "load_skill" to "加载技能",
        "tool_search" to "工具搜索",
        // Shell & 环境
        "execute_bash" to "exec",
        "get_working_directory" to "获取工作目录",
        "get_environment" to "获取环境变量",
        "set_environment" to "设置环境变量",
        "delete_environment" to "删除环境变量",
        // 系统信息
        "get_system_info" to "获取系统信息",
        "read_logcat" to "读取日志",
        "vibrate" to "震动",
        // 音量
        "get_volume" to "获取音量",
        "set_volume" to "设置音量",
        // 应用
        "launch_app" to "启动应用",
        "list_apps" to "列出应用",
        // 剪贴板
        "read_clipboard" to "读取剪贴板",
        "write_clipboard" to "写入剪贴板",
        // 屏幕
        "get_screen_info" to "获取屏幕信息",
        "set_timezone" to "设置时区",
        "set_user_timezone" to "设置用户时区",
        "send_file" to "发送文件",
        "send_file_to_user" to "发送文件",
        "send_image" to "发送图片",
        "send_image_to_user" to "发送图片",
        "image_generate" to "生成图片",
        // 通知
        "send_notification" to "系统提示",
        // 媒体
        "view_image" to "查看图片",
        "view_video" to "查看视频",
        // WiFi & 蓝牙
        "get_wifi_info" to "获取WiFi信息",
        "bluetooth_control" to "蓝牙控制",
        "get_paired_devices" to "已配对设备",
        // 相机
        "take_photo" to "拍照",
        "record_video" to "录制视频",
        "take_screenshot" to "截屏",
        "start_recording" to "开始录制",
        // 传感器
        "read_sensor" to "读取传感器",
        // 通知
        "notification_read" to "读取通知",
        "notification_reply" to "回复通知",
        // 联系人
        "contact_list" to "联系人列表",
        "contact_search" to "搜索联系人",
        // 短信
        "sms_list" to "短信列表",
        "sms_send" to "发送短信",
        // 媒体控制
        "media_control" to "媒体控制",
        "search_media" to "搜索媒体",
        // 位置
        "get_location" to "获取位置",
        "get_current_location" to "获取当前位置",
        // 闹钟 & 日历
        "set_alarm" to "设置闹钟",
        "read_calendar" to "读取日历",
        "write_calendar" to "写入日历",
        // 通话
        "make_call" to "拨打电话",
        "read_call_log" to "通话记录",
        // 文档读取(技能管理)
        "read_pdf" to "读取PDF",
        "read_docx" to "读取Word",
        "read_xlsx" to "读取Excel",
        "read_pptx" to "读取PPT",
        "skill_read_file" to "文档读取器",
        // 引导 & 问答
        "guidance" to "引导助手",
        "qa_source_index" to "问答索引",
        // 网络
        "web_search" to "网页搜索",
        "web_fetch" to "网页抓取",
        // 群组
        "mention_in_group" to "群组@提及",
        "get_group_history" to "群组历史",
        // 智能体
        "check_agent_task" to "检查异步任务",
        "send_to_agent" to "发送给智能体",
        "delegate_to_agent" to "委托任务",
        "delegate_external_agent" to "委托外部智能体",
        "delegate_to_subagent" to "委托子智能体",
        "spawn_sub_agent" to "创建子智能体",
        "spawn_subagent" to "生成子智能体",
        "check_sub_agent_tasks" to "查询子智能体任务状态",
        "check_subagent_tasks" to "检查子智能体任务状态",
        "aggregate_sub_agent" to "汇总子智能体任务结果",
        "aggregate_subagent_tasks" to "汇总子智能体任务结果",
        "chat_with_agent" to "智能体间对话",
        "list_agents" to "列出智能体",
        // 系统
        "get_token_usage" to "Token用量",
        "export_log" to "导出日志",
        "cron" to "定时任务",
        // 辅助功能
        "screen_observe" to "观察屏幕",
        "screen_interact" to "屏幕交互",
        "phone_automate" to "手机自动化",
        "ask" to "提问",
        "list_plugins" to "列出插件",
        "connect_sms_bridge" to "短信桥接",
        // Linux
        "install_package" to "安装",
        "execute_python" to "py",
        "file_transfer" to "transfer",
        "clipboard_sync" to "写入剪贴板",
        "device_access" to "请求许可",
        "ssh_server" to "ssh",
        // 群组（Skill 动态工具）

        "get_file_size" to "获取文件大小",
        "get_text_lines_count" to "获取文本行数",
        "get_group_members" to "获取群成员",
        "create_plan" to "创建计划",
        "update_subtask_state" to "更新子任务状态",
        "revise_current_plan" to "修订当前计划",
        "finish_plan" to "完成当前计划",
        // 智能体管理
        "create_agent" to "创建智能体",
        "delete_agent" to "删除智能体",
        "update_agent" to "更新智能体",
        "agent_info" to "智能体信息",
        "send_message" to "发送消息",
        // 子任务
        "create_subagent_task" to "创建子任务",
        "get_subagent_status" to "获取子任务状态",
        "list_subagent_tasks" to "列出子任务",
        "cancel_subagent_task" to "取消子任务",
        // 问答 & 记忆
        "qa_index_sources" to "索引知识源",
        "qa_search" to "知识搜索",
        "memory_save" to "保存记忆",
        "memory_search" to "搜索记忆",
        "memory_delete" to "删除记忆",
        // 审批
        "approve_tool" to "批准工具",
        "deny_tool" to "拒绝工具",
        // 群组通信
        "send_group_message" to "发送群组消息",
        "mention_agent" to "@提及智能体",
        "broadcast_message" to "广播消息",
        // 执行命令
        "execute_command" to "执行命令",
    )
    fun getDisplayName(name: String): String = map[name] ?: ""
}

class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()
    private val _registeredTools = MutableStateFlow<Map<String, Tool>>(emptyMap())
    val registeredTools: Flow<Map<String, Tool>> = _registeredTools.asStateFlow()

    internal val ttlManager = ToolTTLManager()
    internal val accessController = ToolAccessController()
    private val hiddenTools = mutableSetOf<String>()
    private val deferredTools = mutableSetOf<String>()
    val deferredToolRegistry = DeferredToolRegistry()

    @Volatile
    var permissionChecker: ((permission: String) -> Boolean)? = null

    fun register(tool: Tool, ttl: Int? = null, hidden: Boolean = false, deferred: Boolean = false) {
        val def = tool.definition
        val displayName = BuiltinToolNames.getDisplayName(def.name)
        val wrappedTool = if (def.displayName.isEmpty() && displayName.isNotEmpty()) {
            val updatedDef = def.copy(displayName = displayName)
            object : Tool() {
                override val definition = updatedDef
                override suspend fun execute(arguments: Map<String, Any>): ToolResult = tool.execute(arguments)
                override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult = tool.execute(ctx, args)
            }
        } else {
            tool
        }

        tools[def.name] = wrappedTool
        if (ttl != null) ttlManager.setTTL(def.name, ttl)
        if (hidden) hiddenTools.add(def.name)
        if (deferred) {
            deferredTools.add(def.name)
            hiddenTools.add(def.name)
        }
        _registeredTools.value = tools.toMap()
    }

    fun unregister(name: String) {
        tools.remove(name)
        ttlManager.removeTTL(name)
        hiddenTools.remove(name)
        deferredTools.remove(name)
        _registeredTools.value = tools.toMap()
    }

    fun getDeferredToolNames(): Set<String> = deferredTools.toSet()

    fun getTool(name: String): Tool? = tools[name]

    fun getToolDefinition(name: String): ToolDefinition? = tools[name]?.definition

    fun getAllDefinitions(): List<ToolDefinition> = tools.values.map { it.definition }

    fun getVisibleDefinitions(): List<ToolDefinition> {
        return tools.values
            .filter { ttlManager.isVisible(it.definition.name) }
            .filter { it.definition.name !in hiddenTools }
            .map { it.definition }
            .sortedBy { it.name }
    }

    /**
     * 获取 Agent 可见的工具定义列表（全量注入，仅受访问控制过滤）。
     * @param agentId Agent ID
     */
    fun getDefinitionsForAgent(
        agentId: String
    ): List<ToolDefinition> {
        return tools.values
            .filter { ttlManager.isVisible(it.definition.name) }
            .filter { tool -> accessController.isToolAccessible(tool.definition.name, agentId, tool.definition.ownership) }
            .map { it.definition }
            .sortedBy { it.name }
    }

    fun setAgentToolAccess(agentId: String, toolNames: Set<String>) {
        accessController.setAgentToolAccess(agentId, toolNames)
    }

    fun isToolAccessibleByAgent(toolName: String, agentId: String): Boolean {
        val tool = tools[toolName] ?: return false
        if (!accessController.isAllowedByPolicy(toolName, agentId)) return false
        return accessController.isToolAccessible(toolName, agentId, tool.definition.ownership)
    }

    fun setAgentAllowList(agentId: String, patterns: List<String>) {
        accessController.setAgentAllowList(agentId, patterns)
    }

    fun setAgentDenyList(agentId: String, patterns: List<String>) {
        accessController.setAgentDenyList(agentId, patterns)
    }

    fun isToolAllowedByPolicy(toolName: String, agentId: String): Boolean {
        return accessController.isAllowedByPolicy(toolName, agentId)
    }

    fun tickTTL() {
        ttlManager.tick()
    }

    fun promoteTools(names: List<String>) {
        ttlManager.promote(names)
    }

    fun revealTools(names: List<String>) {
        hiddenTools.removeAll(names.toSet())
    }

    fun isToolVisible(name: String): Boolean {
        return name !in hiddenTools && ttlManager.isVisible(name)
    }

    fun clone(): ToolRegistry {
        val cloned = ToolRegistry()
        for ((name, tool) in tools) {
            cloned.tools[name] = tool
        }
        val (ttls, hidden) = ttlManager.copyState()
        cloned.ttlManager.restoreState(ttls, hidden)
        cloned.hiddenTools.addAll(hiddenTools)
        cloned._registeredTools.value = cloned.tools.toMap()
        return cloned
    }

    suspend fun executeTool(toolCall: ToolCall): ToolResult {
        val tool = tools[toolCall.toolName]
            ?: return ToolResult(
                callId = toolCall.callId,
                success = false,
                error = "Tool not found: ${toolCall.toolName}"
            )

        val permError = checkPermissions(tool)
        if (permError != null) return permError.copy(callId = toolCall.callId)

        return try {
            tool.execute(toolCall.arguments)
        } catch (e: Exception) {
            ToolResult(
                callId = toolCall.callId,
                success = false,
                error = e.message ?: "Unknown error"
            )
        }
    }

    suspend fun executeTool(toolCall: ToolCall, ctx: ToolContext): ToolResult {
        val tool = tools[toolCall.toolName]
            ?: return ToolResult(
                callId = toolCall.callId,
                success = false,
                error = "Tool not found: ${toolCall.toolName}"
            )

        val permError = checkPermissions(tool)
        if (permError != null) return permError.copy(callId = toolCall.callId)

        return try {
            tool.execute(ctx, toolCall.arguments)
        } catch (e: Exception) {
            ToolResult(
                callId = toolCall.callId,
                success = false,
                error = e.message ?: "Unknown error"
            )
        }
    }

    companion object {
        val CUSTOM_PERMISSIONS = setOf(
            "DEVICE_ACCESS", "CLIPBOARD_ACCESS", "SSH_SERVER",
            "FILE_TRANSFER", "SHELL_EXECUTE"
        )
    }

    private fun checkPermissions(tool: Tool): ToolResult? {
        val requiredPerms = tool.definition.requiredPermissions
        if (requiredPerms.isEmpty()) return null

        val checker = permissionChecker
        if (checker == null) {
            val permNames = requiredPerms.map { perm ->
                when (perm) {
                    "DEVICE_ACCESS" -> "设备访问"
                    "CLIPBOARD_ACCESS" -> "剪贴板访问"
                    "SSH_SERVER" -> "SSH 服务"
                    "FILE_TRANSFER" -> "文件传输"
                    "SHELL_EXECUTE" -> "Shell 执行"
                    else -> perm
                }
            }.distinct()
            return ToolResult(
                callId = "",
                success = false,
                output = "权限未配置，默认拒绝：${permNames.joinToString(", ")}。请设置 permissionChecker。",
                error = "Permission denied (no checker configured)"
            )
        }
        val missing = requiredPerms.filter { !checker(it) }
        if (missing.isEmpty()) return null

        val customMissing = missing.filter { it in CUSTOM_PERMISSIONS }
        val androidMissing = missing.filter { it !in CUSTOM_PERMISSIONS }

        if (customMissing.isNotEmpty()) {
            val permNames = customMissing.map { perm ->
                when (perm) {
                    "DEVICE_ACCESS" -> "设备访问"
                    "CLIPBOARD_ACCESS" -> "剪贴板访问"
                    "SSH_SERVER" -> "SSH 服务"
                    "FILE_TRANSFER" -> "文件传输"
                    "SHELL_EXECUTE" -> "Shell 执行"
                    else -> perm
                }
            }.distinct()
            return ToolResult(
                callId = "",
                success = false,
                error = "需要${permNames.joinToString("、")}权限才能使用此功能，请在弹出的对话框中授予权限后重试。",
                forUser = "需要${permNames.joinToString("、")}权限，请在弹出的对话框中授权。",
                needsPermissionApproval = true,
                permissionCommand = "CUSTOM_TOOL_PERM:${customMissing.joinToString(",")}"
            )
        }

        val permNames = androidMissing.map { perm ->
            when (perm) {
                "CAMERA" -> "相机"
                "RECORD_AUDIO" -> "麦克风"
                "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION" -> "位置"
                "READ_CONTACTS" -> "联系人"
                "READ_SMS", "SEND_SMS" -> "短信"
                "CALL_PHONE" -> "电话"
                "READ_CALL_LOG" -> "通话记录"
                "READ_CALENDAR", "WRITE_CALENDAR" -> "日历"
                "BLUETOOTH_CONNECT" -> "蓝牙"
                "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO" -> "媒体文件"
                else -> perm
            }
        }.distinct()

        return ToolResult(
            callId = "",
            success = false,
            error = "需要${permNames.joinToString("、")}权限才能使用此功能，请在弹出的对话框中授予权限后重试。",
            forUser = "需要${permNames.joinToString("、")}权限，请在设置中授予权限后重试。",
            missingAndroidPermissions = androidMissing
        )
    }

    fun hasTool(name: String): Boolean = name in tools
}

