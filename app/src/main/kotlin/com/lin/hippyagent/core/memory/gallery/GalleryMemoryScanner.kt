package com.lin.hippyagent.core.memory.gallery

import android.content.Context
import android.provider.MediaStore
import timber.log.Timber
import java.time.Instant

class GalleryMemoryScanner(private val context: Context) {

    data class ImageEntry(
        val fileName: String,
        val dateTaken: Instant,
        val bucketName: String,
        val mimeType: String,
        val summary: String
    )

    suspend fun scanRecent(limit: Int = 50): List<ImageEntry> {
        val entries = mutableListOf<ImageEntry>()
        try {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $limit"

            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val fileName = cursor.getString(nameIdx) ?: continue
                    val dateTaken = cursor.getLong(dateIdx)
                    val bucketName = cursor.getString(bucketIdx) ?: ""
                    val mimeType = cursor.getString(mimeIdx) ?: ""
                    val summary = fileName.substringBeforeLast(".")

                    entries.add(ImageEntry(
                        fileName = fileName,
                        dateTaken = Instant.ofEpochMilli(dateTaken),
                        bucketName = bucketName,
                        mimeType = mimeType,
                        summary = summary
                    ))
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "GalleryMemoryScanner: scan failed")
        }
        return entries
    }
}
