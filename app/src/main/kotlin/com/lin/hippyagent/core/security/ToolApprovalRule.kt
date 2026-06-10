package com.lin.hippyagent.core.security

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 单工具拦截的"总是允许 / 总是拒绝"规则。
 *
 * - [key] 是 ToolApprovalManager.ruleKey() 生成的精确键 (toolName|argSummary)
 *   或工具级键 (toolName|*)；查询时两者都要查。
 * - [action] 只存持久的 ALWAYS_* 决策，ONCE 不入库。
 * - [toolName] / [argHash] 用于按工具或参数组合筛选。
 */
@Entity(
    tableName = "tool_approval_rules",
    indices = [
        Index("tool_name"),
        Index(value = ["tool_name", "arg_hash"])
    ]
)
data class ToolApprovalRule(
    @androidx.room.PrimaryKey val key: String,
    val action: String,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "arg_hash") val argHash: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ToolApprovalRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: ToolApprovalRule)

    @Query("DELETE FROM tool_approval_rules WHERE key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM tool_approval_rules")
    suspend fun clearAll()

    @Query("SELECT * FROM tool_approval_rules WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): ToolApprovalRule?

    @Query("SELECT * FROM tool_approval_rules WHERE tool_name = :toolName AND (arg_hash = :argHash OR arg_hash = '*') ORDER BY created_at DESC")
    suspend fun findByToolOrArgs(toolName: String, argHash: String): List<ToolApprovalRule>

    @Query("SELECT * FROM tool_approval_rules ORDER BY created_at DESC")
    suspend fun getAll(): List<ToolApprovalRule>
}
