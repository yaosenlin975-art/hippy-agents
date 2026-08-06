package com.lin.hippyagent.ui.chat

import timber.log.Timber

internal suspend fun ChatViewModel.copyAttachmentToWorkspace(
    content: String,
    attachedFileUri: String?,
    agentId: String
): Pair<String, String?> {
    Timber.d("copyAttachment: attachedFileUri=$attachedFileUri, agentId=$agentId")
    if (attachedFileUri.isNullOrBlank()) {
        Timber.w("copyAttachment: attachedFileUri is null/blank, returning empty")
        return Pair("", null)
    }

    return try {
        val uri = android.net.Uri.parse(attachedFileUri)
        Timber.d("copyAttachment: parsed uri=$uri, scheme=${uri.scheme}")
        val agent = agentFactory.getAgent(agentId)
        if (agent == null) {
            Timber.w("copyAttachment: agent is null for agentId=$agentId")
            return Pair("", null)
        }

        val workspaceDir = agent.workspaceDir
        workspaceDir.mkdirs()

        val fileName = getFileNameFromUri(uri) ?: "attachment_${System.currentTimeMillis()}"
        Timber.d("copyAttachment: fileName=$fileName, workspaceDir=${workspaceDir.absolutePath}")
        val destFile = java.io.File(workspaceDir, fileName)

        val inputStream = context.contentResolver.openInputStream(uri)
        if (inputStream == null) {
            Timber.e("copyAttachment: openInputStream returned null for uri=$uri")
            return Pair("", null)
        }
        inputStream.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        Timber.d("copyAttachment: file copied to ${destFile.absolutePath}, size=${destFile.length()}")

        // 判断是否为图
        val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        val ext = fileName.substringAfterLast(".", "").lowercase()
        val isImage = ext in imageExtensions

        // 返回目标文件绝对路径，和图片路径（如适用
        Pair(destFile.absolutePath, if (isImage) destFile.absolutePath else null)
    } catch (e: Exception) {
        Timber.e(e, "Failed to copy attachment to workspace")
        Pair("", null)
    }
}

internal fun ChatViewModel.getFileNameFromUri(uri: android.net.Uri): String? {
    var fileName: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
    }
    if (fileName == null) {
        fileName = uri.path?.substringAfterLast('/')
    }
    return fileName
}
