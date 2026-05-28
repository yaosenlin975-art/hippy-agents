package com.lin.hippyagent.core.channel.feishu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

@Serializable
data class TenantTokenRequest(
    val app_id: String,
    val app_secret: String
)

@Serializable
data class TenantTokenResponse(
    val tenant_access_token: String = "",
    val expire: Int = 0,
    val code: Int = -1,
    val msg: String = ""
)

@Serializable
data class SendMessageRequest(
    val receive_id: String,
    val msg_type: String = "text",
    val content: String
)

@Serializable
data class SendMessageResponse(
    val code: Int = -1,
    val msg: String = "",
    val data: MessageData? = null
)

@Serializable
data class MessageData(
    val message_id: String = ""
)

@Serializable
data class ListMessagesResponse(
    val code: Int = -1,
    val msg: String = "",
    val data: ListMessagesData? = null
)

@Serializable
data class ListMessagesData(
    val items: List<FeishuMessageItem> = emptyList(),
    val has_more: Boolean = false,
    val page_token: String = ""
)

@Serializable
data class FeishuMessageItem(
    val message_id: String = "",
    val chat_id: String = "",
    val sender: FeishuSender? = null,
    val msg_type: String = "",
    val content: String = "",
    val create_time: String = ""
)

@Serializable
data class FeishuSender(
    val sender_id: FeishuSenderId? = null,
    val sender_type: String = ""
)

@Serializable
data class FeishuSenderId(
    val open_id: String = "",
    val user_id: String = ""
)

class FeishuApiClient(
    private val appId: String,
    private val appSecret: String,
    private val client: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var accessToken: String = ""
    private var tokenExpireAt: Long = 0L

    suspend fun getTenantAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (accessToken.isNotEmpty() && System.currentTimeMillis() < tokenExpireAt) {
                return@runCatching accessToken
            }
            val request = TenantTokenRequest(appId, appSecret)
            val body = json.encodeToString(TenantTokenRequest.serializer(), request)
                .toRequestBody(JSON_MEDIA_TYPE)
            val httpRequest = Request.Builder()
                .url("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")
                .post(body)
                .build()
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
                ?: throw RuntimeException("Empty response from tenant_access_token API")
            val tokenResp = json.decodeFromString<TenantTokenResponse>(responseBody)
            if (tokenResp.code != 0) {
                throw RuntimeException("Feishu auth failed: code=${tokenResp.code}, msg=${tokenResp.msg}")
            }
            accessToken = tokenResp.tenant_access_token
            tokenExpireAt = System.currentTimeMillis() + (tokenResp.expire - 60) * 1000L
            Timber.i("Feishu tenant_access_token obtained, expires in ${tokenResp.expire}s")
            accessToken
        }
    }

    suspend fun sendMessage(receiveId: String, text: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val token = getTenantAccessToken().getOrThrow()
            val contentJson = json.encodeToString(
                kotlinx.serialization.serializer<Map<String, String>>(),
                mapOf("text" to text)
            )
            val request = SendMessageRequest(
                receive_id = receiveId,
                content = contentJson
            )
            val body = json.encodeToString(SendMessageRequest.serializer(), request)
                .toRequestBody(JSON_MEDIA_TYPE)
            val httpRequest = Request.Builder()
                .url("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
                ?: throw RuntimeException("Empty response from send message API")
            val sendResp = json.decodeFromString<SendMessageResponse>(responseBody)
            if (sendResp.code != 0) {
                throw RuntimeException("Feishu send failed: code=${sendResp.code}, msg=${sendResp.msg}")
            }
            Timber.i("Feishu message sent to $receiveId, messageId=${sendResp.data?.message_id}")
            sendResp.data?.message_id ?: ""
        }
    }

    suspend fun listMessages(chatId: String, pageSize: Int = 20): Result<List<FeishuMessageItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = getTenantAccessToken().getOrThrow()
            val httpRequest = Request.Builder()
                .url("https://open.feishu.cn/open-apis/im/v1/messages?container_id_type=chat&container_id=$chatId&page_size=$pageSize")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
                ?: throw RuntimeException("Empty response from list messages API")
            val listResp = json.decodeFromString<ListMessagesResponse>(responseBody)
            if (listResp.code != 0) {
                throw RuntimeException("Feishu list messages failed: code=${listResp.code}, msg=${listResp.msg}")
            }
            listResp.data?.items ?: emptyList()
        }
    }

    fun clearToken() {
        accessToken = ""
        tokenExpireAt = 0L
    }
}
