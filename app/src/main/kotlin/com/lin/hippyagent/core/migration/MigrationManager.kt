package com.lin.hippyagent.core.migration

import android.content.Context
import com.lin.hippyagent.core.auth.SecretMigrationManager
import com.lin.hippyagent.core.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import java.io.File

data class Migration(
    val fromVersion: String,
    val toVersion: String,
    val description: String,
    val migrate: suspend (Context) -> Result<Unit>
)

class MigrationManager(
    private val context: Context
) {
    private val migrations = mutableListOf<Migration>()
    private val prefs = context.getSharedPreferences("migrations", Context.MODE_PRIVATE)

    fun registerMigration(migration: Migration) {
        migrations.add(migration)
    }

    fun getCurrentVersion(): String {
        return prefs.getString("current_version", "1.0.0") ?: "1.0.0"
    }

    private fun setCurrentVersion(version: String) {
        prefs.edit().putString("current_version", version).apply()
    }

    suspend fun runMigrations(targetVersion: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val currentVersion = getCurrentVersion()
            if (currentVersion == targetVersion) {
                Timber.i("Already at target version: $targetVersion")
                return@runCatching
            }

            val applicableMigrations = migrations
                .filter { it.fromVersion == currentVersion }
                .sortedWith(compareBy { versionToNumber(it.toVersion) })

            for (migration in applicableMigrations) {
                Timber.i("Running migration: ${migration.fromVersion} -> ${migration.toVersion}: ${migration.description}")
                migration.migrate(context)
                    .onSuccess {
                        setCurrentVersion(migration.toVersion)
                        Timber.i("Migration completed: ${migration.toVersion}")
                    }
                    .onFailure { e ->
                        Timber.e(e, "Migration failed: ${migration.fromVersion} -> ${migration.toVersion}")
                        throw e
                    }
            }

            setCurrentVersion(targetVersion)
            Timber.i("All migrations completed, current version: $targetVersion")
        }
    }

    private fun versionToNumber(version: String): Long {
        return version.split(".")
            .mapIndexed { index, part ->
                (part.toLongOrNull() ?: 0) * (1000L.pow(2 - index))
            }
            .sum()
    }

    private fun Long.pow(exponent: Int): Long {
        var result = 1L
        repeat(exponent) { result *= this }
        return result
    }

    companion object {
        fun registerDefaultMigrations(manager: MigrationManager) {
            // 1.0.0 → 1.1.0: 迁移单 workspace 到 multi-agent 结构
            manager.registerMigration(
                Migration(
                    fromVersion = "1.0.0",
                    toVersion = "1.1.0",
                    description = "Multi-agent workspace migration: single workspace → workspaces/{agentId}"
                ) { context ->
                    runCatching {
                        val workingDir = context.filesDir
                        val agentsDir = File(workingDir, "agents")
                        val workspacesDir = File(workingDir, "workspaces")

                        if (!agentsDir.exists()) {
                            Timber.i("No agents directory, skipping migration")
                            return@runCatching
                        }

                        // Migrate legacy single-workspace data to per-agent workspaces
                        val legacyDialogDir = File(workingDir, "dialog")
                        if (legacyDialogDir.exists()) {
                            val defaultAgentWorkspace = File(workspacesDir, "default-agent")
                            defaultAgentWorkspace.mkdirs()

                            val targetDialogDir = File(defaultAgentWorkspace, "dialog")
                            if (!targetDialogDir.exists()) {
                                legacyDialogDir.renameTo(targetDialogDir)
                                Timber.i("Migrated legacy dialog dir to default-agent workspace")
                            }
                        }

                        // Migrate legacy memory dir
                        val legacyMemoryDir = File(workingDir, "memory")
                        if (legacyMemoryDir.exists()) {
                            val defaultAgentWorkspace = File(workspacesDir, "default-agent")
                            defaultAgentWorkspace.mkdirs()

                            val targetMemoryDir = File(defaultAgentWorkspace, "memory")
                            if (!targetMemoryDir.exists()) {
                                legacyMemoryDir.renameTo(targetMemoryDir)
                                Timber.i("Migrated legacy memory dir to default-agent workspace")
                            }
                        }

                        // Create workspace dirs for existing agents
                        agentsDir.listFiles()?.filter { it.extension == "json" }?.forEach { agentFile ->
                            val agentId = agentFile.nameWithoutExtension
                            val agentWorkspace = File(workspacesDir, agentId)
                            if (!agentWorkspace.exists()) {
                                agentWorkspace.mkdirs()
                                Timber.i("Created workspace for agent: $agentId")
                            }
                        }

                        Timber.i("Migration 1.0.0 → 1.1.0 completed")
                        Result.success(Unit)
                    }
                }
            )

            // 1.1.0 → 1.1.3: 添加插件系统目录和工具安全配置
            manager.registerMigration(
                Migration(
                    fromVersion = "1.1.0",
                    toVersion = "1.1.3",
                    description = "Add plugin system directories and tool guard config"
                ) { context ->
                    runCatching {
                        val workingDir = context.filesDir

                        // Ensure plugins directory exists
                        val pluginsDir = File(workingDir, "plugins")
                        if (!pluginsDir.exists()) {
                            pluginsDir.mkdirs()
                            Timber.i("Created plugins directory")
                        }

                        // Ensure backups directory exists
                        val backupsDir = File(workingDir, ".backups")
                        if (!backupsDir.exists()) {
                            backupsDir.mkdirs()
                            Timber.i("Created backups directory")
                        }

                        // Ensure secret directory exists
                        val secretDir = File(context.filesDir, "secret")
                        if (!secretDir.exists()) {
                            secretDir.mkdirs()
                            Timber.i("Created secret directory")
                        }

                        Timber.i("Migration 1.1.0 → 1.1.3 completed")
                        Result.success(Unit)
                    }
                }
            )

            // 1.1.3 → 1.2.0: 迁移明文密钥到加密存储
            manager.registerMigration(
                Migration(
                    fromVersion = "1.1.3",
                    toVersion = "1.2.0",
                    description = "Migrate plaintext secrets to encrypted storage"
                ) { context ->
                    runCatching {
                        val secureStorage = KoinJavaComponent.getKoin().get<SecureStorage>()
                        val migrationManager = SecretMigrationManager(context, secureStorage)
                        migrationManager.migrateIfNeeded().getOrThrow()
                    }
                }
            )
        }
    }
}



