package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.agent.group.GroupCollaborationProtocol
import com.lin.hippyagent.core.agent.group.MentionExchange
import com.lin.hippyagent.core.agent.group.detectNewTask
import com.lin.hippyagent.core.agent.group.detectQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentGroup 群聊引擎纯逻辑测试：@提及解析、群聊协议（ping-pong 停止）、LLM 决策解析、Prompt 构建。
 * AgentGroup 主流程（LLM 调用/队列调度）依赖 AgentFactory/SessionStore 等重型组件，不在此覆盖。
 */
class AgentGroupLogicTest {

    // ═══════════ MentionParser ═══════════

    @Test
    fun mentionParser_extractsEnglishAndChineseMentions() {
        val parsed = MentionParser.parse("@alice 和 @小明 请看一下 @bob-2")
        assertEquals(listOf("alice", "小明", "bob-2"), parsed)
    }

    @Test
    fun mentionParser_deduplicates() {
        val parsed = MentionParser.parse("@alice @alice @bob")
        assertEquals(listOf("alice", "bob"), parsed)
    }

    @Test
    fun mentionParser_emptyMessage_returnsEmpty() {
        assertTrue(MentionParser.parse("没有提及").isEmpty())
    }

    @Test
    fun mentionParser_hasMentions() {
        assertTrue(MentionParser.hasMentions("请 @alice 处理"))
        assertFalse(MentionParser.hasMentions("没有提及任何人"))
    }

    @Test
    fun mentionParser_removeMentions_cleansMessage() {
        val cleaned = MentionParser.removeMentions("  @alice  请  @bob  处理  ")
        assertEquals("请 处理", cleaned)
    }

    @Test
    fun mentionParser_formatMentions() {
        assertEquals("@a @b", MentionParser.formatMentions(listOf("a", "b")))
    }

    // ═══════════ 群聊协议: ping-pong 停止判断 ═══════════

    @Test
    fun shouldStopPingPong_lessThanTwoExchanges_false() {
        val protocol = GroupCollaborationProtocol()
        assertFalse(protocol.shouldStopPingPong(emptyList()))
        assertFalse(
            protocol.shouldStopPingPong(
                listOf(MentionExchange("a", "b", 1, hasNewTask = false, hasQuestion = false, hasDecision = false))
            )
        )
    }

    @Test
    fun shouldStopPingPong_twoIdleExchanges_true() {
        val protocol = GroupCollaborationProtocol()
        val exchanges = listOf(
            MentionExchange("a", "b", 1, hasNewTask = false, hasQuestion = false, hasDecision = false),
            MentionExchange("b", "a", 2, hasNewTask = false, hasQuestion = false, hasDecision = false)
        )
        assertTrue(protocol.shouldStopPingPong(exchanges))
    }

    @Test
    fun shouldStopPingPong_newTaskInRecent_false() {
        val protocol = GroupCollaborationProtocol()
        val exchanges = listOf(
            MentionExchange("a", "b", 1, hasNewTask = true, hasQuestion = false, hasDecision = false),
            MentionExchange("b", "a", 2, hasNewTask = false, hasQuestion = false, hasDecision = false)
        )
        assertFalse(protocol.shouldStopPingPong(exchanges))
    }

    @Test
    fun shouldStopPingPong_questionInRecent_false() {
        val protocol = GroupCollaborationProtocol()
        val exchanges = listOf(
            MentionExchange("a", "b", 1, hasNewTask = false, hasQuestion = true, hasDecision = false),
            MentionExchange("b", "a", 2, hasNewTask = false, hasQuestion = false, hasDecision = false)
        )
        assertFalse(protocol.shouldStopPingPong(exchanges))
    }

    @Test
    fun shouldStopPingPong_decisionInRecent_false() {
        val protocol = GroupCollaborationProtocol()
        val exchanges = listOf(
            MentionExchange("a", "b", 1, hasNewTask = false, hasQuestion = false, hasDecision = true),
            MentionExchange("b", "a", 2, hasNewTask = false, hasQuestion = false, hasDecision = false)
        )
        assertFalse(protocol.shouldStopPingPong(exchanges))
    }

    @Test
    fun shouldStopPingPong_onlyLastTwoConsidered() {
        val protocol = GroupCollaborationProtocol()
        val exchanges = listOf(
            MentionExchange("a", "b", 1, hasNewTask = true, hasQuestion = false, hasDecision = false),
            MentionExchange("b", "a", 2, hasNewTask = false, hasQuestion = false, hasDecision = false),
            MentionExchange("a", "b", 3, hasNewTask = false, hasQuestion = false, hasDecision = false)
        )
        assertTrue(protocol.shouldStopPingPong(exchanges))
    }

    // ═══════════ 消息意图检测 ═══════════

    @Test
    fun detectNewTask_matchesTaskVerbs() {
        assertTrue(detectNewTask("请帮我写一个文档"))
        assertTrue(detectNewTask("执行这个任务"))
        assertFalse(detectNewTask("今天天气不错"))
    }

    @Test
    fun detectQuestion_matchesQuestionWords() {
        assertTrue(detectQuestion("这个方案怎么样？"))
        assertTrue(detectQuestion("为什么这样做?"))
        assertTrue(detectQuestion("How to fix it"))
        assertFalse(detectQuestion("好的，没问题"))
    }

    // ═══════════ LLM 决策解析 ═══════════

    @Test
    fun parseLLMResponse_finishAndYes_returnsFinish() {
        assertEquals(GroupChatPrompts.ParseResult.Finish, GroupChatPrompts.parseLLMResponse("FINISH", listOf("a")))
        assertEquals(GroupChatPrompts.ParseResult.Finish, GroupChatPrompts.parseLLMResponse("YES", listOf("a")))
        assertEquals(GroupChatPrompts.ParseResult.Finish, GroupChatPrompts.parseLLMResponse(" finish ", listOf("a")))
    }

    @Test
    fun parseLLMResponse_no_returnsContinue() {
        assertEquals(GroupChatPrompts.ParseResult.Continue, GroupChatPrompts.parseLLMResponse("NO", listOf("a")))
    }

    @Test
    fun parseLLMResponse_exactAgentId_returnsSpeaker() {
        assertEquals(
            GroupChatPrompts.ParseResult.SpeakerSelected("alice"),
            GroupChatPrompts.parseLLMResponse("alice", listOf("alice", "bob"))
        )
        assertEquals(
            GroupChatPrompts.ParseResult.SpeakerSelected("alice"),
            GroupChatPrompts.parseLLMResponse("ALICE", listOf("alice", "bob"))
        )
        assertEquals(
            GroupChatPrompts.ParseResult.SpeakerSelected("alice"),
            GroupChatPrompts.parseLLMResponse("\"alice\"", listOf("alice", "bob"))
        )
    }

    @Test
    fun parseLLMResponse_mentionFormat_returnsSpeaker() {
        assertEquals(
            GroupChatPrompts.ParseResult.SpeakerSelected("bob"),
            GroupChatPrompts.parseLLMResponse("@BOB 来说两句", listOf("alice", "bob"))
        )
    }

    @Test
    fun parseLLMResponse_unknownContent_returnsInvalid() {
        assertEquals(GroupChatPrompts.ParseResult.Invalid, GroupChatPrompts.parseLLMResponse("随便聊聊", listOf("alice")))
        assertEquals(GroupChatPrompts.ParseResult.Invalid, GroupChatPrompts.parseLLMResponse("CAROL", listOf("alice", "bob")))
    }

    // ═══════════ Prompt 构建 ═══════════

    @Test
    fun buildSpeakerSelectionPrompt_marksExcludedAgent() {
        val prompt = GroupChatPrompts.buildSpeakerSelectionPrompt(
            agents = listOf(GroupChatPrompts.AgentInfo("a", "desc a"), GroupChatPrompts.AgentInfo("b", "desc b")),
            history = emptyList(),
            excludeAgentId = "a"
        )
        assertTrue(prompt.contains("[跳过] - **a**"))
        assertFalse(prompt.contains("[跳过] - **b**"))
    }

    @Test
    fun buildSpeakerSelectionPrompt_truncatesHistoryTo10() {
        val history = (1..20).map {
            GroupChatMessage(agentId = "a", content = "消息 $it", round = it)
        }
        val prompt = GroupChatPrompts.buildSpeakerSelectionPrompt(
            agents = listOf(GroupChatPrompts.AgentInfo("a", "desc")),
            history = history
        )
        assertTrue(prompt.contains("消息 20"))
        assertFalse(prompt.contains("消息 10"))
    }

    @Test
    fun buildTerminationPrompt_showsRoundProgress() {
        val prompt = GroupChatPrompts.buildTerminationPrompt(emptyList(), maxRounds = 100, currentRound = 42)
        assertTrue(prompt.contains("42 / 100"))
    }

    @Test
    fun buildAgentSystemPrompt_listsOthersAndRules() {
        val context = GroupContext(
            groupId = "g1",
            groupName = "测试群",
            allAgentIds = listOf("a", "b"),
            agentDescriptions = mapOf("a" to "A的描述", "b" to "B的描述"),
            recentMessages = emptyList(),
            currentRound = 1,
            maxRounds = 10
        )
        val prompt = GroupChatPrompts.buildAgentSystemPrompt("a", context)
        assertTrue(prompt.contains("@b"))
        assertTrue(prompt.contains("A的描述"))
        assertTrue(prompt.contains("群聊交流规则"))
    }

    // ═══════════ AgentGroupConfig ═══════════

    @Test
    fun agentGroupConfig_defaults() {
        val config = AgentGroupConfig()
        assertFalse(config.enableLLMSpeakerSelection)
        assertFalse(config.enablePingPongDetection)
        assertFalse(config.enableLLMTermination)
        assertEquals(100, config.maxRounds)
        assertEquals(5000L, config.selectorTimeoutMs)
    }

    // ═══════════ senderIsUser 归因与路由 ═══════════

    private fun routerWith(agentIds: List<String>): GroupMessageRouter {
        val queues = agentIds.associateWith { AgentMessageQueue(it) }
        return GroupMessageRouter(groupId = "g1", agentQueues = queues)
    }

    private fun userMessage(content: String): GroupChatMessage =
        GroupChatMessage(agentId = USER_ID, content = content, round = 1, senderIsUser = true)

    private fun agentMessage(agentId: String, content: String): GroupChatMessage =
        GroupChatMessage(agentId = agentId, content = content, round = 1, senderIsUser = false)

    @Test
    fun userMessage_withoutMentions_routesToAllAgents() {
        val result = kotlinx.coroutines.runBlocking {
            routerWith(listOf("a", "b")).route(userMessage("大家好"))
        }
        assertEquals(setOf("a", "b"), result.deliverToAgents.toSet())
    }

    @Test
    fun agentMessage_withoutMentions_routesToNoOne() {
        val result = kotlinx.coroutines.runBlocking {
            routerWith(listOf("a", "b")).route(agentMessage("a", "这段内容只对 a 自己可见"))
        }
        assertTrue(result.deliverToAgents.isEmpty())
    }

    @Test
    fun agentMessage_withMentions_routesOnlyToMentioned() {
        val result = kotlinx.coroutines.runBlocking {
            routerWith(listOf("a", "b", "c")).route(agentMessage("a", "@b 请确认方案"))
        }
        assertEquals(listOf("b"), result.deliverToAgents)
    }

    @Test
    fun userMessage_withMentions_routesOnlyToMentioned() {
        val result = kotlinx.coroutines.runBlocking {
            routerWith(listOf("a", "b", "c")).route(userMessage("@c 请处理"))
        }
        assertEquals(listOf("c"), result.deliverToAgents)
    }

    // ═══════════ 发言者标签: senderIsUser 决定展示为「用户」还是 agentId ═══════════

    @Test
    fun speakerSelectionPrompt_labelsAgentAndUserMessages() {
        val history = listOf(
            agentMessage("a", "我来处理 @b"),
            userMessage("好的，谢谢")
        )
        val prompt = GroupChatPrompts.buildSpeakerSelectionPrompt(
            agents = listOf(GroupChatPrompts.AgentInfo("a", "desc a"), GroupChatPrompts.AgentInfo("b", "desc b")),
            history = history
        )
        assertTrue(prompt.contains("[a]: 我来处理 @b"))
        assertTrue(prompt.contains("[用户]: 好的，谢谢"))
        assertFalse(prompt.contains("[用户]: 我来处理 @b"))
    }

    @Test
    fun terminationPrompt_labelsAgentAndUserMessages() {
        val history = listOf(
            agentMessage("b", "方案已确认"),
            userMessage("好的，请继续")
        )
        val prompt = GroupChatPrompts.buildTerminationPrompt(history, maxRounds = 10, currentRound = 2)
        assertTrue(prompt.contains("[b]: 方案已确认"))
        assertTrue(prompt.contains("[用户]: 好的，请继续"))
    }
}
