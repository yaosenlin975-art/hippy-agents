package com.lin.hippyagent.core.util

import timber.log.Timber
import java.io.File

object FileUtils {

    fun atomicWrite(file: File, content: String) {
        val tempFile = File(file.parent, "${file.name}.tmp")
        try {
            tempFile.writeText(content)
            if (file.exists() && !file.delete()) {
                Timber.w("atomicWrite: failed to delete original file ${file.path}")
            }
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
                Timber.w("atomicWrite: renameTo failed, used copyTo fallback for ${file.path}")
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    fun atomicAppend(file: File, content: String) {
        val existing = if (file.exists()) file.readText() else ""
        atomicWrite(file, existing + content)
    }
}
