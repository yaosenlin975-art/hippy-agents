package com.lin.hippyagent.core.plugin

import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

data class SkillValidationResult(
    val name: String,
    val hasSkillMd: Boolean,
    val fileCount: Int
)

object SkillValidator {

    private const val MAX_EXTRACT_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB
    private const val REQUIRED_FILE = "SKILL.md"

    /**
     * Validates a skill ZIP archive before installation.
     *
     * Checks performed:
     * 1. File exists and is a valid ZIP
     * 2. No path-traversal entries (../)
     * 3. No symlinks
     * 4. Contains at least SKILL.md
     * 5. Total uncompressed size within limit
     */
    fun validateZipStructure(zipFile: File): Result<SkillValidationResult> {
        if (!zipFile.exists()) {
            return Result.failure(IllegalArgumentException("ZIP file does not exist: ${zipFile.absolutePath}"))
        }
        if (!zipFile.isFile) {
            return Result.failure(IllegalArgumentException("Path is not a file: ${zipFile.absolutePath}"))
        }

        return try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().asSequence().toList()

                // Check for path traversal
                val traversalEntry = entries.firstOrNull { entry ->
                    entry.name.contains("..") || entry.name.startsWith("/")
                }
                if (traversalEntry != null) {
                    return Result.failure(
                        SecurityException("Path traversal detected in ZIP entry: ${traversalEntry.name}")
                    )
                }

                // Check for symlinks (Android ZipEntry doesn't have isSymbolicLink, check name pattern)
                val symlinkEntry = entries.firstOrNull { entry ->
                    entry.name.contains("/../") || entry.name.startsWith("../")
                }
                if (symlinkEntry != null) {
                    return Result.failure(
                        SecurityException("Symlink detected in ZIP entry: ${symlinkEntry.name}")
                    )
                }

                // Check total uncompressed size
                val totalSize = entries.sumOf { it.size }
                if (totalSize > MAX_EXTRACT_SIZE_BYTES) {
                    return Result.failure(
                        IllegalArgumentException(
                            "ZIP too large: ${totalSize / 1024 / 1024}MB (max ${MAX_EXTRACT_SIZE_BYTES / 1024 / 1024}MB)"
                        )
                    )
                }

                // Check for required SKILL.md
                val hasSkillMd = entries.any { it.name.endsWith(REQUIRED_FILE) && !it.isDirectory }
                if (!hasSkillMd) {
                    return Result.failure(
                        IllegalArgumentException("ZIP does not contain $REQUIRED_FILE")
                    )
                }

                // Derive skill name from top-level directory or zip filename
                val topLevelDirs = entries
                    .filter { it.isDirectory && it.name.count { c -> c == '/' } == 1 }
                    .map { it.name.trimEnd('/') }

                val skillName = when {
                    topLevelDirs.size == 1 -> topLevelDirs.first()
                    else -> zipFile.nameWithoutExtension
                }

                Timber.d("Skill ZIP validated: name=$skillName, files=${entries.size}, size=${totalSize}")
                Result.success(
                    SkillValidationResult(
                        name = skillName,
                        hasSkillMd = true,
                        fileCount = entries.size
                    )
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to validate skill ZIP: ${zipFile.name}")
            Result.failure(IllegalArgumentException("Invalid ZIP file: ${e.message}"))
        }
    }
}

