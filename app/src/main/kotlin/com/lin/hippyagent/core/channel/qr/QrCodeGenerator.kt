package com.lin.hippyagent.core.channel.qr

import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

private val QR_SIZE = 300
private val QR_HINTS = mapOf(
    EncodeHintType.MARGIN to 2,
    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
)

fun generateQrcodeBase64(content: String): String {
    val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, QR_HINTS)
    val bitmap = Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.RGB_565)
    for (x in 0 until QR_SIZE) {
        for (y in 0 until QR_SIZE) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return ByteArrayOutputStream().use { baos ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bitmap.recycle()
        Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
