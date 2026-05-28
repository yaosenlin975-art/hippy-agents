package com.lin.hippyagent.core.channel

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import java.util.UUID

private const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
private const val CHANNEL_VERSION = "2.0.1"
private const val GETUPDATES_TIMEOUT = 45L
private const val DEFAULT_TIMEOUT = 15L
private const val QRCODE_STATUS_TIMEOUT = 60L

class ILinkClient(
    private var botToken: String = "",
    private var baseUrl: String = DEFAULT_BASE_URL
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(java.time.Duration.ofSeconds(GETUPDATES_TIMEOUT))
        .build()

    private fun url(path: String): String {
        return "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
    }

    private fun makeHeaders(): Map<String, String> {
        val uin = SecureRandom().nextInt(Int.MAX_VALUE)
        val uinB64 = Base64.encodeToString(uin.toString().toByteArray(), Base64.NO_WRAP)
        return buildMap {
            put("Content-Type", "application/json")
            put("AuthorizationType", "ilink_bot_token")
            put("X-WECHAT-UIN", uinB64)
            if (botToken.isNotEmpty()) {
                put("Authorization", "Bearer $botToken")
            }
        }
    }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap(), timeout: Long = DEFAULT_TIMEOUT): JSONObject = withContext(Dispatchers.IO) {
        val urlBuilder = url(path).toHttpUrl().newBuilder()
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val requestBuilder = Request.Builder().url(urlBuilder.build()).get()
        makeHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val response = client.newBuilder().callTimeout(java.time.Duration.ofSeconds(timeout)).build()
            .newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) throw RuntimeException("ILink GET $path failed: HTTP ${response.code}")
        JSONObject(response.body?.string() ?: "{}")
    }

    private suspend fun post(path: String, body: JSONObject, timeout: Long = DEFAULT_TIMEOUT): JSONObject = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url(path))
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        makeHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val response = client.newBuilder().callTimeout(java.time.Duration.ofSeconds(timeout)).build()
            .newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) throw RuntimeException("ILink POST $path failed: HTTP ${response.code}")
        JSONObject(response.body?.string() ?: "{}")
    }

    suspend fun getBotQrcode(): JSONObject = get("ilink/bot/get_bot_qrcode", mapOf("bot_type" to "3"))

    suspend fun getQrcodeStatus(qrcode: String): JSONObject = get(
        "ilink/bot/get_qrcode_status",
        mapOf("qrcode" to qrcode),
        timeout = QRCODE_STATUS_TIMEOUT
    )

    suspend fun waitForLogin(qrcode: String, pollInterval: Long = 1500L, maxWait: Long = 300_000L): Pair<String, String> {
        var elapsed = 0L
        while (elapsed < maxWait) {
            try {
                val data = getQrcodeStatus(qrcode)
                when (data.optString("status")) {
                    "confirmed" -> {
                        val token = data.optString("bot_token", "")
                        val newBaseUrl = data.optString("baseurl", baseUrl)
                        return Pair(token, newBaseUrl)
                    }
                    "expired" -> throw RuntimeException("WeChat QR code expired, please retry login")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "weixin: QR status poll error, retrying")
            }
            delay(pollInterval)
            elapsed += pollInterval
        }
        throw RuntimeException("WeChat QR code not scanned within ${maxWait / 1000}s")
    }

    suspend fun getupdates(cursor: String = ""): JSONObject {
        val body = JSONObject().apply {
            put("get_updates_buf", cursor)
            put("base_info", JSONObject().put("channel_version", CHANNEL_VERSION))
        }
        return post("ilink/bot/getupdates", body, timeout = GETUPDATES_TIMEOUT)
    }

    suspend fun sendText(toUserId: String, text: String, contextToken: String): JSONObject {
        val msg = JSONObject().apply {
            put("from_user_id", "")
            put("to_user_id", toUserId)
            put("client_id", UUID.randomUUID().toString())
            put("message_type", 2)
            put("message_state", 2)
            put("context_token", contextToken)
            put("item_list", JSONArray().put(
                JSONObject().put("type", 1).put("text_item", JSONObject().put("text", text))
            ))
        }
        return post("ilink/bot/sendmessage", JSONObject().apply {
            put("msg", msg)
            put("base_info", JSONObject().put("channel_version", CHANNEL_VERSION))
        })
    }

    suspend fun sendTyping(toUserId: String, typingTicket: String, status: Int = 1): JSONObject {
        return post("ilink/bot/sendtyping", JSONObject().apply {
            put("ilink_user_id", toUserId)
            put("typing_ticket", typingTicket)
            put("status", status)
            put("base_info", JSONObject().put("channel_version", CHANNEL_VERSION))
        })
    }

    suspend fun getConfig(ilinkUserId: String = "", contextToken: String = ""): JSONObject {
        val body = JSONObject().apply {
            if (ilinkUserId.isNotEmpty()) put("ilink_user_id", ilinkUserId)
            if (contextToken.isNotEmpty()) put("context_token", contextToken)
            put("base_info", JSONObject().put("channel_version", CHANNEL_VERSION))
        }
        return post("ilink/bot/getconfig", body)
    }
}

class WeixinChannel(
    private var botToken: String = "",
    private var baseUrl: String = DEFAULT_BASE_URL,
    private val tokenFile: File? = null
) : Channel {
    override val id = "weixin"
    override val name = "个人微信"
    override var isEnabled = true

    private val _messageFlow = MutableSharedFlow<ChannelMessage>()
    private var lastActivityTime = 0L
    private var connected = false
    private var ilinkClient: ILinkClient? = null
    private var pollJob: Job? = null
    private val pollScope = CoroutineScope(Dispatchers.IO)
    private var cursor = ""
    private val processedIds = mutableSetOf<String>()
    private val userContextTokens = mutableMapOf<String, String>()

    override suspend fun sendMessage(message: ChannelMessage): Result<Unit> = runCatching {
        val client = ilinkClient ?: throw RuntimeException("WeixinChannel not connected")
        val toUserId = message.metadata["weixin_from_user_id"] ?: ""
        val contextToken = message.metadata["weixin_context_token"]
            ?: userContextTokens[toUserId] ?: ""
        if (toUserId.isEmpty() || contextToken.isEmpty()) {
            throw RuntimeException("Missing to_user_id or context_token")
        }
        val resp = client.sendText(toUserId, message.content, contextToken)
        val ret = resp.optInt("ret", -1)
        if (ret != 0) {
            Timber.w("weixin send rejected: ret=$ret errcode=${resp.optInt("errcode", -1)}")
        }
        lastActivityTime = System.currentTimeMillis()
        _messageFlow.emit(message)
    }

    override suspend fun receiveMessages(): Flow<ChannelMessage> = _messageFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = runCatching {
        if (botToken.isEmpty()) {
            botToken = loadTokenFromFile()
        }
        if (botToken.isEmpty()) {
            throw RuntimeException("No bot_token configured. Please login via QR code first.")
        }
        ilinkClient = ILinkClient(botToken = botToken, baseUrl = baseUrl)
        connected = true
        startPolling()
        Timber.i("weixin channel connected (token=${botToken.take(12)}...)")
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        pollJob?.cancel()
        pollJob = null
        connected = false
        ilinkClient = null
        Timber.i("weixin channel disconnected")
    }

    override suspend fun isConnected(): Boolean = connected && isEnabled && pollJob?.isActive == true

    override suspend fun healthCheck(): ChannelHealthStatus {
        if (!isEnabled) return ChannelHealthStatus(id, false, lastActivityTime, error = "Channel disabled")
        if (ilinkClient == null) return ChannelHealthStatus(id, false, lastActivityTime, error = "Client not initialized")
        if (botToken.isEmpty()) return ChannelHealthStatus(id, false, lastActivityTime, error = "No bot token")
        return ChannelHealthStatus(id, true, lastActivityTime)
    }

    override suspend fun restart(): Result<Unit> = runCatching {
        disconnect().getOrThrow()
        connect().getOrThrow()
        Timber.i("weixin channel restarted")
    }

    suspend fun loginWithQrcode(onQrcodeReady: (String) -> Unit): Result<Unit> = runCatching {
        val loginClient = ILinkClient(baseUrl = baseUrl)
        val qrData = loginClient.getBotQrcode()
        val qrcode = qrData.optString("qrcode", "")
        val qrcodeImg = qrData.optString("qrcode_img_content", "")
        if (qrcode.isEmpty()) throw RuntimeException("Failed to get QR code")
        onQrcodeReady(qrcodeImg)
        val (token, newBaseUrl) = loginClient.waitForLogin(qrcode)
        botToken = token
        if (newBaseUrl.isNotEmpty()) baseUrl = newBaseUrl.trimEnd('/')
        saveTokenToFile(token)
        ilinkClient = ILinkClient(botToken = botToken, baseUrl = baseUrl)
        connected = true
        startPolling()
        Timber.i("weixin QR login succeeded")
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = pollScope.launch {
            val client = ilinkClient ?: return@launch
            while (coroutineContext.isActive) {
                try {
                    val data = client.getupdates(cursor)
                    val newCursor = data.optString("get_updates_buf", "")
                    if (newCursor.isNotEmpty()) cursor = newCursor
                    val msgs = data.optJSONArray("msgs")
                    if (msgs != null) {
                        for (i in 0 until msgs.length()) {
                            processMessage(msgs.getJSONObject(i))
                        }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Timber.e(e, "weixin poll error, retry in 5s")
                    delay(5000)
                }
            }
        }
    }

    private suspend fun processMessage(msg: JSONObject) {
        try {
            val fromUserId = msg.optString("from_user_id", "")
            val contextToken = msg.optString("context_token", "")
            val msgType = msg.optInt("message_type", 0)
            if (msgType != 1) return

            val dedupKey = contextToken.ifEmpty { "${fromUserId}_${msg.optString("msg_id", "")}" }
            if (dedupKey.isNotEmpty() && processedIds.contains(dedupKey)) return
            if (dedupKey.isNotEmpty()) {
                processedIds.add(dedupKey)
                if (processedIds.size > 10000) {
                    val iterator = processedIds.iterator()
                    repeat(5000) { if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
                }
            }

            if (fromUserId.isNotEmpty() && contextToken.isNotEmpty()) {
                userContextTokens[fromUserId] = contextToken
            }

            val textParts = mutableListOf<String>()
            val itemList = msg.optJSONArray("item_list")
            if (itemList != null) {
                for (i in 0 until itemList.length()) {
                    val item = itemList.getJSONObject(i)
                    when (item.optInt("type", 0)) {
                        1 -> {
                            val text = item.optJSONObject("text_item")?.optString("text", "")?.trim() ?: ""
                            if (text.isNotEmpty()) textParts.add(text)
                        }
                        2 -> textParts.add("[image]")
                        3 -> {
                            val voiceItem = item.optJSONObject("voice_item")
                            val asrText = voiceItem?.optJSONObject("text_item")?.optString("text", "")?.trim() ?: ""
                            if (asrText.isNotEmpty()) textParts.add(asrText) else textParts.add("[voice]")
                        }
                        4 -> textParts.add("[file]")
                        5 -> textParts.add("[video]")
                    }
                }
            }

            val text = textParts.joinToString("\n").trim()
            if (text.isEmpty()) return

            val groupId = msg.optString("group_id", "")
            val sessionId = if (groupId.isNotEmpty()) "weixin:group:$groupId" else "weixin:$fromUserId"

            lastActivityTime = System.currentTimeMillis()
            _messageFlow.emit(
                ChannelMessage(
                    content = text,
                    senderId = fromUserId,
                    sessionId = sessionId,
                    metadata = mapOf(
                        "weixin_from_user_id" to fromUserId,
                        "weixin_context_token" to contextToken,
                        "weixin_group_id" to groupId
                    )
                )
            )
            Timber.d("weixin recv: from=${fromUserId.take(20)} text_len=${text.length}")
        } catch (e: Exception) {
            Timber.e(e, "weixin processMessage failed")
        }
    }

    private fun loadTokenFromFile(): String {
        return try {
            tokenFile?.let { if (it.exists()) it.readText().trim() else "" } ?: ""
        } catch (e: Exception) {
            Timber.w(e, "weixin: failed to read token file")
            ""
        }
    }

    private fun saveTokenToFile(token: String) {
        try {
            tokenFile?.let {
                it.parentFile?.mkdirs()
                it.writeText(token)
                Timber.i("weixin: bot_token saved to ${it.absolutePath}")
            }
        } catch (e: Exception) {
            Timber.w(e, "weixin: failed to save token file")
        }
    }
}
