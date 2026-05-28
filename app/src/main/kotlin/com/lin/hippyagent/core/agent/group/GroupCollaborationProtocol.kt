package com.lin.hippyagent.core.agent.group

import kotlinx.serialization.Serializable
import timber.log.Timber

const val NO_REPLY = "NO_REPLY"

private val TASK_VERB_PATTERN = Regex("请|帮我|执行|创建|删除|修改|发送|打开|关闭|安装|运行")
private val QUESTION_WORD_PATTERN = Regex("什么|怎么|如何|为什么|哪里|哪个|谁|when|where|who|what|how|why|is|can|could|would|[?？]")

fun detectNewTask(content: String): Boolean {
    return TASK_VERB_PATTERN.containsMatchIn(content)
}

fun detectQuestion(content: String): Boolean {
    return QUESTION_WORD_PATTERN.containsMatchIn(content)
}

@Serializable
data class MentionExchange(
    val fromAgent: String,
    val toAgent: String,
    val timestamp: Long,
    val hasNewTask: Boolean,
    val hasQuestion: Boolean,
    val hasDecision: Boolean
)

class GroupCollaborationProtocol {

    fun shouldStopPingPong(exchangeHistory: List<MentionExchange>): Boolean {
        if (exchangeHistory.size < 2) return false
        val recent = exchangeHistory.takeLast(2)
        val idle = recent.all { !it.hasNewTask && !it.hasQuestion && !it.hasDecision }
        if (idle) {
            Timber.d("Ping-pong stop: 2+ rounds without new task/question/decision")
        }
        return idle
    }

}
