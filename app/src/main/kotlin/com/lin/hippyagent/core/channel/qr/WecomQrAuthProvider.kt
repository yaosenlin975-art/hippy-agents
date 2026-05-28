package com.lin.hippyagent.core.channel.qr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.security.SecureRandom

private val WECOM_SETTINGS_REGEX = Regex("""window\.settings\s*=\s*(\{.*?})\s*;?\s*</script>""", RegexOption.DOT_MATCHES_ALL)

class WecomQrAuthProvider(
    private val client: OkHttpClient
) : QrAuthProvider {
    override val channelId = "wechat"

    private val baseUrl = "https://work.weixin.qq.com"

    override suspend fun fetchQrcode(): Pair<String, String> = withContext(Dispatchers.IO) {
        val state = generateRandomState()
        val timestamp = System.currentTimeMillis()
        val url = "$baseUrl/ai/qc/gen?source=hippyagent&state=$state&timestamp=$timestamp"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val genClient = client.newBuilder()
            .callTimeout(java.time.Duration.ofSeconds(15))
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        genClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("Wecom gen failed: HTTP ${response.code}")
            val html = response.body?.string() ?: throw RuntimeException("Wecom gen: empty response")
            val (scode, authUrl) = parseHtmlSettings(html)
            if (scode.isEmpty() || authUrl.isEmpty()) {
                throw RuntimeException("Wecom gen: failed to extract scode/auth_url from HTML")
            }
            val qrcodeBase64 = generateQrcodeBase64(authUrl)
            Pair(qrcodeBase64, scode)
        }
    }

    override suspend fun pollStatus(qrcodeText: String): QrPollResult = withContext(Dispatchers.IO) {
        try {
            val encodedScode = URLEncoder.encode(qrcodeText, "UTF-8")
            val url = "$baseUrl/ai/qc/query_result?scode=$encodedScode"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            val pollClient = client.newBuilder()
                .callTimeout(java.time.Duration.ofSeconds(10))
                .build()
            pollClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext QrPollResult.Waiting
                val outerData = JSONObject(response.body?.string() ?: "{}")
                val data = outerData.optJSONObject("data") ?: return@withContext QrPollResult.Waiting
                val status = data.optString("status", "waiting")
                if (status == "waiting") return@withContext QrPollResult.Waiting
                val botInfo = data.optJSONObject("bot_info")
                val botId = botInfo?.optString("botid", "") ?: ""
                val secret = botInfo?.optString("secret", "") ?: ""
                if (botId.isNotEmpty() && secret.isNotEmpty()) {
                    QrPollResult.Confirmed(mapOf("botId" to botId, "secret" to secret))
                } else {
                    QrPollResult.Expired
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Wecom poll error")
            QrPollResult.Waiting
        }
    }

    private fun parseHtmlSettings(html: String): Pair<String, String> {
        val match = WECOM_SETTINGS_REGEX.find(html) ?: return Pair("", "")
        val jsonStr = match.groupValues[1]
        return try {
            val settings = JSONObject(jsonStr)
            val scode = settings.optString("scode", "")
            val authUrl = settings.optString("auth_url", "")
            Pair(scode, authUrl)
        } catch (e: Exception) {
            Timber.w(e, "Wecom: failed to parse window.settings JSON")
            Pair("", "")
        }
    }

    private fun generateRandomState(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return (1..16).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }
}
