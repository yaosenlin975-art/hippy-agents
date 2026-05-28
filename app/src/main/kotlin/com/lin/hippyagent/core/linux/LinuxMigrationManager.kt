package com.lin.hippyagent.core.linux

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import java.io.File

/**
 * Linux 环境迁移管理器
 * 处理数据持久化和版本升级时的数据迁移
 */
class LinuxMigrationManager(
    private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "linux_migration"
        private const val KEY_VERSION = "version"
        private const val KEY_MIGRATION_STATUS = "migration_status"
        private const val CURRENT_VERSION = 1

        // 迁移状态
        const val STATUS_NONE = 0
        const val STATUS_PENDING = 1
        const val STATUS_COMPLETED = 2
        const val STATUS_FAILED = 3
    }

    /**
     * 检查是否需要迁移
     */
    fun checkMigration(): MigrationAction {
        val savedVersion = prefs.getInt(KEY_VERSION, 0)
        val migrationStatus = prefs.getInt(KEY_MIGRATION_STATUS, STATUS_NONE)

        return when {
            // 首次安装
            savedVersion == 0 -> MigrationAction.FIRST_INSTALL
            // 版本升级
            savedVersion < CURRENT_VERSION -> MigrationAction.UPGRADE
            // 迁移失败，需要重试
            migrationStatus == STATUS_FAILED -> MigrationAction.RETRY_MIGRATION
            // 无需迁移
            else -> MigrationAction.NONE
        }
    }

    /**
     * 执行迁移
     */
    suspend fun migrate(action: MigrationAction): Result<Unit> = runCatching {
        Timber.d("Starting migration: $action")

        when (action) {
            MigrationAction.FIRST_INSTALL -> {
                // 首次安装，创建必要的目录结构
                createDirectoryStructure()
                saveVersion()
            }

            MigrationAction.UPGRADE -> {
                // 版本升级，备份并迁移数据
                val savedVersion = prefs.getInt(KEY_VERSION, 0)
                backupData(savedVersion)
                migrateData(savedVersion, CURRENT_VERSION)
                saveVersion()
            }

            MigrationAction.RETRY_MIGRATION -> {
                // 重试失败的迁移
                val savedVersion = prefs.getInt(KEY_VERSION, 0)
                migrateData(savedVersion, CURRENT_VERSION)
                saveVersion()
            }

            MigrationAction.NONE -> {
                Timber.d("No migration needed")
            }
        }

        prefs.edit().putInt(KEY_MIGRATION_STATUS, STATUS_COMPLETED).apply()
        Timber.d("Migration completed successfully")
    }

    /**
     * 创建目录结构
     */
    private fun createDirectoryStructure() {
        val dirs = listOf(
            context.linuxBaseDir,
            context.rootfsDir,
            context.sharedDir,
            context.linuxConfigDir,
            context.linuxLogDir,
            context.linuxTmpDir,
            context.projectDir,
            File(context.sharedDir, "home"),
            File(context.sharedDir, "tmp")
        )
        dirs.forEach { it.mkdirs() }
        Timber.d("Directory structure created")
    }

    /**
     * 备份数据
     */
    private fun backupData(fromVersion: Int) {
        val backupDir = File(context.linuxBaseDir, "backup/v$fromVersion")
        backupDir.mkdirs()

        // 备份配置文件
        val configFile = File(context.linuxConfigDir, "alinux.conf")
        if (configFile.exists()) {
            configFile.copyTo(File(backupDir, "alinux.conf"), overwrite = true)
        }

        // 备份用户主目录中的重要文件
        val userHome = File(context.rootfsDir, "root")
        if (userHome.exists()) {
            val importantFiles = listOf(".bashrc", ".profile", ".ssh/authorized_keys")
            importantFiles.forEach { fileName ->
                val file = File(userHome, fileName)
                if (file.exists()) {
                    val backupFile = File(backupDir, fileName)
                    backupFile.parentFile?.mkdirs()
                    file.copyTo(backupFile, overwrite = true)
                }
            }
        }

        Timber.d("Data backed up to ${backupDir.absolutePath}")
    }

    /**
     * 迁移数据
     */
    private fun migrateData(fromVersion: Int, toVersion: Int) {
        Timber.d("Migrating data from v$fromVersion to v$toVersion")

        // 这里可以添加具体的迁移逻辑
        // 例如：重命名目录、更新配置格式、清理旧文件等

        // 示例：清理临时目录
        val tmpDir = context.linuxTmpDir
        if (tmpDir.exists()) {
            tmpDir.listFiles()?.forEach { it.delete() }
        }

        // 示例：更新配置文件格式
        if (fromVersion < 2) {
            // 假设 v2 引入了新的配置格式
            migrateConfigFormat()
        }
    }

    /**
     * 迁移配置格式
     */
    private fun migrateConfigFormat() {
        // 实现配置格式迁移逻辑
        Timber.d("Migrating config format")
    }

    /**
     * 保存版本号
     */
    private fun saveVersion() {
        prefs.edit()
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .putInt(KEY_MIGRATION_STATUS, STATUS_COMPLETED)
            .apply()
    }

    /**
     * 标记迁移失败
     */
    fun markMigrationFailed() {
        prefs.edit().putInt(KEY_MIGRATION_STATUS, STATUS_FAILED).apply()
    }

    /**
     * 获取当前版本
     */
    fun getCurrentVersion(): Int = CURRENT_VERSION

    /**
     * 获取保存的版本
     */
    fun getSavedVersion(): Int = prefs.getInt(KEY_VERSION, 0)

    /**
     * 清理备份
     */
    fun cleanupBackups(keepLastN: Int = 3) {
        val backupDir = File(context.linuxBaseDir, "backup")
        if (!backupDir.exists()) return

        val backups = backupDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("v") }
            ?.sortedByDescending { it.name.removePrefix("v").toIntOrNull() ?: 0 }
            ?: return

        if (backups.size > keepLastN) {
            backups.drop(keepLastN).forEach { backup ->
                backup.deleteRecursively()
                Timber.d("Cleaned up old backup: ${backup.name}")
            }
        }
    }
}

/**
 * 迁移动作
 */
enum class MigrationAction {
    NONE,           // 无需迁移
    FIRST_INSTALL,  // 首次安装
    UPGRADE,        // 版本升级
    RETRY_MIGRATION // 重试失败的迁移
}

