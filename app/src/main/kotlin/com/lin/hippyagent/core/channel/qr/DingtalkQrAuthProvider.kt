package com.lin.hippyagent.core.channel.qr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

class DingtalkQrAuthProvider(
    private val client: OkHttpClient
) : QrAuthProvider {
    override val channelId = "dingtalk"

    private val baseUrl = "https://oapi.dingtalk.com"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun fetchQrcode(): Pair<String, String> = withContext(Dispatchers.IO) {
        val nonce = initRegistration()
        val (scanUrl, pollToken) = beginRegistration(nonce)
        val qrcodeBase64 = generateQrcodeBase64(scanUrl)
        Pair(qrcodeBase64, pollToken)
    }

    override suspend fun pollStatus(qrcodeText: String): QrPollResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("device_code", qrcodeText)
                .toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$baseUrl/app/registration/poll")
                .post(body)
                .build()
            val pollClient = client.newBuilder()
                .callTimeout(java.time.Duration.ofSeconds(10))
                .build()
            pollClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext QrPollResult.Waiting
                val data = JSONObject(response.body?.string() ?: "{}")
                when (data.optString("status")) {
                    "SUCCESS" -> {
                        val clientId = data.optString("client_id", "")
                        val clientSecret = data.optString("client_secret", "")
                        if (clientId.isNotEmpty() && clientSecret.isNotEmpty()) {
                            QrPollResult.Confirmed(mapOf(
                                "clientId" to clientId,
                                "clientSecret" to clientSecret
                            ))
                        } else {
                            QrPollResult.Expired
                        }
                    }
                    "FAIL" -> QrPollResult.Expired
                    "EXPIRED" -> QrPollResult.Expired
                    else -> QrPollResult.Waiting
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Dingtalk poll error")
            QrPollResult.Waiting
        }
    }

    private fun initRegistration(): String {
        val body = JSONObject().put("source", "HIPPYAGENT")
            .toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/app/registration/init")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("Dingtalk init failed: HTTP ${response.code}")
            val data = JSONObject(response.body?.string() ?: "{}")
            if (data.optInt("errcode", -1) != 0) throw RuntimeException("Dingtalk init error: ${data.optString("errmsg")}")
            val nonce = data.optString("nonce", "")
            if (nonce.isEmpty()) throw RuntimeException("Dingtalk init: missing nonce")
            return nonce
        }
    }

    private fun beginRegistration(nonce: String): Pair<String, String> {
        val body = JSONObject().put("nonce", nonce)
            .toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/app/registration/begin")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("Dingtalk begin failed: HTTP ${response.code}")
            val data = JSONObject(response.body?.string() ?: "{}")
            if (data.optInt("errcode", -1) != 0) throw RuntimeException("Dingtalk begin error: ${data.optString("errmsg")}")
            val deviceCode = data.optString("device_code", "")
            val verificationUri = data.optString("verification_uri_complete", "")
            if (deviceCode.isEmpty() || verificationUri.isEmpty()) {
                throw RuntimeException("Dingtalk begin: missing device_code or verification_uri")
            }
            return Pair(verificationUri, deviceCode)
        }
    }
}
