package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelCallResponse
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

sealed class SelectorResult {
    data class SpeakerSelected(
        val agentId: String,
        val reason: String? = null
    ) : SelectorResult()

    object Finish : SelectorResult()
    object Continue : SelectorResult()

    data class Error(
        val message: String,
        val fallbackStrategy: TurnStrategy = TurnStrategy.ROUND_ROBIN
    ) : SelectorResult()
}

class LLMSpeakerSelector(
    private val modelClients: Map<String, ModelClient>,
    private val descriptionProvider: AgentDescriptionProvider,
    private val defaultModelId: String? = null,
    private val timeoutMs: Long = 5000
) {
    private companion object {
        val TERMINATE_KEYWORDS = setOf("yes", "y", "是", "完成", "结束", "done", "complete", "finished")
        val CONTINUE_KEYWORDS = setOf("no", "n", "否", "继续", "未完成", "continue", "not yet", "ongoing")
    }

    private fun parseTerminationResponse(text: String): Boolean? {
        val firstWord = text.trim().lowercase().split("\\s+".toRegex()).firstOrNull() ?: return null
        return when {
            firstWord in TERMINATE_KEYWORDS -> true
            firstWord in CONTINUE_KEYWORDS -> false
            else -> null
        }
    }
    suspend fun selectNextSpeaker(
        config: GroupChatConfig,
        state: GroupChatState
    ): SelectorResult {
        val modelId = config.llmSelectorModel ?: defaultModelId
            ?: return SelectorResult.Error("No model specified for LLM selector")

        val modelClient = if (!config.llmSelectorProviderId.isNullOrBlank() && !config.llmSelectorModelName.isNullOrBlank()) {
            modelClients["${config.llmSelectorProviderId}/${config.llmSelectorModelName}"]
        } else {
            modelClients[modelId]
        } ?: return SelectorResult.Error("Model client not found for selector")

        val agents = config.agentIds.map { agentId ->
            GroupChatPrompts.AgentInfo(
                id = agentId,
                description = descriptionProvider.getDescription(agentId)
            )
        }

        val prompt = GroupChatPrompts.buildSpeakerSelectionPrompt(
            agents = agents,
            history = state.messages,
            currentSpeakerId = null,
            excludeAgentId = null
        )

        return try {
            val request = ModelCallRequest(
                model = modelId,
                messages = listOf(
                    ModelMessage(role = "system", content = "你是一个群聊协调者。"),
                    ModelMessage(role = "user", content = prompt)
                ),
                temperature = 0.3f,
                maxTokens = 50,
                stream = false
            )

            val response = withTimeoutOrNull(timeoutMs, TimeUnit.MILLISECONDS) {
                modelClient.chatCompletion(request)
            } ?: return SelectorResult.Error("LLM call timeout after ${timeoutMs}ms", TurnStrategy.ROUND_ROBIN)

            val responseText = response.choices.firstOrNull()?.message?.content ?: ""
            val parseResult = GroupChatPrompts.parseLLMResponse(responseText, config.agentIds)

            when (parseResult) {
                is GroupChatPrompts.ParseResult.SpeakerSelected -> {
                    SelectorResult.SpeakerSelected(parseResult.agentId)
                }
                GroupChatPrompts.ParseResult.Finish -> {
                    SelectorResult.Finish
                }
                GroupChatPrompts.ParseResult.Continue -> {
                    SelectorResult.Continue
                }
                GroupChatPrompts.ParseResult.Invalid -> {
                    Timber.w("LLM returned invalid response: $responseText, falling back to ROUND_ROBIN")
                    SelectorResult.Error("Invalid LLM response: $responseText", TurnStrategy.ROUND_ROBIN)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "LLM speaker selection failed")
            SelectorResult.Error(e.message ?: "Unknown error", TurnStrategy.ROUND_ROBIN)
        }
    }

    suspend fun shouldTerminate(
        config: GroupChatConfig,
        state: GroupChatState
    ): Boolean {
        if (!config.useLLMToTerminate) return false

        val modelId = config.llmSelectorModel ?: defaultModelId
            ?: return false

        val modelClient = if (!config.llmSelectorProviderId.isNullOrBlank() && !config.llmSelectorModelName.isNullOrBlank()) {
            modelClients["${config.llmSelectorProviderId}/${config.llmSelectorModelName}"]
        } else {
            modelClients[modelId]
        } ?: return false

        val prompt = GroupChatPrompts.buildTerminationPrompt(
            history = state.messages,
            maxRounds = config.maxRounds,
            currentRound = state.currentRound
        )

        return try {
            val request = ModelCallRequest(
                model = modelId,
                messages = listOf(
                    ModelMessage(role = "user", content = prompt)
                ),
                temperature = 0.1f,
                maxTokens = 10,
                stream = false
            )

            val response = withTimeoutOrNull(timeoutMs, TimeUnit.MILLISECONDS) {
                modelClient.chatCompletion(request)
            } ?: return false

            val responseText = response.choices.firstOrNull()?.message?.content?.trim() ?: ""
            parseTerminationResponse(responseText) == true
        } catch (e: Exception) {
            Timber.e(e, "LLM termination check failed")
            false
        }
    }

    private suspend fun <T> withTimeoutOrNull(
        timeout: Long,
        unit: TimeUnit,
        block: suspend () -> T
    ): T? {
        return try {
            kotlinx.coroutines.withTimeoutOrNull(unit.toMillis(timeout)) {
                block()
            }
        } catch (e: Exception) {
            null
        }
    }
}