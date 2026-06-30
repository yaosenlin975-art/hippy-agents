package com.lin.hippyagent.ui.entry

/**
 * 所有 Android 系统入口点（Shortcuts / QS Tile / RemoteInput / Widget / Notification）
 * 构造本 sealed class 实例后委托 [AgentEntryRouter.route] 统一路由。
 */
sealed class AgentAction {
    /** 打开 Chat；prompt 非空时预填，quickAsk=true 时进入快速提问模式 */
    data class OpenChat(val prompt: String? = null, val quickAsk: Boolean = false) : AgentAction()

    /** 开始行为录制 */
    object StartRecording : AgentAction()

    /** 停止行为录制 */
    object StopRecording : AgentAction()

    /** 新建定时任务（预填 prompt） */
    object CreateCron : AgentAction()

    /** 进入 Companion 模式 */
    object CompanionMode : AgentAction()

    /** 通知栏快速回复（RemoteInput），需要 sessionId/channelId 路由到对应会话 */
    data class SendQuickMessage(val text: String, val sessionId: String, val channelId: String) : AgentAction()

    /** 切换前台服务运行状态 */
    object ToggleAgent : AgentAction()
}
