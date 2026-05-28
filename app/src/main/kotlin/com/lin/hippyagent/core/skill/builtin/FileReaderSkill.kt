package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException as JavaFileNotFoundException

/**
 * 文件阅读器技能 - 支持多种格式的文件内容提取
 */
class FileReaderSkill(private val context: Context) {

    /**
     * 读取文件内容
     */
    fun readFile(filePath: String): Result<String> {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return Result.failure(JavaFileNotFoundException("File not found: $filePath"))
            }

            if (!file.canRead()) {
                return Result.failure(SecurityException("Cannot read file: $filePath"))
            }

            val content = when {
                file.extension.lowercase() in TEXT_EXTENSIONS -> {
                    file.readText()
                }
                file.extension.lowercase() in BINARY_EXTENSIONS -> {
                    "[Binary file: ${file.name}, size: ${file.length()} bytes]"
                }
                else -> {
                    // 尝试作为文本读取
                    try {
                        file.readText()
                    } catch (e: Exception) {
                        "[Cannot read file: ${file.name} (${file.extension})]"
                    }
                }
            }

            Result.success(content)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read file: $filePath")
            Result.failure(e)
        }
    }

    /**
     * 读取文件元数据
     */
    fun getFileMetadata(filePath: String): Result<Map<String, Any>> {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return Result.failure(JavaFileNotFoundException("File not found: $filePath"))
            }

            val metadata = mapOf(
                "name" to file.name,
                "path" to file.absolutePath,
                "size" to file.length(),
                "extension" to file.extension,
                "isDirectory" to file.isDirectory,
                "isReadable" to file.canRead(),
                "lastModified" to file.lastModified()
            )

            Result.success(metadata)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get file metadata: $filePath")
            Result.failure(e)
        }
    }

    /**
     * 列出目录内容
     */
    fun listDirectory(dirPath: String): Result<List<Map<String, Any>>> {
        return try {
            val dir = File(dirPath)
            if (!dir.exists()) {
                return Result.failure(JavaFileNotFoundException("Directory not found: $dirPath"))
            }

            if (!dir.isDirectory) {
                return Result.failure(IllegalArgumentException("Not a directory: $dirPath"))
            }

            val items = dir.listFiles()?.map { file ->
                mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "isDirectory" to file.isDirectory,
                    "size" to file.length()
                )
            } ?: emptyList()

            Result.success(items)
        } catch (e: Exception) {
            Timber.e(e, "Failed to list directory: $dirPath")
            Result.failure(e)
        }
    }

    companion object {
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "xml", "yaml", "yml", "toml",
            "kt", "java", "py", "js", "ts", "jsx", "tsx",
            "html", "css", "scss", "less",
            "sh", "bash", "zsh", "fish",
            "sql", "csv", "tsv",
            "gradle", "properties", "cfg", "conf", "ini"
        )

        private val BINARY_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "ico",
            "mp3", "mp4", "avi", "mov", "mkv", "flv",
            "zip", "tar", "gz", "rar", "7z",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "apk", "exe", "dll", "so", "dylib"
        )
    }
}

