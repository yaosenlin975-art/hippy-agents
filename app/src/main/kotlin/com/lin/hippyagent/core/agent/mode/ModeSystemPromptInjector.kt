package com.lin.hippyagent.core.agent.mode

import com.lin.hippyagent.core.skill.AgentMode

class ModeSystemPromptInjector {

    fun injectInto(basePrompt: String, mode: AgentMode): String {
        val modePrompt = promptFor(mode)
        if (modePrompt.isEmpty()) return basePrompt
        return buildString {
            append(basePrompt)
            append("\n\n")
            append(modePrompt)
        }
    }

    fun promptFor(mode: AgentMode): String = when (mode) {
        AgentMode.AUTO -> ""
        AgentMode.NONE -> NONE_MODE_PROMPT
        AgentMode.CHAT -> CHAT_MODE_PROMPT
        AgentMode.WORK -> WORK_MODE_PROMPT
    }

    companion object {
        private const val NONE_MODE_PROMPT =
            "你处于 NONE 模式 — 不进行模式区分，所有已启用的工具和技能均可使用，没有 chat/work 过滤。" +
                "若你认为当前任务更适合用其他模式，可在回复中通过 XML 标记声明切换。"

        private const val CHAT_MODE_PROMPT =
            "你处于 Chat 模式。优先用对话方式回复，调用工具须克制。" +
                "若用户消息明显需要执行任务 (写代码/批量处理/工具调用) → 在回复开头输出 XML 标记 <switch_to_mode>work</switch_to_mode> 申请切换 Work 模式。" +
                "若当前任务超出当前模型能力 → 可输出 <switch_to_model>complex</switch_to_model> 申请切到复杂任务模型。"

        private const val WORK_MODE_PROMPT =
            "你处于 Work 模式。优先拆解为可执行步骤,逐步推进。完成后清晰汇报结果。" +
                "若你认为只是闲聊/问答 → 输出 <switch_to_mode>chat</switch_to_mode> 切回 Chat 模式。"
    }
}
