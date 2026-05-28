package com.lin.hippyagent.core.channel.qr

import com.lin.hippyagent.core.channel.ILinkClient
import org.json.JSONObject

class WeixinQrAuthProvider(
    private val ilinkClient: ILinkClient
) : QrAuthProvider {
    override val channelId = "weixin"

    override suspend fun fetchQrcode(): Pair<String, String> {
        val qrData = ilinkClient.getBotQrcode()
        val qrcode = qrData.optString("qrcode", "")
        val qrcodeImg = qrData.optString("qrcode_img_content", "")
        if (qrcode.isEmpty()) throw RuntimeException("Failed to get QR code")
        return Pair(qrcodeImg, qrcode)
    }

    override suspend fun pollStatus(qrcodeText: String): QrPollResult {
        val data = ilinkClient.getQrcodeStatus(qrcodeText)
        return when (data.optString("status")) {
            "confirmed" -> QrPollResult.Confirmed(mapOf(
                "botToken" to data.optString("bot_token", ""),
                "baseUrl" to data.optString("baseurl", "")
            ))
            "scanned" -> QrPollResult.Scanned
            "expired" -> QrPollResult.Expired
            else -> QrPollResult.Waiting
        }
    }
}
