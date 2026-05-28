package com.lin.hippyagent.core.channel.qr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

private val BASE64_DATA_URI_REGEX = Regex("^data:image/[^;]+;base64,")

object QrImageSaver {

    suspend fun saveToGallery(context: Context, base64Image: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val cleanBase64 = BASE64_DATA_URI_REGEX.replace(base64Image, "")
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)

            val filename = "hippy_qr_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/HippyAgent")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null

            resolver.openOutputStream(uri)?.use { os ->
                os.write(bytes)
                os.flush()
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            Timber.i("QR image saved to gallery: $uri")
            uri
        } catch (e: Exception) {
            Timber.e(e, "Failed to save QR image to gallery")
            null
        }
    }
}

fun base64ToBitmap(base64String: String): Bitmap? {
    return try {
        val cleanBase64 = BASE64_DATA_URI_REGEX.replace(base64String, "")
        val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        Timber.e(e, "Failed to decode base64 bitmap")
        null
    }
}
