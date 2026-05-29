package com.lin.hippyagent.core.hooks.system

import android.content.Context
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.AgentRegistry
import com.lin.hippyagent.core.agent.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class SystemEventDispatcher(
    private val context: Context,
    private val agentRegistry: AgentRegistry,
    private val agentFactory: AgentFactory,
    private val sessionStore: SessionStore
) : SystemEventCallback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onEvent(event: SystemEvent) {
        scope.launch {
            try {
                val defaultProfile = agentRegistry.getDefaultAgent()
                if (defaultProfile == null) {
                    Timber.w("SystemHook: no default agent found")
                    return@launch
                }

                val agent = agentFactory.getAgent(defaultProfile.agentId)
                if (agent == null) {
                    Timber.w("SystemHook: agent instance not available: ${defaultProfile.agentId}")
                    return@launch
                }

                val sessionResult = sessionStore.createSession(
                    agentId = defaultProfile.agentId,
                    title = context.getString(R.string.system_event_session_title, event.type.description)
                )
                val sessionId = sessionResult.getOrNull()?.id
                    ?: "system_hook:${event.timestamp}"

                val message = buildEventPrompt(event)
                agent.processMessage(sessionId, "system_hook", message)
                Timber.d("System event ${event.type} dispatched to agent")
            } catch (e: Exception) {
                Timber.e(e, "Failed to handle system event: ${event.type}")
            }
        }
    }

    private fun buildEventPrompt(event: SystemEvent): String {
        return when (event.type) {
            SystemEventType.SMS_RECEIVED -> {
                val sender = event.payload["sender"] ?: "unknown"
                val body = event.payload["body"] ?: ""
                "收到来自 $sender 的短信: $body。请判断是否需要处理。"
            }
            SystemEventType.INCOMING_CALL -> {
                val number = event.payload["number"] ?: "unknown"
                "收到来自 $number 的来电。请判断是否需要处理。"
            }
            SystemEventType.NOTIFICATION_POSTED -> {
                val pkg = event.payload["package"] ?: "unknown"
                val title = event.payload["title"] ?: ""
                "收到来自 $pkg 的通知: $title。请判断是否需要处理。"
            }
            SystemEventType.BATTERY_LOW -> "电量低，请判断是否需要采取行动。"
            SystemEventType.BATTERY_CHARGING -> "设备开始充电。"
            SystemEventType.CALENDAR_EVENT -> "日历事件即将开始。"
            SystemEventType.APP_INSTALLED -> {
                val pkg = event.payload["package"] ?: "unknown"
                "应用 $pkg 已安装。"
            }
            SystemEventType.APP_UNINSTALLED -> {
                val pkg = event.payload["package"] ?: "unknown"
                "应用 $pkg 已卸载。"
            }
            SystemEventType.SCREEN_ON -> "屏幕已亮起。"
            SystemEventType.SCREEN_OFF -> "屏幕已关闭。"
            SystemEventType.CLIPBOARD_CHANGED -> "剪贴板内容已变化。"
            SystemEventType.BOOT_COMPLETED -> "系统启动完成。"
            SystemEventType.CALL_ENDED -> "通话已结束。"
            SystemEventType.NOTIFICATION_REMOVED -> "通知已移除。"
        }
    }
}
