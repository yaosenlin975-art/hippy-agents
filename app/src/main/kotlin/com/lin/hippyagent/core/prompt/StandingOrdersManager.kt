package com.lin.hippyagent.core.prompt

import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 常驻指令（Standing Order）
 *
 * 参考: OpenClaw StandingOrders.ts — StandingOrder 接口
 */
data class StandingOrder(
    val id: String,
    val content: String,
    val hash: String,
    val priority: Int = 100
)

/**
 * 注入结果
 */
data class InjectionResult(
    val injected: List<StandingOrder>,
    val skipped: Int
)

/**
 * 常驻指令管理器 — 管理 System Prompt 中只注入一次的长期行为指令
 *
 * HippyAgent 当前每次 LLM 调用都注入完整的 RULES.md + SOUL.md + PROFILE.md，
 * 这些文件内容稳定几乎每轮不变，极大浪费 Token。
 *
 * Standing Orders 方案：
 * - 核心文件在对话开始时注入一次（system message）
 * - 后续轮次只注入增量变化（如果文件未修改则完全跳过）
 * - 文件保存后通知重新哈希，确保变更后下一轮重新注入
 *
 * 参考: OpenClaw StandingOrders.ts — StandingOrdersManager
 */
class StandingOrdersManager {

    private val orders = ConcurrentHashMap<String, StandingOrder>()

    /** 注册常驻指令 */
    fun register(id: String, content: String, priority: Int = 100) {
        orders[id] = StandingOrder(
            id = id,
            content = content,
            hash = hashContent(content),
            priority = priority
        )
    }

    /** 更新指令内容（当文件被编辑时调用） */
    fun update(id: String, newContent: String) {
        val existing = orders[id] ?: return
        val newHash = hashContent(newContent)
        if (newHash == existing.hash) return
        orders[id] = existing.copy(content = newContent, hash = newHash)
    }

    /** 移除指令 */
    fun remove(id: String) {
        orders.remove(id)
    }

    /**
     * 获取需要注入的常驻指令
     * @param sessionId 会话 ID
     * @param workingDir 工作目录（用于重新加载文件内容）
     * @return 本轮需要注入的指令列表
     */
    fun getStandingOrders(sessionId: String, workingDir: File): List<StandingOrder> {
        val result = mutableListOf<StandingOrder>()
        val sorted = orders.values.sortedBy { it.priority }

        for (order in sorted) {
            val currentContent = loadOrderContent(order.id, workingDir)
            val currentHash = hashContent(currentContent)
            val updatedOrder = StandingOrder(
                id = order.id,
                content = currentContent,
                hash = currentHash,
                priority = order.priority
            )
            orders[order.id] = updatedOrder
            result.add(updatedOrder)
        }

        return result
    }

    fun getRegisteredOrders(): List<StandingOrder> = orders.values.sortedBy { it.priority }

    private fun hashContent(content: String): String {
        if (content.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun loadOrderContent(id: String, workingDir: File): String {
        return when (id) {
            "soul" -> File(workingDir, "SOUL.md").takeIf { it.exists() }?.readText() ?: ""
            "profile" -> File(workingDir, "PROFILE.md").takeIf { it.exists() }?.readText() ?: ""
            "rules" -> File(workingDir, "RULES.md").takeIf { it.exists() }?.readText() ?: ""
            "agents" -> File(workingDir, "AGENTS.md").takeIf { it.exists() }?.readText() ?: ""
            "heartbeat" -> File(workingDir, "HEARTBEAT.md").takeIf { it.exists() }?.readText() ?: ""
            "global_rules" -> ""  // 全局规则由调用方注入
            else -> ""
        }
    }

    companion object {
        /** 创建默认的 StandingOrdersManager 并注册核心文件 */
        fun createDefault(): StandingOrdersManager {
            val manager = StandingOrdersManager()
            manager.register("soul", "", priority = 10)
            manager.register("profile", "", priority = 20)
            manager.register("rules", "", priority = 30)
            manager.register("agents", "", priority = 40)

            manager.register("global_rules", "", priority = 60)
            return manager
        }
    }
}
