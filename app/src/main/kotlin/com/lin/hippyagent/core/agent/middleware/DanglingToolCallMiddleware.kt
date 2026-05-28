package com.lin.hippyagent.core.agent.middleware

import com.lin.hippyagent.core.model.ModelMessage
import timber.log.Timber

class DanglingToolCallMiddleware : AgentMiddleware {

    private val handledDanglingIds = mutableSetOf<String>()
    private var consecutiveDanglingCount = 0

    override val priority: Int = PRIORITY
    override val name: String = NAME

    override fun beforeModel(context: MiddlewareContext): MiddlewareResult {
        val messages = context.messages
        val danglingIds = findDanglingToolCallIds(messages)
        if (danglingIds.isEmpty()) {
            consecutiveDanglingCount = 0
            return MiddlewareResult.Continue
        }

        val danglingSet = danglingIds.toSet()
        val repeatIds = danglingIds.filter { it in handledDanglingIds }

        if (repeatIds.isNotEmpty()) {
            consecutiveDanglingCount++
            if (consecutiveDanglingCount >= MAX_CONSECUTIVE_DANGLING) {
                Timber.w("DanglingToolCallMiddleware: death spiral detected ($consecutiveDanglingCount consecutive), pruning and removing empty assistant messages")
                pruneDanglingToolCalls(messages, danglingSet)
                removeEmptyAssistantMessages(messages)
                handledDanglingIds.addAll(danglingIds)
                return MiddlewareResult.Modify(messages.toList())
            }
        }

        val newIds = danglingIds.filter { it !in handledDanglingIds }
        if (newIds.isNotEmpty()) {
            Timber.w("Found ${newIds.size} new dangling tool calls, inserting synthetic tool messages and pruning tool_calls")

            val syntheticMessages = newIds.map { id ->
                ModelMessage(
                    role = "tool",
                    content = "[Tool call was interrupted and did not return a result.]",
                    toolCallId = id
                )
            }
            val lastAssistantIndex = messages.indexOfLast { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
            if (lastAssistantIndex >= 0) {
                messages.addAll(lastAssistantIndex + 1, syntheticMessages)
            } else {
                messages.addAll(syntheticMessages)
            }

            pruneDanglingToolCalls(messages, newIds.toSet())
            removeEmptyAssistantMessages(messages)
        } else {
            Timber.w("Found ${danglingIds.size} repeat dangling tool calls (already handled), pruning from tool_calls")
            pruneDanglingToolCalls(messages, danglingSet)
            removeEmptyAssistantMessages(messages)
        }

        handledDanglingIds.addAll(danglingIds)
        return MiddlewareResult.Modify(messages.toList())
    }

    override fun afterAgent(context: MiddlewareContext) {
        handledDanglingIds.clear()
        consecutiveDanglingCount = 0
    }

    private fun pruneDanglingToolCalls(messages: MutableList<ModelMessage>, idSet: Set<String>) {
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                val remaining = msg.toolCalls.filter { it.id !in idSet }
                if (remaining.isEmpty()) {
                    messages[i] = msg.copy(toolCalls = null)
                } else {
                    messages[i] = msg.copy(toolCalls = remaining)
                }
            }
        }
    }

    private fun removeEmptyAssistantMessages(messages: MutableList<ModelMessage>) {
        messages.removeAll { msg ->
            msg.role == "assistant" && msg.toolCalls.isNullOrEmpty() && msg.content.isBlank()
        }
    }

    private fun findDanglingToolCallIds(messages: List<ModelMessage>): List<String> {
        val calledToolIds = mutableSetOf<String>()
        val respondedToolIds = mutableSetOf<String>()

        for (msg in messages) {
            if (msg.role == "assistant") {
                msg.toolCalls?.forEach { tc ->
                    calledToolIds.add(tc.id)
                }
            }
            if (msg.role == "tool" && msg.toolCallId != null) {
                respondedToolIds.add(msg.toolCallId)
            }
        }

        return calledToolIds.subtract(respondedToolIds).toList()
    }

    companion object {
        const val PRIORITY = 20
        const val NAME = "dangling_tool_call"
        private const val MAX_CONSECUTIVE_DANGLING = 3
    }
}
