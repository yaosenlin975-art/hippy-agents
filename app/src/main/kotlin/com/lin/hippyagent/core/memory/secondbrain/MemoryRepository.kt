package com.lin.hippyagent.core.memory.commonmemory

import androidx.room.*
import com.lin.hippyagent.core.memory.commonmemory.SearchIntent

/**
 * MemoryRepository 接口（Uncle Bob Clean Architecture 原则）
 *
 * 设计意图：记忆系统的「端口（Port）」，不依赖具体实现（Room / DataStore / InMemory）
 * Agent 和 Dream 只依赖此接口，不关心底层是 Room 还是 JSONL
 *
 * 参考：Mercury Agent CommonMemory 设计
 */
interface MemoryRepository {

    /** 插入新记忆 */
    suspend fun insert(entry: CommonMemoryEntry)

    /** 更新现有记忆（合并/置信度调整） */
    suspend fun update(entry: CommonMemoryEntry)

    /** 根据 ID 查询 */
    suspend fun findById(id: String): CommonMemoryEntry?

    /** FTS4 全文搜索（Room 原生支持） */
    suspend fun searchFts(query: String, limit: Int = 10): List<CommonMemoryEntry>

    /** 混合 RAG 搜索（FTS4 + RRF + 重排序），Phase 1 纯算法无向量 */
    suspend fun searchHybrid(query: String, limit: Int = 8): List<Pair<CommonMemoryEntry, Float>>

    /** 按智能体混合 RAG 搜索 */
    suspend fun searchHybridByAgentId(query: String, agentId: String, limit: Int = 8): List<Pair<CommonMemoryEntry, Float>>

    /** 意图驱动搜索 */
    suspend fun search(query: String, agentId: String, intent: SearchIntent, limit: Int = 8): List<Pair<CommonMemoryEntry, Float>>

    /** 按智能体 FTS4 全文搜索 */
    suspend fun searchFtsByAgentId(query: String, agentId: String, limit: Int = 10): List<CommonMemoryEntry>

    /** 按类型查询 */
    suspend fun findByType(type: BrainMemoryType, limit: Int = 10): List<CommonMemoryEntry>

    /** 按类型和智能体查询 */
    suspend fun findByTypeAndAgentId(type: BrainMemoryType, agentId: String, limit: Int = 10): List<CommonMemoryEntry>

    /** 查询所有活跃记忆（非 dismissed） */
    suspend fun findActive(limit: Int = 50): List<CommonMemoryEntry>

    /** 查询所有记忆（包含 dismissed） */
    suspend fun findAll(limit: Int = 200): List<CommonMemoryEntry>

    /** 按智能体查询活跃记忆 */
    suspend fun findActiveByAgentId(agentId: String, limit: Int = 50): List<CommonMemoryEntry>

    /** 查找合并候选（overlapScore ≥ 0.74） */
    suspend fun findMergeCandidate(
        type: BrainMemoryType,
        normalizedTerms: List<String>
    ): CommonMemoryEntry?

    /** 查找冲突候选（极性检测） */
    suspend fun findConflictCandidate(
        type: BrainMemoryType,
        summaryTerms: List<String>
    ): CommonMemoryEntry?

    /** 软删除（dismissed = 1） */
    suspend fun softDelete(id: String)

    /** dismiss 别名 */
    suspend fun dismiss(id: String) = softDelete(id)

    /** 查找过期的上传相关事实 */
    suspend fun findExpiredUploadFacts(nowMs: Long): List<CommonMemoryEntry>

    /** 按摘要搜索（用于语义去重） */
    suspend fun searchBySummary(query: String, limit: Int = 10): List<CommonMemoryEntry>

    /** 硬删除（物理删除） */
    suspend fun hardDelete(id: String)

    /** 自动修剪过期记忆 */
    suspend fun pruneStale(): PruneResult

    /** 晋升 active → durable（evidenceCount ≥ 3） */
    suspend fun promoteToDurable(): Int

    /** 获取统计信息 */
    suspend fun getStats(): MemoryStats

    /** 检索排序（Karpathy 评分函数） */
    fun scoreMemories(candidates: List<CommonMemoryEntry>, query: String): List<Pair<CommonMemoryEntry, Float>>

    /** 关闭数据库（App 退出时调用） */
    fun close()
}

/** 修剪结果 */
data class PruneResult(
    val activePruned: Int,   // active+inferred 过期被 dismiss 的数量
    val durablePruned: Int   // durable+inferred 置信度衰减至 <0.3 被 dismiss 的数量
)

/** 记忆统计 */
data class MemoryStats(
    val total: Int,
    val byType: Map<String, Int>,
    val profileSummary: String?,
    val activeSummary: String?
)

