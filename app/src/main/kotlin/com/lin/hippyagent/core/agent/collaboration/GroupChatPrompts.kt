package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.pool.StringBuilderPool

object GroupChatPrompts {
    private val mentionPattern = Regex("@([a-zA-Z0-9_-]+)")
    private val sbPool = StringBuilderPool()

    data class AgentInfo(
        val id: String,
        val description: String
    )

    fun buildSpeakerSelectionPrompt(
        agents: List<AgentInfo>,
        history: List<GroupChatMessage>,
        currentSpeakerId: String? = null,
        excludeAgentId: String? = null
    ): String = sbPool.use { sb ->
        sb.appendLine("你是一个群聊协调者，负责选择下一个发言的智能体。")
        sb.appendLine()
        sb.appendLine("## 可用的智能体：")
        agents.forEach { agent ->
            val prefix = if (agent.id == excludeAgentId) "[跳过] " else ""
            sb.appendLine("$prefix- **$agent.id**: ${agent.description}")
        }
        sb.appendLine()
        sb.appendLine("## 对话历史：")
        if (history.isEmpty()) {
            sb.appendLine("(暂无历史消息)")
        } else {
            history.takeLast(10).forEach { msg ->
                val speakerLabel = if (msg.senderIsUser) "用户" else msg.agentId
                sb.appendLine("[$speakerLabel]: ${msg.content.take(200)}")
            }
        }
        sb.appendLine()
        sb.appendLine("## 选择规则：")
        sb.appendLine("1. 根据对话内容，选择最应该发言的智能体")
        sb.appendLine("2. 如果认为对话已经完整，可以结束，请返回 FINISH")
        sb.appendLine("3. 只返回智能体 ID 或 FINISH，不要有其他内容")
        sb.toString()
    }

    fun buildTerminationPrompt(
        history: List<GroupChatMessage>,
        maxRounds: Int,
        currentRound: Int
    ): String = sbPool.use { sb ->
        sb.appendLine("你是一个群聊协调者，负责判断对话是否应该结束。")
        sb.appendLine()
        sb.appendLine("## 当前状态：")
        sb.appendLine("- 已完成轮次: $currentRound / $maxRounds")
        sb.appendLine()
        sb.appendLine("## 最近对话：")
        if (history.isEmpty()) {
            sb.appendLine("(暂无历史消息)")
        } else {
            history.takeLast(6).forEach { msg ->
                val speakerLabel = if (msg.senderIsUser) "用户" else msg.agentId
                sb.appendLine("[$speakerLabel]: ${msg.content.take(150)}")
            }
        }
        sb.appendLine()
        sb.appendLine("## 判断规则：")
        sb.appendLine("如果认为对话已经完成目标或继续下去没有意义，请返回 YES。")
        sb.appendLine("如果认为还需要更多讨论，请返回 NO。")
        sb.appendLine("只返回 YES 或 NO，不要有其他内容。")
        sb.toString()
    }

    fun buildAgentSystemPrompt(
        agentId: String,
        groupContext: GroupContext,
        mentionPath: MentionPath? = null,
        isCycleTarget: Boolean = false
    ): String = sbPool.use { sb ->
        val myDescription = groupContext.agentDescriptions[agentId]
        sb.appendLine("你正在参与群聊。你的 ID 是: $agentId")
        if (!myDescription.isNullOrBlank()) {
            sb.appendLine("你的身份: $myDescription")
        }
        sb.appendLine()
        sb.appendLine("## 群聊信息：")
        sb.appendLine("- 群组名称: ${groupContext.groupName}")
        sb.appendLine("- 当前轮次: ${groupContext.currentRound} / ${groupContext.maxRounds}")
        sb.appendLine()
        sb.appendLine("## 群聊中的其他智能体：")
        val others = groupContext.getOtherAgents(agentId)
        if (others.isEmpty()) {
            sb.appendLine("(暂无其他智能体)")
        } else {
            for ((id, desc) in others) {
                sb.appendLine("- **@$id**: $desc")
            }
        }
        sb.appendLine()
        sb.appendLine("## 如何 @ 其他智能体：")
        sb.appendLine("在回复中写 @智能体ID 即可，详见下方「群聊交流规则」")
        sb.appendLine()
        if (mentionPath != null) {
            sb.appendLine("## 提及链路")
            sb.appendLine("你被 @ 的路径：${mentionPath.toDisplayString()}")
            sb.appendLine("当前深度：${mentionPath.depth}/${mentionPath.maxDepth}")
            if (mentionPath.isMaxDepth) {
                sb.appendLine("深度已到上限，请不要再 @ 其他成员，直接给出最终回复。")
            }
        }
        if (isCycleTarget) {
            sb.appendLine()
            sb.appendLine("## ⚠ @ 回环检测")
            sb.appendLine("检测到你已被同一提及链中的人再次 @，这形成了 @ 回环。")
            sb.appendLine("请自行判断：如果讨论已有结论或你已无新内容补充，请直接给出最终回复，不要再 @ 其他人；如果确实需要继续讨论，可以继续 @ 对方。")
        }
        sb.appendLine()
        sb.appendLine("## 群聊交流规则：")
        sb.appendLine("1. 直接在回复中 @目标智能体，对方会在群组中回复")
        sb.appendLine("2. 多智能体讨论时，通过 @ 相互回复即可形成讨论")
        sb.appendLine("3. 如果检测到 @ 回环提示，请判断讨论是否已有结论，避免无意义的来回对话")
        sb.appendLine("4. 只在需要与其他智能体对话或传递任务时才使用@提及，如果只是总结、转述或回复用户则不要@其他智能体。过度使用@会导致不必要的消息传播和响应延迟。")
        sb.appendLine()
        sb.appendLine("## 如何引用之前的消息：")
        sb.appendLine("如果你需要引用之前群内某条消息进行回复，在消息开头使用以下格式：")
        sb.appendLine("> 引用的消息内容")
        sb.appendLine("---")
        sb.appendLine("你的回复内容")
        sb.appendLine("每条消息都有唯一编号（round），你可以在历史消息中看到。引用时系统会自动识别。")
        sb.toString()
    }

    fun parseLLMResponse(response: String, validAgentIds: List<String>): ParseResult {
        val trimmed = response.trim().uppercase()

        if (trimmed == "FINISH" || trimmed == "YES") {
            return ParseResult.Finish
        }

        if (trimmed == "NO") {
            return ParseResult.Continue
        }

        for (agentId in validAgentIds) {
            if (trimmed == agentId.uppercase() || trimmed == "\"$agentId\"".uppercase()) {
                return ParseResult.SpeakerSelected(agentId)
            }
        }

        val match = mentionPattern.find(trimmed)
        if (match != null) {
            val mentionedId = match.groupValues[1]
            if (mentionedId in validAgentIds.map { it.uppercase() }) {
                val actualId = validAgentIds.first { it.uppercase() == mentionedId }
                return ParseResult.SpeakerSelected(actualId)
            }
        }

        return ParseResult.Invalid
    }

    sealed class ParseResult {
        data class SpeakerSelected(val agentId: String) : ParseResult()
        object Finish : ParseResult()
        object Continue : ParseResult()
        object Invalid : ParseResult()
    }
}