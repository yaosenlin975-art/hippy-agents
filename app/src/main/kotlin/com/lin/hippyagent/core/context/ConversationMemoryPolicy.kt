package com.lin.hippyagent.core.context

/**
 * 对话记忆策略 - 区分"用户对话轮次"与"Agent 内部工具迭代".
 *
 * 设计哲学:
 * - 用户对话轮次 (user→assistant 一次完整交互) 是用户感知的连续性
 * - Agent 内部工具迭代 (一次对话内多次 tool_call) 是实现细节
 * - 压缩时硬保留最近 N 个对话轮次 verbatim, 即使内部工具日志膨胀也不动用户消息
 *
 * 集成点: ContextManager.checkContext 调用 isHardKeep/rememberableTurns 划分硬保留区.
 */
object ConversationMemoryPolicy {

    /** 硬保留最近 12 个用户对话轮次 (可配置覆盖) */
    const val HARD_KEEP_RECENT_DIALOG_TURNS: Int = 12

    /**
     * 可记忆轮次上限映射.
     * 总轮次少时全保留, 多时按阶梯压缩 (避免线性膨胀).
     *
     * @param totalTurns 历史总轮次
     * @return 建议保留的可记忆轮次上限
     */
    fun rememberableTurns(totalTurns: Int): Int = when {
        totalTurns <= HARD_KEEP_RECENT_DIALOG_TURNS -> totalTurns
        totalTurns <= 50 -> HARD_KEEP_RECENT_DIALOG_TURNS
        totalTurns <= 200 -> 18
        else -> 24  // 上限 24, 防止过度保留
    }

    /**
     * 判定指定轮次索引是否在硬保留区.
     *
     * @param turnIndexFromEnd 当前消息所属轮次索引 (0-based, 从最近往回数: 0=最近一轮)
     * @param totalTurns 总轮次数
     * @return true 表示该轮次应硬保留, 不参与压缩
     */
    fun isHardKeep(turnIndexFromEnd: Int, totalTurns: Int): Boolean {
        val keepCount = rememberableTurns(totalTurns)
        return turnIndexFromEnd in 0 until keepCount
    }
}
