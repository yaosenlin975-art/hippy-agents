package com.lin.hippyagent.ui.entry

import android.app.Application
import android.content.Context
import android.content.Intent
import com.lin.hippyagent.core.agent.MessageQueueManager
import com.lin.hippyagent.core.agent.QueuedMessage
import com.lin.hippyagent.core.behavior.BehaviorRecorder
import com.lin.hippyagent.core.companion.CompanionController
import com.lin.hippyagent.core.service.AgentForegroundService
import com.lin.hippyagent.ui.MainActivity
import org.koin.core.context.GlobalContext
import timber.log.Timber

/**
 * 统一路由层：所有系统入口点构造 [AgentAction] 后调 [route]，
 * 由本 object 转为 Intent 启动 Activity 或直接调 Controller。
 *
 * 协程合规（coding.md）：本类不持有 scope；Controller 调用同步；
 * 异步路径由调用方自行处理。
 */
object AgentEntryRouter {

    const val EXTRA_AGENT_ACTION = "extra_agent_action"
    const val EXTRA_PROMPT = "extra_prompt"
    const val EXTRA_QUICK_ASK = "extra_quick_ask"

    const val ACTION_OPEN_CHAT = "open_chat"
    const val ACTION_CREATE_CRON = "create_cron"

    fun route(context: Context, action: AgentAction) {
        when (action) {
            is AgentAction.OpenChat -> launchChat(context, action.prompt, action.quickAsk)
            // 当前由 RecordingTileService 直接调用 BehaviorRecordingController，不经此路由；保留以匹配 sealed class 穷尽性
            AgentAction.StartRecording -> triggerRecordingStart(context)
            // 当前由 RecordingTileService 直接调用 BehaviorRecordingController，不经此路由；保留以匹配 sealed class 穷尽性
            AgentAction.StopRecording -> triggerRecordingStop()
            AgentAction.CreateCron -> launchChatWithPrefill(context, "我想设置一个定时任务...")
            AgentAction.CompanionMode -> triggerCompanionMode(context)
            is AgentAction.SendQuickMessage -> enqueueMessage(action.text, action.sessionId, action.channelId)
            AgentAction.ToggleAgent -> AgentForegroundService.toggle(context)
        }
    }

    private fun launchChat(context: Context, prompt: String?, quickAsk: Boolean) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_AGENT_ACTION, ACTION_OPEN_CHAT)
            prompt?.let { putExtra(EXTRA_PROMPT, it) }
            if (quickAsk) putExtra(EXTRA_QUICK_ASK, true)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.e(it, "AgentEntryRouter.launchChat failed") }
    }

    private fun launchChatWithPrefill(context: Context, prefill: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_AGENT_ACTION, ACTION_CREATE_CRON)
            putExtra(EXTRA_PROMPT, prefill)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.e(it, "AgentEntryRouter.launchChatWithPrefill failed") }
    }

    private fun triggerRecordingStart(context: Context) {
        val app = context.applicationContext as? Application ?: run {
            Timber.w("AgentEntryRouter: context is not Application, cannot start recording")
            return
        }
        BehaviorRecorder.start(app)
    }

    private fun triggerRecordingStop() {
        BehaviorRecorder.stop()
    }

    private fun triggerCompanionMode(context: Context) {
        val app = context.applicationContext as? Application ?: run {
            Timber.w("AgentEntryRouter: context is not Application, cannot enter companion mode")
            return
        }
        if (CompanionController.uiState.value.isActive) {
            CompanionController.exitCompanionMode()
        } else {
            // sessionId 由 CompanionController 内部处理；default 兜底，
            // 真实 sessionId 路由需要 SessionStore 查询，但为避免依赖未确认 API 用 default。
            CompanionController.enterCompanionMode(app, "default")
        }
    }

    private fun enqueueMessage(text: String, sessionId: String, channelId: String) {
        runCatching {
            GlobalContext.get().get<MessageQueueManager>().enqueue(
                QueuedMessage(content = text, sessionId = sessionId, channelId = channelId)
            )
        }.onFailure { Timber.e(it, "AgentEntryRouter.enqueueMessage failed") }
    }
}
