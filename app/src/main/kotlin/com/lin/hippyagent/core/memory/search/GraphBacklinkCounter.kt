package com.lin.hippyagent.core.memory.search

import com.lin.hippyagent.core.agent.session.GraphEntityDao
import com.lin.hippyagent.core.agent.session.GraphRelationDao

/**
 * 图谱入度/度数统计器。
 *
 * - [countBacklinks]: 记忆被其他实体链接的次数（入度，targetEntityId 命中）
 * - [countDegree]: 记忆关联的总关系数（source + target）
 *
 * 用于 PostFusionReranker 的 backlink / graph_signals 阶段。
 * 通过 properties LIKE 反查记忆对应的图谱实体（≤10k 规模可接受全表扫描）。
 */
class GraphBacklinkCounter(
    private val entityDao: GraphEntityDao,
    private val relationDao: GraphRelationDao
) {
    suspend fun countBacklinks(memoryId: String): Int {
        val entity = entityDao.findByPropertiesLike("%memoryId=$memoryId%") ?: return 0
        return relationDao.countByTargetEntity(entity.id)
    }

    suspend fun countDegree(memoryId: String): Int {
        val entity = entityDao.findByPropertiesLike("%memoryId=$memoryId%") ?: return 0
        return relationDao.getByEntityId(entity.id).size
    }
}
