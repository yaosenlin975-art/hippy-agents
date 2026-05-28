package com.lin.hippyagent.core.plugin

import android.content.Context
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipHelper {
    private var context: Context? = null

    fun init(context: Context): ZipHelper {
        this.context = context.applicationContext
        Timber.d("ZipHelper initialized")
        return this
    }

    fun unzip(zipFile: File, targetDir: File, maxEntrySize: Long = 100 * 1024 * 1024) {
        targetDir.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Security: prevent path traversal
                val outFile = File(targetDir, entry.name)
                val canonicalTarget = targetDir.canonicalPath
                val canonicalOut = outFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalTarget)) {
                    Timber.w("Skipping zip entry with path traversal: ${entry.name}")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                // Security: prevent zip bomb
                if (entry.size > maxEntrySize) {
                    Timber.w("Skipping oversized zip entry: ${entry.name} (${entry.size} bytes)")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                        zis.copyTo(bos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun zip(sourceDir: File, outputFile: File, excludePatterns: List<String> = emptyList()) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
            zipRecursive(sourceDir, sourceDir, zos, excludePatterns)
        }
    }

    fun openInputStream(uri: android.net.Uri): java.io.InputStream? {
        return context?.contentResolver?.openInputStream(uri)
    }

    private fun zipRecursive(
        root: File,
        source: File,
        zos: ZipOutputStream,
        excludePatterns: List<String>
    ) {
        val compiledPatterns = excludePatterns.map { Regex(it) }
        val files = source.listFiles() ?: return
        for (file in files) {
            val relativePath = file.relativeTo(root).path.replace("\\", "/")
            if (compiledPatterns.any { pattern ->
                    relativePath.contains(pattern.pattern) || file.name.matches(pattern)
                }) continue

            if (file.isDirectory) {
                zipRecursive(root, file, zos, excludePatterns)
            } else {
                val entry = ZipEntry(relativePath)
                zos.putNextEntry(entry)
                FileInputStream(file).use { fis -> fis.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}

