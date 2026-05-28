package com.lin.hippyagent.core.memory.gallery

import timber.log.Timber
import java.io.File
import java.time.Instant

class GalleryMemoryStore(workspaceDir: File) {

    private val memoryFile = File(workspaceDir, "IMAGE-MEMORY.md")

    suspend fun syncEntries(entries: List<GalleryMemoryScanner.ImageEntry>) {
        val content = buildString {
            appendLine("# 图片记忆")
            appendLine()
            appendLine("| 文件名 | 时间 | 相册 | 类型 |")
            appendLine("|--------|------|------|------|")
            for (entry in entries) {
                appendLine("| ${entry.fileName} | ${entry.dateTaken} | ${entry.bucketName} | ${entry.mimeType} |")
            }
        }
        memoryFile.writeText(content)
        Timber.i("GalleryMemoryStore: synced ${entries.size} entries")
    }

    suspend fun searchByKeyword(keyword: String): List<GalleryMemoryScanner.ImageEntry> {
        if (!memoryFile.exists()) return emptyList()
        val lines = memoryFile.readLines()
        return lines.filter { it.contains(keyword, ignoreCase = true) }
            .mapNotNull { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size >= 5) GalleryMemoryScanner.ImageEntry(
                    fileName = parts[1],
                    dateTaken = Instant.now(),
                    bucketName = parts[3],
                    mimeType = parts[4],
                    summary = parts[1]
                ) else null
            }
    }

    suspend fun getRecentEntries(limit: Int): List<GalleryMemoryScanner.ImageEntry> {
        if (!memoryFile.exists()) return emptyList()
        val lines = memoryFile.readLines().drop(4)
        return lines.take(limit).mapNotNull { line ->
            val parts = line.split("|").map { it.trim() }
            if (parts.size >= 5) GalleryMemoryScanner.ImageEntry(
                fileName = parts[1],
                dateTaken = Instant.now(),
                bucketName = parts[3],
                mimeType = parts[4],
                summary = parts[1]
            ) else null
        }
    }
}
