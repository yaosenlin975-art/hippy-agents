package com.lin.hippyagent.core.agent.collaboration

data class GroupChatConfig(
    val groupId: String,
    val groupName: String,
    val agentIds: List<String>,
    val maxRounds: Int = 10,
    val turnStrategy: TurnStrategy = TurnStrategy.ROUND_ROBIN,
    val maxSkipCount: Int = 3,
    val useLLMToTerminate: Boolean = false,
    val llmSelectorModel: String? = null,
    val llmSelectorProviderId: String? = null,
    val llmSelectorModelName: String? = null,
    val selectorTimeoutMs: Long = 5000
)

enum class TurnStrategy {
    ROUND_ROBIN,
    MODERATOR,
    REACTIVE,
    LLM_SELECTOR
}

data class SelectorDecision(
    val round: Int,
    val selectedAgentId: String,
    val reason: String? = null,
    val isFinish: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class GroupChatState(
    val groupId: String,
    val currentRound: Int = 0,
    val currentSpeakerIndex: Int = 0,
    val messages: List<GroupChatMessage> = emptyList(),
    val isComplete: Boolean = false,
    val consecutiveSkips: Int = 0,
    val selectorDecisions: List<SelectorDecision> = emptyList()
)

data class GroupChatMessage(
    val agentId: String,
    val content: String,
    val round: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val senderIsUser: Boolean = false,
    val quotedMessageId: String? = null,
    val quotedContent: String? = null,
    val quotedSenderName: String? = null
)
