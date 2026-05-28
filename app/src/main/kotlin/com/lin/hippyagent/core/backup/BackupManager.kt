package com.lin.hippyagent.core.backup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份条目
 */
@Serializable
data class BackupEntry(
    val id: String,
    val name: String,
    val createdAt: String,
    val sizeBytes: Long,
    val scope: BackupScope,
    val agentIds: List<String> = emptyList()
)

/**
 * 备份范围
 */
@Serializable
enum class BackupScope {
    FULL,           // 完整备份
    AGENTS_ONLY,    // 仅智能体
    CONFIG_ONLY,    // 仅配置
    SKILLS_ONLY     // 仅技能
}

/**
 * 恢复模式
 */
enum class RestoreMode {
    FULL,           // 完全替换
    CUSTOM          // 选择性恢复（默认）
}

/**
 * 备份与恢复管理器
 */
class BackupManager(
    private val context: Context
) {
    private val backupDir by lazy {
        File(context.filesDir, "backups").apply { mkdirs() }
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * 创建备份
     */
    suspend fun createBackup(
        name: String,
        scope: BackupScope = BackupScope.FULL,
        agentIds: List<String> = emptyList()
    ): Result<BackupEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val backupId = "backup_${System.currentTimeMillis()}"
            val backupFolder = File(backupDir, backupId).apply { mkdirs() }
            val zipFile = File(backupDir, "$backupId.zip")

            // 保存备份元数据
            val entry = BackupEntry(
                id = backupId,
                name = name,
                createdAt = Instant.now().toString(),
                sizeBytes = 0,
                scope = scope,
                agentIds = agentIds
            )
            File(backupFolder, "metadata.json").writeText(json.encodeToString(entry))

            // 根据范围备份不同内容
            when (scope) {
                BackupScope.FULL -> {
                    copyDirIfExists(File(context.filesDir, "workspace"), File(backupFolder, "workspace"))
                    copyDirIfExists(File(context.filesDir, "memory"), File(backupFolder, "memory"))
                    copyDirIfExists(File(context.filesDir, "missions"), File(backupFolder, "missions"))
                    copyDirIfExists(File(context.filesDir, "dream"), File(backupFolder, "dream"))
                }
                BackupScope.AGENTS_ONLY -> {
                    copyDirIfExists(File(context.filesDir, "workspace"), File(backupFolder, "workspace"))
                }
                BackupScope.CONFIG_ONLY -> {
                    // 配置文件备份
                }
                BackupScope.SKILLS_ONLY -> {
                    // 技能池备份
                }
            }

            // 压缩为 ZIP
            zipDirectory(backupFolder, zipFile)

            // 更新大小
            val size = zipFile.length()
            val finalEntry = entry.copy(sizeBytes = size)
            File(backupFolder, "metadata.json").writeText(json.encodeToString(finalEntry))

            // 清理临时文件夹
            backupFolder.deleteRecursively()

            Timber.i("Backup created: $name (${size / 1024}KB)")
            finalEntry
        }
    }

    /**
     * 列出所有备份
     */
    suspend fun listBackups(): List<BackupEntry> = withContext(Dispatchers.IO) {
        backupDir.listFiles { file -> file.extension == "zip" }
            ?.mapNotNull { zipFile ->
                runCatching {
                    val backupId = zipFile.nameWithoutExtension
                    val metadataFile = File(backupDir, "$backupId/metadata.json")
                    if (metadataFile.exists()) {
                        json.decodeFromString<BackupEntry>(metadataFile.readText())
                    } else {
                        BackupEntry(
                            id = backupId,
                            name = backupId,
                            createdAt = Instant.ofEpochMilli(zipFile.lastModified()).toString(),
                            sizeBytes = zipFile.length(),
                            scope = BackupScope.FULL
                        )
                    }
                }.getOrNull()
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /**
     * 恢复备份
     */
    suspend fun restoreBackup(
        backupId: String,
        mode: RestoreMode = RestoreMode.CUSTOM
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val zipFile = File(backupDir, "$backupId.zip")
            if (!zipFile.exists()) throw IllegalArgumentException("备份文件不存在: $backupId")

            val tempDir = File(context.cacheDir, "restore_$backupId").apply { mkdirs() }

            try {
                // 解压
                unzipFile(zipFile, tempDir)

                // 根据模式恢复
                when (mode) {
                    RestoreMode.FULL -> {
                        // 完全替换：先清理现有数据
                        File(context.filesDir, "workspace").deleteRecursively()
                        File(context.filesDir, "memory").deleteRecursively()
                        File(context.filesDir, "missions").deleteRecursively()
                    }
                    RestoreMode.CUSTOM -> {
                        // 选择性恢复：合并数据
                    }
                }

                // 复制恢复的数据
                copyDirIfExists(File(tempDir, "workspace"), File(context.filesDir, "workspace"))
                copyDirIfExists(File(tempDir, "memory"), File(context.filesDir, "memory"))
                copyDirIfExists(File(tempDir, "missions"), File(context.filesDir, "missions"))
                copyDirIfExists(File(tempDir, "dream"), File(context.filesDir, "dream"))

                Timber.i("Backup restored: $backupId (mode: $mode)")
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * 删除备份
     */
    suspend fun deleteBackup(backupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val zipFile = File(backupDir, "$backupId.zip")
            val metadataDir = File(backupDir, backupId)

            if (zipFile.exists()) zipFile.delete()
            if (metadataDir.exists()) metadataDir.deleteRecursively()

            Timber.i("Backup deleted: $backupId")
        }
    }

    /**
     * 导出备份为 ZIP
     */
    suspend fun exportBackup(backupId: String, destFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceZip = File(backupDir, "$backupId.zip")
            if (!sourceZip.exists()) throw IllegalArgumentException("备份文件不存在: $backupId")

            sourceZip.copyTo(destFile, overwrite = true)
            Timber.i("Backup exported: $backupId -> ${destFile.absolutePath}")
        }
    }

    /**
     * 导入备份 ZIP
     */
    suspend fun importBackup(sourceZip: File): Result<BackupEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val backupId = "imported_${System.currentTimeMillis()}"
            val destZip = File(backupDir, "$backupId.zip")

            sourceZip.copyTo(destZip, overwrite = true)

            val entry = BackupEntry(
                id = backupId,
                name = "导入的备份",
                createdAt = Instant.now().toString(),
                sizeBytes = destZip.length(),
                scope = BackupScope.FULL
            )

            Timber.i("Backup imported: ${sourceZip.name}")
            entry
        }
    }

    /**
     * 复制目录
     */
    private fun copyDirIfExists(source: File, dest: File) {
        if (!source.exists()) return
        source.copyRecursively(dest, overwrite = true)
    }

    /**
     * 压缩目录为 ZIP
     */
    private fun zipDirectory(sourceDir: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(sourceDir).path.replace(File.separatorChar, '/')
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }

    /**
     * 解压 ZIP 文件
     */
    private fun unzipFile(zipFile: File, destDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (!entry.isDirectory) {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}

