package com.lin.hippyagent.core.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// 文件级 DataStore 扩展，避免与 ToolGuardConfig.kt 中的私有 dataStore 冲突
private val Context.permissionDataStore: DataStore<Preferences> by preferencesDataStore(name = "permission_settings")

/**
 * PermissionManager（移植自 Mercury Agent 权限系统）
 * 
 * 为 PRoot 环境提供细粒度安全控制
 * 
 * 参考：reference/mercury-agent/src/capabilities/permissions.ts
 */
class PermissionManager(
    private val context: Context
) {
    private val dataStore = context.permissionDataStore
    private val _tempScopes = java.util.concurrent.CopyOnWriteArrayList<FileScope>()
    private var _autoApproveAll = false

    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun matchesPattern(command: String, pattern: String): Boolean {
        val regex = regexCache.getOrPut(pattern) {
            val escaped = buildString {
                for (ch in pattern) {
                    when (ch) {
                        '*', '?' -> append(ch)
                        else -> {
                            if (ch in ".()[]{}+^$|\\") append('\\')
                            append(ch)
                        }
                    }
                }
            }
            Regex(escaped.replace("*", ".*").replace("?", "."))
        }
        return regex.matches(command)
    }

    companion object {
        // ============ 默认黑名单（永远禁止）===========
        val DEFAULT_BLOCKED = listOf(
            "sudo *",
            "rm -rf /",
            "rm -rf ~",
            "rm -rf /*",
            "mkfs *",
            "dd if=*",
            "chmod 777 /",
            "chown * /",
            ":(){ :|:& };:",  // fork bomb
            "shutdown *",
            "reboot *",
            "init 0",
            "init 6",
            "kill -9 1",
            "> /dev/sda",
            "mv /* /dev/null",
            // Android 特有
            "pm clear *",
            "pm uninstall *",
            "rm -rf /data/data",
            "rm -rf /storage/emulated/0/Android"
        )

        // ============ 默认自动批准 ============
        val DEFAULT_AUTO_APPROVED = listOf(
            "ls *", "cat *", "pwd", "cd *",
            "git status *", "git diff *", "git log *", "git branch *",
            "node *", "npm run *", "npm test *",
            "python3 *", "pip list *",
            "echo *", "head *", "tail *", "wc *",
            "find *", "grep *", "rg *",
            "ps *", "df *", "du *",
            "uname *"
        )

        // ============ 默认需确认 ============
        val DEFAULT_NEEDS_APPROVAL = listOf(
            "npm publish *",
            "git push *",
            "rm -rf *",
            "chmod *",
            "mv *",
            "cp -r *",
            "mkdir *",
            "rmdir *",
            // Android 特有
            "pm install *",
            "pip install *",
            "pip3 install *"
        )

        // ============ DataStore Keys ============
        val FILE_SCOPES_KEY = stringSetPreferencesKey("file_scopes")
        val AUTO_APPROVED_COMMANDS_KEY = stringSetPreferencesKey("auto_approved_commands")
        val AUTO_APPROVE_ALL_KEY = booleanPreferencesKey("auto_approve_all")
    }

    /**
     * 从 DataStore 加载持久化配置（App 启动时调用）
     */
    suspend fun initialize() {
        _autoApproveAll = dataStore.data.map { prefs ->
            prefs[AUTO_APPROVE_ALL_KEY] ?: false
        }.first()
        val prefs = dataStore.data.first()
        for ((key, value) in prefs.asMap()) {
            if (key.name.startsWith("custom_perm_") && value == true) {
                onceApprovedCustomPerms.add(key.name.removePrefix("custom_perm_"))
            }
        }
    }
    
    // ============ 文件系统权限检查 ============
    
    /**
     * 检查文件系统访问权限
     * @return FsPermissionResult
     */
    suspend fun checkFsAccess(path: String, mode: FileAccessMode): FsPermissionResult {
        // 1. 检查临时授权
        if (hasTempAuthorization(path, mode)) {
            return FsPermissionResult(allowed = true, scopeType = ScopeType.TEMPORARY)
        }
        
        // 2. 检查持久授权
        val persistent = getPersistentScopes()
        if (hasAuthorization(persistent, path, mode)) {
            return FsPermissionResult(allowed = true, scopeType = ScopeType.PERSISTENT)
        }
        
        // 3. 检查 autoApproveAll
        if (_autoApproveAll) {
            return FsPermissionResult(allowed = true, scopeType = ScopeType.ALWAYS)
        }
        
        // 4. 无可用授权
        return FsPermissionResult(false, reason = "No permission for ${mode.name} access to $path")
    }
    
    /**
     * 创建文件权限请求（通过 UI 层回调）
     */
    fun createFsPermissionRequest(path: String, mode: FileAccessMode): FsPermissionRequest {
        return FsPermissionRequest(
            path = path,
            mode = mode,
            message = "Agent 请求 ${if (mode == FileAccessMode.READ) "读取" else "写入"}权限：$path"
        )
    }

    /**
     * 创建 Shell 命令权限请求（通过 UI 层回调）
     */
    fun createShellPermissionRequest(command: String): ShellPermissionRequest {
        return ShellPermissionRequest(command = command)
    }
    
    // ============ Shell 权限检查 ============
    
    /**
     * 检查 Shell 命令权限
     * @return ShellPermissionResult
     */
    suspend fun checkShellCommand(command: String): ShellPermissionResult {
        val trimmed = command.trim()
        
        // 1. 检查黑名单
        for (pattern in DEFAULT_BLOCKED) {
            if (matchesPattern(trimmed, pattern)) {
                return ShellPermissionResult.BLOCKED
            }
        }
        
        // 2. 检查 autoApproveAll
        if (_autoApproveAll) {
            return ShellPermissionResult.AUTO_APPROVED
        }
        
        // 3. 检查自动批准
        for (pattern in DEFAULT_AUTO_APPROVED) {
            if (matchesPattern(trimmed, pattern)) {
                return ShellPermissionResult.AUTO_APPROVED
            }
        }
        
        // 4. 检查需要确认
        for (pattern in DEFAULT_NEEDS_APPROVAL) {
            if (matchesPattern(trimmed, pattern)) {
                return ShellPermissionResult.NEEDS_APPROVAL
            }
        }
        
        // 5. 未匹配，需要用户确认
        return ShellPermissionResult.NEEDS_APPROVAL
    }
    
    // ============ 授权管理 ============
    
    /**
     * 添加临时授权（会话级）
     */
    fun addTempScope(path: String, read: Boolean, write: Boolean) {
        _tempScopes.add(FileScope(path, read, write))
    }
    
    /**
     * 添加持久授权（保存到 DataStore）
     */
    suspend fun addPersistentScope(path: String, read: Boolean, write: Boolean) {
        val scopes = getPersistentScopes().toMutableList()
        // 检查是否已存在，如果存在则合并
        val existing = scopes.find { it.path == path }
        if (existing != null) {
            scopes[scopes.indexOf(existing)] = existing.copy(
                read = existing.read || read,
                write = existing.write || write
            )
        } else {
            scopes.add(FileScope(path, read, write))
        }
        savePersistentScopes(scopes)
    }
    
    /**
     * 清除临时授权（会话结束时调用）
     */
    fun clearTempScopes() {
        _tempScopes.clear()
    }
    
    /**
     * 设置自动批准所有
     */
    suspend fun setAutoApproveAll(value: Boolean) {
        _autoApproveAll = value
        dataStore.edit { prefs ->
            prefs[AUTO_APPROVE_ALL_KEY] = value
        }
    }
    
    /**
     * 获取自动批准状态
     */
    fun isAutoApproveAll(): Boolean = _autoApproveAll

    private val onceApprovedCustomPerms = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun approveCustomToolPermission(permKeys: List<String>, persistent: Boolean) {
        onceApprovedCustomPerms.addAll(permKeys)
        if (persistent) {
            dataStore.edit { prefs ->
                for (perm in permKeys) {
                    prefs[booleanPreferencesKey("custom_perm_$perm")] = true
                }
            }
        }
    }

    fun isCustomToolApprovedSync(permKey: String): Boolean = permKey in onceApprovedCustomPerms

    suspend fun isCustomToolApproved(permKey: String): Boolean {
        if (permKey in onceApprovedCustomPerms) return true
        return dataStore.data.map { prefs ->
            prefs[booleanPreferencesKey("custom_perm_$permKey")] ?: false
        }.first()
    }

    suspend fun isCustomToolPermanentlyApproved(permKey: String): Boolean {
        return dataStore.data.map { prefs ->
            prefs[booleanPreferencesKey("custom_perm_$permKey")] ?: false
        }.first()
    }

    suspend fun denyCustomToolPermanently(permKeys: List<String>) {
        dataStore.edit { prefs ->
            for (perm in permKeys) {
                prefs[booleanPreferencesKey("custom_perm_${perm}_denied")] = true
            }
        }
    }

    suspend fun isCustomToolPermanentlyDenied(permKey: String): Boolean {
        return dataStore.data.map { prefs ->
            prefs[booleanPreferencesKey("custom_perm_${permKey}_denied")] ?: false
        }.first()
    }
    
    // ============ 私有方法 ============
    
    private fun hasTempAuthorization(path: String, mode: FileAccessMode): Boolean {
        for (scope in _tempScopes) {
            if (path.startsWith(scope.path)) {
                return when (mode) {
                    FileAccessMode.READ -> scope.read
                    FileAccessMode.WRITE -> scope.write
                }
            }
        }
        return false
    }
    
    private suspend fun hasAuthorization(scopes: List<FileScope>, path: String, mode: FileAccessMode): Boolean {
        for (scope in scopes) {
            if (path.startsWith(scope.path)) {
                return when (mode) {
                    FileAccessMode.READ -> scope.read
                    FileAccessMode.WRITE -> scope.write
                }
            }
        }
        return false
    }
    
    private suspend fun getPersistentScopes(): List<FileScope> {
        val raw = dataStore.data.map { prefs ->
            prefs[FILE_SCOPES_KEY] ?: emptySet()
        }.first()
        
        return raw.mapNotNull { str ->
            // 格式 "path|read|write"
            val parts = str.split("|")
            if (parts.size == 3) {
                FileScope(
                    path = parts[0],
                    read = parts[1].toBoolean(),
                    write = parts[2].toBoolean()
                )
            } else null
        }
    }
    
    private suspend fun savePersistentScopes(scopes: List<FileScope>) {
        val raw = scopes.map { "${it.path}|${it.read}|${it.write}" }.toSet()
        dataStore.edit { prefs ->
            prefs[FILE_SCOPES_KEY] = raw
        }
    }
    
}

