package com.lin.hippyagent.core.plugin

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import timber.log.Timber

object UrlDownloader {

    suspend fun downloadFile(url: String, destination: File): Result<File> = runCatching {
        Timber.d("Downloading file from $url to ${destination.absolutePath}")

        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            if (connection.responseCode != 200) {
                throw Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}")
            }

            val contentLength = connection.contentLength
            if (contentLength > 50 * 1024 * 1024) {
                throw Exception("File too large: ${contentLength / 1024 / 1024}MB (max 50MB)")
            }

            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        totalBytes += bytesRead
                        if (totalBytes > 50 * 1024 * 1024) {
                            throw Exception("Download exceeds 50MB limit")
                        }
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            if (!destination.exists() || destination.length() == 0L) {
                throw Exception("Download failed: file empty or not created")
            }

            Timber.d("Download completed: ${destination.length()} bytes")
            destination
        } finally {
            connection.disconnect()
        }
    }.onFailure { e ->
        Timber.e(e, "Download failed from $url")
        if (destination.exists()) destination.delete()
    }
}

