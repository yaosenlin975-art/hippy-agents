package com.lin.hippyagent.ui.chat

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.lin.hippyagent.core.agent.AgentStatus
import com.lin.hippyagent.core.agent.session.BadgeLevel
import com.lin.hippyagent.core.agent.session.Session
import com.lin.hippyagent.core.chat.ChatTurn
import com.lin.hippyagent.core.mission.MissionState

/**
 * Delivery Scope 管理- 管理所ChatViewModel deliveryScope 生命周期
 * 确保旧的 scope 在新ViewModel 创建时被取消，避免内存泄
 */
object DeliveryScopeManager {
    private val activeScopes = java.util.concurrent.ConcurrentHashMap<String, CoroutineScope>()

    fun getOrCreateScope(sessionId: String = "default"): CoroutineScope = synchronized(this) {
        activeScopes.getOrPut(sessionId) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }

    fun cancelScope(sessionId: String) = synchronized(this) {
        activeScopes.remove(sessionId)?.cancel()
    }

    fun cancelAll() = synchronized(this) {
        activeScopes.values.forEach { it.cancel() }
        activeScopes.clear()
    }
}

enum class SessionPhase {
    LOADING,
    READY,
    ERROR
}

@Immutable
data class ChatUiState(
    val sessionId: String = "",
    val sessionTitle: String = "",
    val agentId: String = "",
    val turns: List<ChatTurn> = emptyList(),
    val agentStatus: AgentStatus = AgentStatus.IDLE,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedModel: String = "",
    val selectedProviderId: String = "",
    val availableModels: List<Triple<String, String, String>> = emptyList(),
    val activeMission: MissionState? = null,
    val allSessions: List<Session> = emptyList(),
    val sessionBadges: Map<String, BadgeLevel> = emptyMap(),
    val sessionUnreadCounts: Map<String, Int> = emptyMap(),
    val sessionStatuses: Map<String, AgentStatus> = emptyMap(),
    val sessionPhase: SessionPhase = SessionPhase.LOADING,
    val messageQueueSize: Int = 0,
    val agentName: String = "",
    val showFreeModelWarning: Boolean = false,
    val freeModelKeys: Set<String> = emptySet(),
    val pendingSendText: String? = null,
    val pendingSendChips: List<InputChip> = emptyList(),
    val isMultiSelectMode: Boolean = false,
    val selectedMessageIds: Set<String> = emptySet(),
    val iterationExhausted: Boolean = false,
    val autoDecidedMode: String? = null,
    val autoDecidedModeSource: String? = null,
    val autoDecidedModeReasoning: String? = null,
    /** 触发本次 auto 决策的用户 turn.id；用于在用户消息下显示 AutoDecisionHint */
    val autoDecidedModeTurnId: String? = null,
    val selectedModeLocked: Boolean = false,
    /** Auto/Work 模式正在 LLM 决策中 (决策完成前显示「决策中」状态) */
    val isModeDeciding: Boolean = false,
    /** B3 隐私模式：强制所有 LLM 调用走端侧模型 */
    val privacyMode: Boolean = false,
    /** 端侧模型是否已加载（用于控制隐私模式开关可用性） */
    val onDeviceModelReady: Boolean = false
)

@Immutable
data class StreamingState(
    val streamingContent: String = "",
    val streamingThinkingContent: String = "",
    val streamingTurnId: String? = null
)
