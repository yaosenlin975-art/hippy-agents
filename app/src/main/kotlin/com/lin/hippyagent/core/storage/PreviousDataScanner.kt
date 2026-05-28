package com.lin.hippyagent.core.storage

import android.content.Context
import android.os.Environment
import timber.log.Timber
import java.io.File

/**
 * 磁盘快速扫描器 — 在首次安装打开时，扫描常见存储路径寻找曾经的 hippydata 工作区文件夹
 *
 * 扫描路径：
 * - 外部存储根目录 (/sdcard/, /storage/emulated/0/)
 * - Download 目录
 * - Documents 目录
 * - Android/data/com.lin.hippyagent/ (旧版应用数据)
 * - 常见自定义路径
 */
class PreviousDataScanner(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "previous_data_scanner"
        private const val KEY_SCANNED = "has_scanned"
        private const val COPAW_DIR_NAME = ".copaw"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 是否已完成首次扫描 */
    fun hasScanned(): Boolean = prefs.getBoolean(KEY_SCANNED, false)

    /** 标记扫描完成 */
    fun markScanned() {
        prefs.edit().putBoolean(KEY_SCANNED, true).apply()
    }

    /**
     * 快速扫描磁盘，寻找曾经的 hippydata 工作区目录
     * 返回找到的所有有效目录列表（非空且包含内容）
     */
    fun scanForPreviousData(): List<PreviousDataLocation> {
        val foundLocations = mutableListOf<PreviousDataLocation>()
        val scannedPaths = mutableSetOf<String>()

        val scanDirs = buildScanDirs()

        for (dir in scanDirs) {
            try {
                if (!dir.exists() || !dir.isDirectory) continue
                if (dir.absolutePath in scannedPaths) continue
                scannedPaths.add(dir.absolutePath)

                val copawDir = File(dir, COPAW_DIR_NAME)
                if (copawDir.exists() && copawDir.isDirectory && copawDir.hasContent()) {
                    val fileInfo = copawDir.getSummary()
                    foundLocations.add(
                        PreviousDataLocation(
                            path = copawDir.absolutePath,
                            parentPath = dir.absolutePath,
                            totalSize = fileInfo.first,
                            fileCount = fileInfo.second,
                            hasWorkspaces = File(copawDir, "workspaces").let { it.exists() && it.isDirectory && it.listFiles()?.isNotEmpty() == true },
                            hasSessions = File(copawDir, "sessions").let { it.exists() && it.isDirectory && it.listFiles()?.isNotEmpty() == true }
                        )
                    )
                }

                // 也检查直接命名为 hippydata 的子目录（深度 1）
                dir.listFiles()?.filter { it.isDirectory && it.name == COPAW_DIR_NAME }?.forEach { subCopaw ->
                    if (subCopaw.absolutePath in scannedPaths) return@forEach
                    scannedPaths.add(subCopaw.absolutePath)
                    if (subCopaw.hasContent()) {
                        val fileInfo = subCopaw.getSummary()
                        foundLocations.add(
                            PreviousDataLocation(
                                path = subCopaw.absolutePath,
                                parentPath = dir.absolutePath,
                                totalSize = fileInfo.first,
                                fileCount = fileInfo.second,
                                hasWorkspaces = File(subCopaw, "workspaces").let { it.exists() && it.isDirectory && it.listFiles()?.isNotEmpty() == true },
                                hasSessions = File(subCopaw, "sessions").let { it.exists() && it.isDirectory && it.listFiles()?.isNotEmpty() == true }
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.d(e, "Error scanning dir: ${dir.absolutePath}")
            }
        }

        return foundLocations.distinctBy { it.path }
    }

    private fun buildScanDirs(): List<File> {
        val dirs = mutableListOf<File>()

        // 1. 外部存储根目录
        try {
            val externalStorage = Environment.getExternalStorageDirectory()
            dirs.add(externalStorage)
        } catch (_: Exception) {}

        // 2. 标准目录
        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { dirs.add(it) }
        } catch (_: Exception) {}
        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.let { dirs.add(it) }
        } catch (_: Exception) {}

        // 3. 应用外部存储目录
        try {
            context.getExternalFilesDir(null)?.parentFile?.let { dirs.add(it) }
        } catch (_: Exception) {}

        // 4. 旧版应用数据目录
        try {
            val legacyDir = File(Environment.getExternalStorageDirectory(), "Android/data/com.lin.hippyagent")
            if (legacyDir.exists()) dirs.add(legacyDir)
        } catch (_: Exception) {}

        // 5. 常见自定义路径
        val customPaths = listOf(
            "/sdcard/HippyAgent",
            "/sdcard/backups",
            "/storage/emulated/0/HippyAgent"
        )
        for (path in customPaths) {
            val f = File(path)
            if (f.exists()) dirs.add(f)
        }

        return dirs
    }

    private fun File.hasContent(): Boolean {
        return listFiles()?.isNotEmpty() == true
    }

    /** 获取目录大小和文件数摘要（限制递归深度避免耗时） */
    private fun File.getSummary(maxDepth: Int = 3): Pair<Long, Int> {
        var totalSize = 0L
        var fileCount = 0
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth) return
            dir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    totalSize += f.length()
                    fileCount++
                } else if (f.isDirectory) {
                    walk(f, depth + 1)
                }
            }
        }
        walk(this, 0)
        return totalSize to fileCount
    }
}

/**
 * 找到的历史数据位置
 */
data class PreviousDataLocation(
    val path: String,
    val parentPath: String,
    val totalSize: Long,
    val fileCount: Int,
    val hasWorkspaces: Boolean,
    val hasSessions: Boolean
) {
    /** 人类可读的大小 */
    val readableSize: String
        get() = when {
            totalSize >= 1_000_000_000 -> "%.1f GB".format(totalSize / 1_000_000_000.0)
            totalSize >= 1_000_000 -> "%.1f MB".format(totalSize / 1_000_000.0)
            totalSize >= 1_000 -> "%.1f KB".format(totalSize / 1_000.0)
            else -> "$totalSize B"
        }

    /** 数据类型描述 */
    val dataTypeDescription: String
        get() = buildList {
            if (hasWorkspaces) add("工作区")
            if (hasSessions) add("会话数据")
            if (isEmpty()) add("配置文件")
        }.joinToString("、")
}


