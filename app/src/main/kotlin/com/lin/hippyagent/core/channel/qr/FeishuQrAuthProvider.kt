package com.lin.hippyagent.core.channel.qr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder

class FeishuQrAuthProvider(
    private val client: OkHttpClient
) : QrAuthProvider {
    override val channelId = "feishu"

    private val baseUrl = "https://accounts.feishu.cn"
    private val registrationUrl = "$baseUrl/oauth/v1/app/registration"
    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    override suspend fun fetchQrcode(): Pair<String, String> = withContext(Dispatchers.IO) {
        initRegistration()
        val (scanUrl, pollToken) = beginRegistration()
        val qrcodeBase64 = generateQrcodeBase64(scanUrl)
        Pair(qrcodeBase64, pollToken)
    }

    override suspend fun pollStatus(qrcodeText: String): QrPollResult = withContext(Dispatchers.IO) {
        try {
            val body = "action=poll&device_code=${URLEncoder.encode(qrcodeText, "UTF-8")}"
                .toRequestBody(formMediaType)
            val request = Request.Builder()
                .url(registrationUrl)
                .post(body)
                .build()
            val pollClient = client.newBuilder()
                .callTimeout(java.time.Duration.ofSeconds(10))
                .build()
            pollClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext QrPollResult.Waiting
                val data = JSONObject(response.body?.string() ?: "{}")
                val clientId = data.optString("client_id", "")
                val clientSecret = data.optString("client_secret", "")
                if (clientId.isNotEmpty() && clientSecret.isNotEmpty()) {
                    val userInfo = data.optJSONObject("user_info")
                    val openId = userInfo?.optString("open_id", "") ?: ""
                    QrPollResult.Confirmed(mapOf(
                        "appId" to clientId,
                        "appSecret" to clientSecret,
                        "openId" to openId
                    ))
                } else {
                    val error = data.optString("error", "")
                    when {
                        error == "expired_token" || error == "invalid_grant" -> QrPollResult.Expired
                        error == "access_denied" -> QrPollResult.Expired
                        error == "authorization_pending" || error == "slow_down" -> QrPollResult.Waiting
                        error.isNotEmpty() -> QrPollResult.Expired
                        else -> QrPollResult.Waiting
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Feishu poll error")
            QrPollResult.Waiting
        }
    }

    private fun initRegistration() {
        val body = "action=init".toRequestBody(formMediaType)
        val request = Request.Builder()
            .url(registrationUrl)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Feishu init failed: HTTP ${response.code}")
            }
            val data = JSONObject(response.body?.string() ?: "{}")
            val methods = data.optJSONArray("supported_auth_methods")
                ?: throw RuntimeException("No auth methods")
            val hasClientSecret = (0 until methods.length()).any { methods.getString(it) == "client_secret" }
            if (!hasClientSecret) throw RuntimeException("client_secret auth not supported")
        }
    }

    private fun beginRegistration(): Pair<String, String> {
        val body = "action=begin&archetype=PersonalAgent&auth_method=client_secret&request_user_info=open_id"
            .toRequestBody(formMediaType)
        val request = Request.Builder()
            .url(registrationUrl)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Feishu begin failed: HTTP ${response.code}")
            }
            val data = JSONObject(response.body?.string() ?: "{}")
            val deviceCode = data.optString("device_code", "")
            val verificationUri = data.optString("verification_uri_complete", "")
            if (deviceCode.isEmpty() || verificationUri.isEmpty()) {
                throw RuntimeException("Feishu begin: missing device_code or verification_uri")
            }
            val scanUrl = if (verificationUri.contains("?")) {
                "$verificationUri&source=HippyAgent"
            } else {
                "$verificationUri?source=HippyAgent"
            }
            return Pair(scanUrl, deviceCode)
        }
    }
}
