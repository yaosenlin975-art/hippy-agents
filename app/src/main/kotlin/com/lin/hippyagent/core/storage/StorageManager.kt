package com.lin.hippyagent.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import timber.log.Timber

/**
 * 统一存储管理器 — 支持 SAF 外部存储和内部存储自动切换
 *
 * 当用户通过 SAF 授权外部目录后，所有数据存储到外部目录（卸载不丢失）。
 * 未授权时 fallback 到内部存储（context.filesDir）。
 */
class StorageManager(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "storage_prefs"
        private const val KEY_SAF_URI = "saf_tree_uri"
        private const val KEY_SAF_MIGRATED = "saf_migrated"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ===== SAF URI 持久化 =====

    /** 获取已持久化的 SAF 目录 URI */
    fun getSafUri(): android.net.Uri? {
        val uriString = prefs.getString(KEY_SAF_URI, null) ?: return null
        return try {
            android.net.Uri.parse(uriString)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse SAF URI")
            null
        }
    }

    /** 设置 SAF 目录 URI 并持久化 */
    fun setSafUri(uri: android.net.Uri?) {
        if (uri != null) {
            // 持久化 URI 权限
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString(KEY_SAF_URI, uri.toString()).apply()
        } else {
            // 清除时释放权限
            val oldUri = getSafUri()
            if (oldUri != null) {
                try {
                    context.contentResolver.releasePersistableUriPermission(
                        oldUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to release SAF URI permission")
                }
            }
            prefs.edit().remove(KEY_SAF_URI).apply()
        }
    }

    /** 是否已授权 SAF 外部存储 */
    fun isSafEnabled(): Boolean = getSafUri() != null

    /** 是否已完成内部→SAF 的数据迁移 */
    fun isSafMigrated(): Boolean = prefs.getBoolean(KEY_SAF_MIGRATED, false)

    /** 标记迁移完成 */
    fun markSafMigrated() {
        prefs.edit().putBoolean(KEY_SAF_MIGRATED, true).apply()
    }

    /** 检测 SAF 外部存储是否已有 hippydata 数据 */
    fun safHasExistingData(): Boolean {
        val uri = getSafUri() ?: return false
        val root = DocumentFile.fromTreeUri(context, uri) ?: return false
        val copawDir = root.findFile(".copaw")
        if (copawDir != null && copawDir.exists() && copawDir.isDirectory) {
            // 检查是否有实质内容（至少一个子文件）
            return copawDir.listFiles().isNotEmpty()
        }
        return false
    }

    // ===== 目录获取 — 自动切换 SAF / 内部存储 =====

    fun getWorkingDir(): File {
        return context.filesDir
    }

    fun getSecretDir(): File {
        val dir = File(context.filesDir, "secret")
        dir.mkdirs()
        return dir
    }

    fun getBackupDir(): File {
        val dir = File(context.filesDir, ".backups")
        dir.mkdirs()
        return dir
    }

    /** 获取 SAF 下的工作区目录 */
    fun getWorkingDirSaf(): DocumentFile? {
        val uri = getSafUri() ?: return null
        val root = DocumentFile.fromTreeUri(context, uri) ?: return null
        return root
    }

    /** 获取 SAF 下的秘密目录 */
    fun getSecretDirSaf(): DocumentFile? {
        val uri = getSafUri() ?: return null
        val root = DocumentFile.fromTreeUri(context, uri) ?: return null
        return root.findOrCreateDir("secret")
    }

    /** 获取 SAF 下的备份目录 */
    fun getBackupDirSaf(): DocumentFile? {
        val uri = getSafUri() ?: return null
        val root = DocumentFile.fromTreeUri(context, uri) ?: return null
        return root.findOrCreateDir(".backups")
    }

    /**
     * 获取 Agent 工作区目录
     * SAF 启用时返回 SAF 路径，否则返回内部存储
     */
    fun getAgentWorkspaceDir(agentId: String): File {
        val baseDir = getWorkingDir()
        val dir = File(baseDir, "workspaces/$agentId")
        dir.mkdirs()
        return dir
    }

    /**
     * 获取 Agent 工作区的 SAF DocumentFile
     */
    fun getAgentWorkspaceDirSaf(agentId: String): DocumentFile? {
        val copawDir = getWorkingDirSaf() ?: return null
        val workspacesDir = copawDir.findOrCreateDir("workspaces") ?: return null
        return workspacesDir.findOrCreateDir(agentId)
    }

    fun ensureDirectoryStructure() {
        getWorkingDir().mkdirs()
        getSecretDir().mkdirs()
        getBackupDir().mkdirs()
    }

    // ===== 数据迁移：内部存储 → SAF =====

    /**
     * 将所有数据从内部存储迁移到 SAF 目录
     * @return 迁移的文件数量
     */
    suspend fun migrateToSaf(): Result<Int> = runCatching {
        val safRoot = getSafUri()?.let { DocumentFile.fromTreeUri(context, it) }
            ?: return Result.failure(IllegalStateException("SAF URI not set"))

        var count = 0

        listOf("agents", "workspaces", "skills", "messages", "sessions", "channels", "plans", "memory", "datastore").forEach { subDir ->
            val dir = File(context.filesDir, subDir)
            if (dir.exists() && dir.isDirectory) {
                count += migrateDirectory(dir, safRoot, subDir)
            }
        }

        count += migrateDirectory(File(context.filesDir, "secret"), safRoot, "secret")

        // 迁移 .backups/
        count += migrateDirectory(File(context.filesDir, ".backups"), safRoot, ".backups")

        // 迁移 DataStore 目录（模型提供商等偏好数据）
        count += migrateDirectory(File(context.filesDir, "datastore"), safRoot, "datastore")

        // 迁移 SharedPreferences 文件（界面设置等）
        val sharedPrefsDir = File(context.filesDir, "../shared_prefs")
        if (sharedPrefsDir.exists()) {
            count += migrateDirectory(sharedPrefsDir, safRoot, "shared_prefs")
        }

        // 迁移 Room 数据库（Room 文件无法直接移到 SAF，需要特殊处理）
        // Room 数据库留在内部存储，但配置和会话消息在 SAF 中有备份
        // 这是因为 Room 需要 File 路径打开数据库，SAF 不支持

        markSafMigrated()
        Timber.i("SAF migration completed: $count files migrated")
        count
    }

    /**
     * 递归迁移目录
     */
    private fun migrateDirectory(sourceDir: File, safRoot: DocumentFile, dirName: String): Int {
        if (!sourceDir.exists()) return 0

        val targetDir = safRoot.findOrCreateDir(dirName) ?: return 0
        var count = 0

        sourceDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = file.relativeTo(sourceDir).path
                val targetFile = createFileInPath(targetDir, relativePath)
                if (targetFile != null) {
                    try {
                        copyFileToSaf(file, targetFile)
                        count++
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to migrate file: ${file.path}")
                    }
                }
            }
        }
        return count
    }

    /**
     * 在 SAF DocumentFile 中按相对路径创建文件（自动创建中间目录）
     */
    private fun createFileInPath(root: DocumentFile, relativePath: String): DocumentFile? {
        val parts = relativePath.replace("\\", "/").split("/")
        var current = root
        for (i in 0 until parts.size - 1) {
            current = current.findOrCreateDir(parts[i]) ?: return null
        }
        val fileName = parts.last()
        return current.findFile(fileName)?.takeIf { it.isFile } ?: current.createFile("*/*", fileName)
    }

    /**
     * 将 File 内容写入 SAF DocumentFile
     */
    private fun copyFileToSaf(source: File, target: DocumentFile) {
        context.contentResolver.openOutputStream(target.uri)?.use { os ->
            source.inputStream().use { iss ->
                iss.copyTo(os)
            }
        }
    }

    /**
     * 从 SAF DocumentFile 读取内容到 File
     */
    fun copySafToFile(source: DocumentFile, target: File) {
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(source.uri)?.use { iss ->
            target.outputStream().use { os ->
                iss.copyTo(os)
            }
        }
    }

    // ===== 同步：SAF ↔ 内部存储 =====

    /**
     * 将 SAF 中的数据同步回内部存储（用于回退到内部存储模式）
     */
    suspend fun syncSafToInternal(): Result<Int> = runCatching {
        val safRoot = getSafUri()?.let { DocumentFile.fromTreeUri(context, it) }
            ?: return Result.failure(IllegalStateException("SAF URI not set"))

        val backupDir = File(context.filesDir, "backup_${System.currentTimeMillis()}")
        val copawDir = context.filesDir
        if (copawDir.exists()) {
            copawDir.copyRecursively(backupDir, overwrite = true)
            Timber.d("Backed up hippydata to ${backupDir.name} before SAF sync")
        }

        var count = 0
        listOf("secret", ".backups", "datastore", "shared_prefs").forEach { dirName ->
            val safDir = safRoot.findFile(dirName)
            if (safDir != null && safDir.isDirectory) {
                val internalDir = when (dirName) {
                    "shared_prefs" -> File(context.filesDir, "../shared_prefs")
                    else -> File(context.filesDir, dirName)
                }
                count += syncSafDirToInternal(safDir, internalDir)
            }
        }
        for (doc in safRoot.listFiles()) {
            if (doc.isFile) {
                val targetFile = File(context.filesDir, doc.name ?: continue)
                try {
                    copySafToFile(doc, targetFile)
                    count++
                } catch (e: Exception) {
                    Timber.e(e, "Failed to sync SAF root file: ${doc.name}")
                }
            }
        }
        count
    }

    private fun syncSafDirToInternal(safDir: DocumentFile, internalDir: File): Int {
        var count = 0
        internalDir.mkdirs()

        for (doc in safDir.listFiles()) {
            if (doc.isDirectory) {
                val subDir = File(internalDir, doc.name ?: continue)
                count += syncSafDirToInternal(doc, subDir)
            } else if (doc.isFile) {
                val targetFile = File(internalDir, doc.name ?: continue)
                try {
                    copySafToFile(doc, targetFile)
                    count++
                } catch (e: Exception) {
                    Timber.e(e, "Failed to sync SAF file: ${doc.name}")
                }
            }
        }
        return count
    }

    fun restoreFromLatestBackup(): Result<Int> = runCatching {
        val backupDirs = context.filesDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("backup_") }
            ?.sortedByDescending { it.lastModified() }
        if (backupDirs.isNullOrEmpty()) {
            return Result.failure(IllegalStateException("No backup found"))
        }

        val latest = backupDirs.first()
        val copawDir = context.filesDir
        copawDir.deleteRecursively()
        latest.copyRecursively(copawDir, overwrite = true)
        latest.deleteRecursively()
        Timber.i("Restored hippydata from backup: ${latest.name}")
        copawDir.walkTopDown().count { it.isFile }
    }

    // ===== 工具方法 =====

    private fun DocumentFile.findOrCreateDir(dirName: String): DocumentFile? {
        val existing = findFile(dirName)
        if (existing != null && existing.isDirectory) {
            return existing
        }
        if (existing != null && existing.isFile) {
            // 名字冲突：文件和目录同名，先删除文件
            existing.delete()
        }
        return createDirectory(dirName)
    }

    /**
     * 获取当前存储路径的显示文本
     */
    fun getStoragePathDisplay(): String {
        val uri = getSafUri()
        return if (uri != null) {
            "外部存储（已授权）"
        } else {
            "内部存储（卸载将丢失数据）"
        }
    }

    /**
     * 获取存储空间占用统计
     */
    fun getStorageStats(): StorageStats {
        val copawDir = context.filesDir
        val agentsDir = File(copawDir, "agents")
        val workspacesDir = File(copawDir, "workspaces")

        var sessionSize = 0L
        var coreSize = 0L
        if (workspacesDir.exists()) {
            workspacesDir.listFiles()?.forEach { d ->
                sessionSize += dirSize(File(d, "sessions"))
                d.listFiles()?.filter { it.isDirectory && it.name != "sessions" }?.forEach {
                    coreSize += dirSize(it)
                }
            }
        }

        return StorageStats(
            totalAppSize = dirSize(copawDir) + dirSize(File(context.filesDir, "secret")) + dirSize(File(context.filesDir, ".backups")),
            agentsSize = dirSize(agentsDir),
            sessionsSize = sessionSize,
            coreFilesSize = coreSize,
            dbSize = dirSize(File(context.getDatabasePath("dummy").parent ?: "")),
            isSafEnabled = isSafEnabled(),
            safMigrated = isSafMigrated()
        )
    }

    private fun dirSize(d: File): Long {
        if (!d.exists()) return 0
        var s = 0L
        d.listFiles()?.forEach { s += if (it.isDirectory) dirSize(it) else it.length() }
        return s
    }
}

data class StorageStats(
    val totalAppSize: Long,
    val agentsSize: Long,
    val sessionsSize: Long,
    val coreFilesSize: Long,
    val dbSize: Long,
    val isSafEnabled: Boolean,
    val safMigrated: Boolean
)



