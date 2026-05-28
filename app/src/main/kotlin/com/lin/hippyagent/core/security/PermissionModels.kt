package com.lin.hippyagent.core.security

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first

/**
 * 文件权限作用域
 * 
 * 参考 Mercury Agent Filesystem Permissions 设计
 */
data class FileScope(
    val path: String,        // 授权路径（递归生效）
    val read: Boolean,       // 允许读取
    val write: Boolean      // 允许写入
)

/**
 * 权限作用域类型
 */
enum class ScopeType {
    TEMPORARY,     // 会话级，会话结束失效
    PERSISTENT,    // 持久化到 DataStore
    ALWAYS          // 持久化 + 永久生效（用户选择「始终允许」）
}

/**
 * Shell 权限配置
 * 
 * 移植自 Mercury Agent
 */
data class ShellPermissionConfig(
    val enabled: Boolean = true,
    val blockedPatterns: List<String> = PermissionManager.DEFAULT_BLOCKED,
    val autoApprovedPatterns: List<String> = PermissionManager.DEFAULT_AUTO_APPROVED,
    val needsApprovalPatterns: List<String> = PermissionManager.DEFAULT_NEEDS_APPROVAL,
    val cwdOnly: Boolean = true   // 限制只在 CWD 及授权目录
)

/**
 * Shell 权限检查结果
 */
enum class ShellPermissionResult {
    AUTO_APPROVED,      // 自动批准
    NEEDS_APPROVAL,    // 需要用户确认
    BLOCKED,           // 被黑名单禁止
    DENIED             // 用户拒绝
}

/**
 * 文件系统权限检查结果
 */
data class FsPermissionResult(
    val allowed: Boolean,
    val reason: String = "",
    val scopeType: ScopeType? = null
)

/**
 * 文件访问模式
 */
enum class FileAccessMode {
    READ, WRITE
}

/**
 * Shell 权限请求（传递给 UI 层显示）
 */
data class ShellPermissionRequest(
    val command: String,
    val message: String = "Agent 请求执行命令：$command"
)

/**
 * 文件权限请求（传递给 UI 层显示）
 */
data class FsPermissionRequest(
    val path: String,
    val mode: FileAccessMode,
    val message: String = "Agent 请求 ${if (mode == FileAccessMode.READ) "读取" else "写入"}权限：$path"
)

