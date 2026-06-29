package com.lin.hippyagent.core.security

/**
 * 统一风险等级枚举（替代 ToolGuardian.RiskLevel 和 accessibility.RiskLevel）。
 *
 * - SAFE: 无风险，自动放行
 * - LOW: 低风险，自动放行
 * - MEDIUM: 中风险，会话内已批准则放行
 * - HIGH: 高风险，需显式批准
 * - BLOCKED: 阻断，不可批准
 * - CRITICAL: 严重，阻断并记录安全事件
 */
enum class RiskLevel(val weight: Int) {
    SAFE(0),
    LOW(10),
    MEDIUM(20),
    HIGH(30),
    BLOCKED(40),
    CRITICAL(50);

    fun shouldBlock(): Boolean = this >= HIGH
    fun isUnblockable(): Boolean = this >= BLOCKED
}
