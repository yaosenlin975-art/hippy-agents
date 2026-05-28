package com.lin.hippyagent.core.model

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import timber.log.Timber
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class ModelProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val protocol: String = "openai", // "openai", "anthropic", "ollama", "litertlm"
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val isVirtual: Boolean = false,
    val models: List<ModelConfig> = emptyList()
)

@Serializable
data class ModelConfig(
    val id: String,
    val providerId: String = "",
    val name: String,
    val displayName: String = "",
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val maxTokens: Int? = null,
    val contextWindow: Int? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val capabilities: Set<ModelCapability> = emptySet(),
    val free: Boolean = false
)

@Serializable
enum class ModelCapability {
    VISION,
    AUDIO,
    TOOL_CALL,
    STREAMING,
    REASONING
}

data class ModelCallRequest(
    val model: String,
    val messages: List<ModelMessage>,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val stream: Boolean = false,
    val tools: List<ModelToolDefinition>? = null
)

data class ModelMessage(
    val role: String,
    val content: String,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCallInfo>? = null,
    val reasoningContent: String? = null,
    val contentBlocks: List<ContentBlock>? = null
)

/** 多模态内容块 */
sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class ImageUrl(val imageUrl: ImageUrlDetail) : ContentBlock()
}

data class ImageUrlDetail(
    val url: String,
    val detail: String? = null
)

data class ToolCallInfo(
    val id: String,
    val type: String = "function",
    val function: FunctionInfo,
    val index: Int = -1
)

data class FunctionInfo(
    val name: String,
    val arguments: String
)

data class ModelToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

interface ModelClient {
    suspend fun chatCompletion(request: ModelCallRequest): ModelCallResponse
    suspend fun chatCompletionStream(request: ModelCallRequest): Flow<ModelStreamChunk>
    suspend fun testConnection(): Result<Unit>
    suspend fun listModels(): List<String>
}

data class ModelCallResponse(
    val id: String,
    val choices: List<ModelChoice>,
    val usage: ModelUsage? = null
)

data class ModelChoice(
    val index: Int,
    val message: ModelMessage,
    val finishReason: String? = null
)

data class ModelUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0
)

data class ModelStreamChunk(
    val id: String,
    val choices: List<ModelStreamChoice>
)

data class ModelStreamChoice(
    val index: Int,
    val delta: ModelMessage,
    val finishReason: String? = null
)

private val json = Json { ignoreUnknownKeys = true }
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val sharedSbPool = com.lin.hippyagent.core.pool.StringBuilderPool(maxSize = 4)

/** 共享 OkHttpClient 实例，复用连接池和线程池，减少 GC 和连接延迟 */
val sharedOkHttpClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(5, 5, java.util.concurrent.TimeUnit.MINUTES))
        // 仅对 GET 请求启用 HTTP 缓存；POST/SSE 等非幂等请求不应缓存
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.method == "GET") {
                response.newBuilder()
                    .header("Cache-Control", "public, max-age=300")
                    .build()
            } else {
                response.newBuilder()
                    .header("Cache-Control", "no-store")
                    .build()
            }
        }
        .cache(okhttp3.Cache(
            java.io.File(System.getProperty("java.io.tmpdir"), "hippy_http_cache"),
            10L * 1024 * 1024
        ))
        .build()
}

/**
 * 工具 JSON 序列化缓存 — 基于 LLinkedHashMap 的 LRU 驱逐策略
 * 使用内容哈希（而非 System.identityHashCode）作为 key，避免 GC 后 key 失效
 */
private val toolsJsonCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
        size > 64 // 最多保留 64 条缓存
}

private fun List<ModelToolDefinition>.cacheKey(): String {
    var hash = 1
    for (t in this) {
        hash = 31 * hash + t.name.hashCode()
        hash = 31 * hash + t.description.hashCode()
        hash = 31 * hash + t.parameters.hashCode()
    }
    return hash.toString()
}

private fun List<ModelToolDefinition>.toToolsJsonArray(): String {
    val key = cacheKey()
    return toolsJsonCache.getOrPut(key) {
        val sb = sharedSbPool.acquire()
        try {
            sb.append('[')
            forEachIndexed { i, t ->
                if (i > 0) sb.append(',')
                sb.append("{\"type\":\"function\",\"function\":{")
                sb.append("\"name\":")
                jsonEscape(t.name, sb)
                sb.append(",\"description\":")
                jsonEscape(t.description, sb)
                sb.append(",\"parameters\":").append(t.parameters.toJsonElement().toString())
                sb.append("}}")
            }
            sb.append(']')
            sb.toString()
        } finally {
            sharedSbPool.release(sb)
        }
    }
}

private fun jsonEscape(s: String, sb: StringBuilder): StringBuilder {
    sb.append('"')
    var needsEscape = false
    for (ch in s) {
        if (ch == '"' || ch == '\\' || ch == '\n' || ch == '\r' || ch == '\t') {
            needsEscape = true
            break
        }
    }
    if (!needsEscape) {
        sb.append(s)
    } else {
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
    }
    sb.append('"')
    return sb
}

private fun jsonEscape(s: String): String {
    if (s.all { it != '"' && it != '\\' && it != '\n' && it != '\r' && it != '\t' }) {
        return "\"$s\""
    }
    val sb = sharedSbPool.acquire()
    try {
        jsonEscape(s, sb)
        return sb.toString()
    } finally {
        sharedSbPool.release(sb)
    }
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject {
        @Suppress("UNCHECKED_CAST")
        (this@toJsonElement as Map<String, Any?>).forEach { (k, v) -> put(k, v.toJsonElement()) }
    }
    is List<*> -> buildJsonArray {
        (this@toJsonElement as List<Any?>).forEach { add(it.toJsonElement()) }
    }
    else -> JsonPrimitive(toString())
}

private fun ModelMessage.toJsonString(): String {
    val sb = sharedSbPool.acquire()
    try {
        sb.append("{\"role\":")
        jsonEscape(role, sb)
        val blocks = contentBlocks
        if (blocks != null && blocks.isNotEmpty()) {
            sb.append(",\"content\":[")
            blocks.forEachIndexed { i, block ->
                if (i > 0) sb.append(',')
                when (block) {
                    is ContentBlock.Text -> {
                        sb.append("{\"type\":\"text\",\"text\":")
                        jsonEscape(block.text, sb)
                        sb.append('}')
                    }
                    is ContentBlock.ImageUrl -> {
                        sb.append("{\"type\":\"image_url\",\"image_url\":{\"url\":")
                        jsonEscape(block.imageUrl.url, sb)
                        block.imageUrl.detail?.let { sb.append(",\"detail\":"); jsonEscape(it, sb) }
                        sb.append("}}")
                    }
                }
            }
            sb.append(']')
        } else {
            sb.append(",\"content\":")
            jsonEscape(content, sb)
        }
        name?.let { sb.append(",\"name\":"); jsonEscape(it, sb) }
        if (role == "tool") {
            sb.append(",\"tool_call_id\":")
            jsonEscape(toolCallId ?: "", sb)
        }
        toolCallId?.let { sb.append(",\"tool_call_id\":"); jsonEscape(it, sb) }
        toolCalls?.let { tcs ->
            sb.append(",\"tool_calls\":[")
            tcs.forEachIndexed { i, tc ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":")
                jsonEscape(tc.id, sb)
                sb.append(",\"type\":")
                jsonEscape(tc.type, sb)
                sb.append(",\"function\":{\"name\":")
                jsonEscape(tc.function.name, sb)
                sb.append(",\"arguments\":")
                jsonEscape(tc.function.arguments, sb)
                sb.append("}}")
            }
            sb.append(']')
        }
        sb.append('}')
        return sb.toString()
    } finally {
        sharedSbPool.release(sb)
    }
}

private fun writeJsonEscape(s: String, sink: okio.BufferedSink) {
    sink.writeUtf8("\"")
    var needsEscape = false
    for (ch in s) {
        if (ch == '"' || ch == '\\' || ch == '\n' || ch == '\r' || ch == '\t') {
            needsEscape = true
            break
        }
    }
    if (!needsEscape) {
        sink.writeUtf8(s)
    } else {
        for (ch in s) {
            when (ch) {
                '"' -> sink.writeUtf8("\\\"")
                '\\' -> sink.writeUtf8("\\\\")
                '\n' -> sink.writeUtf8("\\n")
                '\r' -> sink.writeUtf8("\\r")
                '\t' -> sink.writeUtf8("\\t")
                else -> sink.writeUtf8(ch.toString())
            }
        }
    }
    sink.writeUtf8("\"")
}

private fun ModelMessage.writeToSink(sink: okio.BufferedSink) {
    sink.writeUtf8("{\"role\":")
    writeJsonEscape(role, sink)
    val blocks = contentBlocks
    if (blocks != null && blocks.isNotEmpty()) {
        sink.writeUtf8(",\"content\":[")
        blocks.forEachIndexed { i, block ->
            if (i > 0) sink.writeUtf8(",")
            when (block) {
                is ContentBlock.Text -> {
                    sink.writeUtf8("{\"type\":\"text\",\"text\":")
                    writeJsonEscape(block.text, sink)
                    sink.writeUtf8("}")
                }
                is ContentBlock.ImageUrl -> {
                    sink.writeUtf8("{\"type\":\"image_url\",\"image_url\":{\"url\":")
                    writeJsonEscape(block.imageUrl.url, sink)
                    block.imageUrl.detail?.let { sink.writeUtf8(",\"detail\":"); writeJsonEscape(it, sink) }
                    sink.writeUtf8("}}")
                }
            }
        }
        sink.writeUtf8("]")
    } else {
        sink.writeUtf8(",\"content\":")
        writeJsonEscape(content, sink)
    }
    name?.let { sink.writeUtf8(",\"name\":"); writeJsonEscape(it, sink) }
    if (role == "tool") {
        sink.writeUtf8(",\"tool_call_id\":")
        writeJsonEscape(toolCallId ?: "", sink)
    }
    toolCallId?.let { sink.writeUtf8(",\"tool_call_id\":"); writeJsonEscape(it, sink) }
    toolCalls?.let { tcs ->
        sink.writeUtf8(",\"tool_calls\":[")
        tcs.forEachIndexed { i, tc ->
            if (i > 0) sink.writeUtf8(",")
            sink.writeUtf8("{\"id\":")
            writeJsonEscape(tc.id, sink)
            sink.writeUtf8(",\"type\":")
            writeJsonEscape(tc.type, sink)
            sink.writeUtf8(",\"function\":{\"name\":")
            writeJsonEscape(tc.function.name, sink)
            sink.writeUtf8(",\"arguments\":")
            writeJsonEscape(tc.function.arguments, sink)
            sink.writeUtf8("}}")
        }
        sink.writeUtf8("]")
    }
    sink.writeUtf8("}")
}

private fun List<ModelToolDefinition>.writeToolsJsonToSink(sink: okio.BufferedSink) {
    sink.writeUtf8("[")
    forEachIndexed { i, t ->
        if (i > 0) sink.writeUtf8(",")
        sink.writeUtf8("{\"type\":\"function\",\"function\":{")
        sink.writeUtf8("\"name\":")
        writeJsonEscape(t.name, sink)
        sink.writeUtf8(",\"description\":")
        writeJsonEscape(t.description, sink)
        sink.writeUtf8(",\"parameters\":")
        val paramStr = t.parameters.toJsonElement().toString()
        sink.writeUtf8(paramStr)
        sink.writeUtf8("}}")
    }
    sink.writeUtf8("]")
}

private fun ModelCallRequest.toOpenAIJsonRequestBody(): RequestBody {
    return object : RequestBody() {
        override fun contentType(): okhttp3.MediaType = JSON_MEDIA_TYPE

        override fun writeTo(sink: okio.BufferedSink) {
            sink.writeUtf8("{\"model\":\"")
            sink.writeUtf8(model)
            sink.writeUtf8("\",\"messages\":[")
            messages.forEachIndexed { i, msg ->
                if (i > 0) sink.writeUtf8(",")
                msg.writeToSink(sink)
            }
            sink.writeUtf8("]")
            temperature?.let { sink.writeUtf8(",\"temperature\":$it") }
            maxTokens?.let { sink.writeUtf8(",\"max_tokens\":$it") }
            topP?.let { sink.writeUtf8(",\"top_p\":$it") }
            sink.writeUtf8(",\"stream\":$stream")
            tools?.let { ts ->
                sink.writeUtf8(",\"tools\":")
                ts.writeToolsJsonToSink(sink)
            }
            sink.writeUtf8("}")
        }
    }
}

private fun ModelCallRequest.toOllamaJsonRequestBody(): RequestBody {
    return object : RequestBody() {
        override fun contentType(): okhttp3.MediaType = JSON_MEDIA_TYPE
        override fun writeTo(sink: okio.BufferedSink) {
            sink.writeUtf8("{\"model\":\""); sink.writeUtf8(model); sink.writeUtf8("\"")
            sink.writeUtf8(",\"messages\":[")
            messages.forEachIndexed { i, msg ->
                if (i > 0) sink.writeUtf8(",")
                msg.writeToSink(sink)
            }
            sink.writeUtf8("]")
            temperature?.let { sink.writeUtf8(",\"temperature\":$it") }
            topP?.let { sink.writeUtf8(",\"top_p\":$it") }
            sink.writeUtf8(",\"stream\":$stream")
            tools?.let { sink.writeUtf8(",\"tools\":"); it.writeToolsJsonToSink(sink) }
            sink.writeUtf8("}")
        }
    }
}

private fun JsonObject.toModelMessage() = ModelMessage(
    role = get("role")?.safeJsonPrimitiveContent() ?: "",
    content = get("content")?.safeJsonPrimitiveContent() ?: "",
    name = get("name")?.safeJsonPrimitiveContent(),
    toolCallId = get("tool_call_id")?.safeJsonPrimitiveContent(),
    toolCalls = get("tool_calls")?.safeJsonArray()?.mapNotNull { el ->
        val tc = el.safeJsonObject() ?: return@mapNotNull null
        val fn = tc["function"]?.safeJsonObject() ?: return@mapNotNull null
        ToolCallInfo(
            id = tc["id"]?.safeJsonPrimitiveContent() ?: "unknown",
            type = tc["type"]?.safeJsonPrimitiveContent() ?: "function",
            function = FunctionInfo(
                fn["name"]?.safeJsonPrimitiveContent() ?: "",
                fn["arguments"]?.safeJsonPrimitiveContent() ?: "{}"
            ),
            index = tc["index"]?.safeJsonPrimitive()?.int ?: -1
        )
    },
    reasoningContent = get("reasoning_content")?.safeJsonPrimitiveContent()
)

private fun JsonObject.toModelCallResponse() = ModelCallResponse(
    id = get("id")?.safeJsonPrimitiveContent() ?: "",
    choices = get("choices")?.safeJsonArray()?.mapNotNull { el ->
        val obj = el.safeJsonObject() ?: return@mapNotNull null
        val msg = obj["message"]?.safeJsonObject()?.toModelMessage() ?: return@mapNotNull null
        ModelChoice(
            index = obj["index"]?.safeJsonPrimitive()?.int ?: 0,
            message = msg,
            finishReason = obj["finish_reason"]?.safeJsonPrimitiveContent()
        )
    } ?: emptyList(),
    usage = get("usage")?.safeJsonObject()?.let { u ->
        val details = u["prompt_tokens_details"]?.safeJsonObject()
        ModelUsage(
            promptTokens = u["prompt_tokens"]?.safeJsonPrimitive()?.int ?: 0,
            completionTokens = u["completion_tokens"]?.safeJsonPrimitive()?.int ?: 0,
            totalTokens = u["total_tokens"]?.safeJsonPrimitive()?.int ?: 0,
            cacheReadTokens = details?.get("cached_tokens")?.safeJsonPrimitive()?.int ?: 0
        )
    }
)

private fun JsonObject.toStreamChunk() = ModelStreamChunk(
    id = get("id")?.safeJsonPrimitiveContent() ?: "",
    choices = get("choices")?.safeJsonArray()?.map { el ->
        val obj = el.safeJsonObject() ?: return@map ModelStreamChoice(
            index = 0, delta = ModelMessage("", ""), finishReason = null
        )
        ModelStreamChoice(
            index = obj["index"]?.safeJsonPrimitive()?.int ?: 0,
            delta = obj["delta"]?.safeJsonObject()?.toModelMessage() ?: ModelMessage("", ""),
            finishReason = obj["finish_reason"]?.safeJsonPrimitiveContent()
        )
    } ?: emptyList()
)

class OpenAIModelClient(
    private val baseUrl: String,
    private val apiKey: String
) : ModelClient {
    private val client = sharedOkHttpClient
    private val sseFactory = EventSources.createFactory(client)

    private fun Request.Builder.addAuth() = apply {
        val cleanKey = apiKey.filter { it.code <= 0x7F }.trim()
        if (cleanKey.isNotBlank()) {
            addHeader("Authorization", "Bearer $cleanKey")
        }
    }

    override suspend fun chatCompletion(request: ModelCallRequest): ModelCallResponse =
        suspendCancellableCoroutine { cont ->
            val url = "$baseUrl/chat/completions"
            Timber.d("OpenAI chatCompletion URL=$url, model=${request.model}, apiKey=***${apiKey.takeLast(4)}, messages=${request.messages.size}, toolMessages=${request.messages.count { it.role == "tool" }}")
            if (request.messages.any { it.role == "tool" }) {
                request.messages.filter { it.role == "tool" }.forEach { tm ->
                    Timber.d("  tool msg: toolCallId=${tm.toolCallId}, content=${tm.content.take(30)}")
                }
            }
            val req = Request.Builder()
                .url(url)
                .addAuth()
                .post(request.copy(stream = false).toOpenAIJsonRequestBody())
                .build()
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Timber.e(e, "OpenAI chatCompletion failed")
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Timber.e("OpenAI chatCompletion HTTP ${response.code}: $body")
                        cont.resumeWithException(IOException("HTTP ${response.code}: $body"))
                        return
                    }
                    try {
                        cont.resume(Json.parseToJsonElement(body).jsonObject.toModelCallResponse())
                    } catch (e: Exception) {
                        Timber.e(e, "OpenAI chatCompletion parse error: $body")
                        cont.resumeWithException(e)
                    }
                }
            })
        }

    override suspend fun chatCompletionStream(request: ModelCallRequest): Flow<ModelStreamChunk> {
        val channel = Channel<ModelStreamChunk>(Channel.BUFFERED)
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addAuth()
            .post(request.copy(stream = true).toOpenAIJsonRequestBody())
            .build()
        val eventSource = sseFactory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: okhttp3.sse.EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    channel.close()
                    return
                }
                try {
                    channel.trySend(Json.parseToJsonElement(data).jsonObject.toStreamChunk())
                } catch (e: Exception) {
                    Timber.w(e, "SSE parse error: ${data.take(100)}")
                }
            }
            override fun onClosed(eventSource: okhttp3.sse.EventSource) {
                channel.close()
            }
            override fun onFailure(eventSource: okhttp3.sse.EventSource, t: Throwable?, response: Response?) {
                val error = when {
                    response != null && !response.isSuccessful -> {
                        val bodySnippet = runCatching { response.body?.string()?.take(200) }.getOrNull() ?: ""
                        IOException("HTTP ${response.code}: $bodySnippet")
                    }
                    t != null -> t
                    else -> IOException("SSE failed")
                }
                channel.close(error)
            }
        })
        return channel.consumeAsFlow().onCompletion { eventSource.cancel() }
    }

    override suspend fun testConnection(): Result<Unit> = suspendCancellableCoroutine { cont ->
        val url = "$baseUrl/models"
        Timber.d("OpenAI testConnection URL=$url, apiKey=***${apiKey.takeLast(4)}")
        val req = Request.Builder()
            .url(url)
            .addAuth()
            .get()
            .build()
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Timber.e(e, "OpenAI testConnection failed")
                cont.resume(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Timber.d("OpenAI testConnection success")
                    cont.resume(Result.success(Unit))
                } else {
                    Timber.e("OpenAI testConnection HTTP ${response.code}: $body")
                    cont.resume(Result.failure(IOException("HTTP ${response.code}: $body")))
                }
            }
        })
    }

    override suspend fun listModels(): List<String> = suspendCancellableCoroutine { cont ->
        val req = Request.Builder()
            .url("$baseUrl/models")
            .addAuth()
            .get()
            .build()
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = try { response.body?.string()?.take(200) } catch (_: Exception) { null }
                    cont.resumeWithException(IOException("获取模型列表失败 (HTTP ${response.code}): ${errorBody ?: response.message}"))
                    return
                }
                try {
                    val body = response.body?.string() ?: ""
                    val json = Json.parseToJsonElement(body).jsonObject
                    val data = json["data"]?.safeJsonArray()
                    val models = data?.mapNotNull { element ->
                        element.safeJsonObject()?.get("id")?.safeJsonPrimitiveContent()
                    } ?: emptyList()
                    cont.resume(models)
                } catch (e: Exception) {
                    cont.resumeWithException(IOException("解析模型列表失败: ${e.message}"))
                }
            }
        })
    }
}

class OllamaModelClient(
    private val baseUrl: String = "http://localhost:11434"
) : ModelClient {
    private val client = sharedOkHttpClient

    override suspend fun chatCompletion(request: ModelCallRequest): ModelCallResponse =
        suspendCancellableCoroutine { cont ->
            val req = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(request.copy(stream = false).toOllamaJsonRequestBody())
                .build()
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                        ?: return cont.resumeWithException(IOException("Empty response"))
                    if (!response.isSuccessful) return cont.resumeWithException(
                        IOException("HTTP ${response.code}: $body")
                    )
                    try {
                        val obj = Json.parseToJsonElement(body).jsonObject
                        val msg = obj["message"]?.safeJsonObject()

                        // Parse tool calls from Ollama response
                        val toolCalls = msg?.get("tool_calls")?.safeJsonArray()?.mapNotNull { el ->
                            val tc = el.safeJsonObject() ?: return@mapNotNull null
                            val fn = tc["function"]?.safeJsonObject() ?: return@mapNotNull null
                            ToolCallInfo(
                                id = "ollama_${System.currentTimeMillis()}",
                                type = "function",
                                function = FunctionInfo(
                                    name = fn["name"]?.safeJsonPrimitiveContent() ?: "",
                                    arguments = fn["arguments"]?.toString() ?: "{}"
                                )
                            )
                        }

                        val message = msg?.let {
                            ModelMessage(
                                role = it["role"]?.safeJsonPrimitiveContent() ?: "assistant",
                                content = it["content"]?.safeJsonPrimitiveContent() ?: "",
                                toolCalls = toolCalls?.ifEmpty { null }
                            )
                        } ?: ModelMessage("assistant", "")

                        cont.resume(ModelCallResponse(
                            id = obj["model"]?.jsonPrimitive?.contentOrNull ?: "",
                            choices = listOf(ModelChoice(
                                index = 0,
                                message = message,
                                finishReason = if (toolCalls.isNullOrEmpty()) "stop" else "tool_calls"
                            ))
                        ))
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
            })
        }

    override suspend fun chatCompletionStream(request: ModelCallRequest): Flow<ModelStreamChunk> {
        val channel = Channel<ModelStreamChunk>(Channel.BUFFERED)
        val req = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(request.copy(stream = true).toOllamaJsonRequestBody())
            .build()
        val call = client.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                channel.close(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.body?.byteStream()?.bufferedReader()?.use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            if (line.isNotBlank()) {
                                val obj = Json.parseToJsonElement(line).jsonObject
                                val msg = obj["message"]?.jsonObject
                                val done = obj["done"]?.jsonPrimitive?.booleanOrNull ?: false
                                channel.trySend(ModelStreamChunk(
                                    id = obj["model"]?.jsonPrimitive?.contentOrNull ?: "",
                                    choices = listOf(ModelStreamChoice(
                                        index = 0,
                                        delta = msg?.toModelMessage() ?: ModelMessage("assistant", ""),
                                        finishReason = if (done) "stop" else null
                                    ))
                                ))
                                if (done) break
                            }
                            line = reader.readLine()
                        }
                    }
                    channel.close()
                } catch (e: Exception) {
                    channel.close(e)
                }
            }
        })
        return channel.consumeAsFlow().onCompletion { call.cancel() }
    }

    override suspend fun testConnection(): Result<Unit> = suspendCancellableCoroutine { cont ->
        val req = Request.Builder()
            .url("$baseUrl/api/tags")
            .get()
            .build()
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resume(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) cont.resume(Result.success(Unit))
                else cont.resume(Result.failure(IOException("HTTP ${response.code}")))
            }
        })
    }

    override suspend fun listModels(): List<String> = suspendCancellableCoroutine { cont ->
        val req = Request.Builder()
            .url("$baseUrl/api/tags")
            .get()
            .build()
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = try { response.body?.string()?.take(200) } catch (_: Exception) { null }
                    cont.resumeWithException(IOException("获取模型列表失败 (HTTP ${response.code}): ${errorBody ?: response.message}"))
                    return
                }
                try {
                    val body = response.body?.string() ?: ""
                    val json = Json.parseToJsonElement(body).jsonObject
                    val models = json["models"]?.safeJsonArray()?.mapNotNull { element ->
                        element.safeJsonObject()?.get("name")?.safeJsonPrimitiveContent()
                    } ?: emptyList()
                    cont.resume(models)
                } catch (e: Exception) {
                    cont.resumeWithException(IOException("解析模型列表失败: ${e.message}"))
                }
            }
        })
    }
}

class AnthropicModelClient(
    private val baseUrl: String = "https://api.anthropic.com",
    private val apiKey: String
) : ModelClient {
    private val client = sharedOkHttpClient
    private val sseFactory = EventSources.createFactory(client)

    private fun Request.Builder.addAnthropicHeaders() = apply {
        val cleanKey = apiKey.filter { it.code <= 0x7F }.trim()
        if (cleanKey.isNotBlank()) addHeader("x-api-key", cleanKey)
        addHeader("anthropic-version", "2023-06-01")
        addHeader("content-type", "application/json")
    }

    private fun ModelCallRequest.toAnthropicJsonRequestBody(): RequestBody {
        return object : RequestBody() {
            override fun contentType(): okhttp3.MediaType = JSON_MEDIA_TYPE
            override fun writeTo(sink: okio.BufferedSink) {
                val systemMsg = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
                val chatMessages = messages.filter { it.role != "system" }
                sink.writeUtf8("{\"model\":\""); sink.writeUtf8(model); sink.writeUtf8("\"")
                if (systemMsg.isNotEmpty()) { sink.writeUtf8(",\"system\":"); writeJsonEscape(systemMsg, sink) }
                sink.writeUtf8(",\"messages\":[")
                chatMessages.forEachIndexed { i, msg ->
                    if (i > 0) sink.writeUtf8(",")
                    sink.writeUtf8("{\"role\":"); writeJsonEscape(msg.role, sink)
                    sink.writeUtf8(",\"content\":"); writeJsonEscape(msg.content, sink)
                    sink.writeUtf8("}")
                }
                sink.writeUtf8("]")
                maxTokens?.let { sink.writeUtf8(",\"max_tokens\":$it") } ?: sink.writeUtf8(",\"max_tokens\":4096")
                temperature?.let { sink.writeUtf8(",\"temperature\":$it") }
                topP?.let { sink.writeUtf8(",\"top_p\":$it") }
                sink.writeUtf8(",\"stream\":$stream")
                tools?.let { ts ->
                    sink.writeUtf8(",\"tools\":[")
                    ts.forEachIndexed { i, t ->
                        if (i > 0) sink.writeUtf8(",")
                        sink.writeUtf8("{\"name\":"); writeJsonEscape(t.name, sink)
                        sink.writeUtf8(",\"description\":"); writeJsonEscape(t.description, sink)
                        sink.writeUtf8(",\"input_schema\":"); sink.writeUtf8(t.parameters.toJsonElement().toString())
                        sink.writeUtf8("}")
                    }
                    sink.writeUtf8("]")
                }
                sink.writeUtf8("}")
            }
        }
    }

    override suspend fun chatCompletion(request: ModelCallRequest): ModelCallResponse =
        suspendCancellableCoroutine { cont ->
            val url = "$baseUrl/v1/messages"
            Timber.d("Anthropic chatCompletion URL=$url, model=${request.model}, apiKey=***${apiKey.takeLast(4)}, maxTokens=${request.maxTokens}")
            val req = Request.Builder()
                .url(url)
                .addAnthropicHeaders()
                .post(request.copy(stream = false).toAnthropicJsonRequestBody())
                .build()
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Timber.e(e, "Anthropic chatCompletion failed")
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Timber.e("Anthropic chatCompletion HTTP ${response.code}: $body")
                        cont.resumeWithException(IOException("HTTP ${response.code}: $body"))
                        return
                    }
                    try {
                        val obj = Json.parseToJsonElement(body).jsonObject
                        val contentArray = obj["content"]?.safeJsonArray()
                        val content = contentArray?.firstOrNull()?.safeJsonObject()
                        val text = content?.get("text")?.safeJsonPrimitiveContent() ?: ""
                        val toolUse = content?.takeIf { it["type"]?.safeJsonPrimitiveContent() == "tool_use" }

                        val toolCalls = toolUse?.let { tu ->
                            listOf(ToolCallInfo(
                                id = tu["id"]?.safeJsonPrimitiveContent() ?: "anthropic_${System.currentTimeMillis()}",
                                type = "function",
                                function = FunctionInfo(
                                    name = tu["name"]?.safeJsonPrimitiveContent() ?: "",
                                    arguments = tu["input"]?.toString() ?: "{}"
                                )
                            ))
                        }

                        val message = ModelMessage(
                            role = "assistant",
                            content = if (toolUse != null) "" else text,
                            toolCalls = toolCalls
                        )

                        cont.resume(ModelCallResponse(
                            id = obj["id"]?.safeJsonPrimitiveContent() ?: "",
                            choices = listOf(ModelChoice(
                                index = 0,
                                message = message,
                                finishReason = obj["stop_reason"]?.safeJsonPrimitiveContent()
                            )),
                            usage = obj["usage"]?.safeJsonObject()?.let { u ->
                                ModelUsage(
                                    promptTokens = u["input_tokens"]?.safeJsonPrimitive()?.int ?: 0,
                                    completionTokens = u["output_tokens"]?.safeJsonPrimitive()?.int ?: 0,
                                    totalTokens = (u["input_tokens"]?.safeJsonPrimitive()?.int ?: 0) +
                                            (u["output_tokens"]?.safeJsonPrimitive()?.int ?: 0),
                                    cacheReadTokens = u["cache_read_input_tokens"]?.safeJsonPrimitive()?.int ?: 0,
                                    cacheWriteTokens = u["cache_creation_input_tokens"]?.safeJsonPrimitive()?.int ?: 0
                                )
                            }
                        ))
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
            })
        }

    override suspend fun chatCompletionStream(request: ModelCallRequest): Flow<ModelStreamChunk> {
        val channel = Channel<ModelStreamChunk>(Channel.BUFFERED)
        val req = Request.Builder()
            .url("$baseUrl/v1/messages")
            .addAnthropicHeaders()
            .post(request.copy(stream = true).toAnthropicJsonRequestBody())
            .build()
        val eventSource = sseFactory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: okhttp3.sse.EventSource, id: String?, type: String?, data: String) {
                try {
                    val obj = Json.parseToJsonElement(data).jsonObject
                    val eventType = obj["type"]?.safeJsonPrimitiveContent() ?: return

                    when (eventType) {
                        "content_block_delta" -> {
                            val delta = obj["delta"]?.safeJsonObject()
                            val text = delta?.get("text")?.safeJsonPrimitiveContent() ?: ""
                            if (text.isNotEmpty()) {
                                channel.trySend(ModelStreamChunk(
                                    id = obj["index"]?.safeJsonPrimitiveContent() ?: "",
                                    choices = listOf(ModelStreamChoice(
                                        index = 0,
                                        delta = ModelMessage("assistant", text),
                                        finishReason = null
                                    ))
                                ))
                            }
                        }
                        "message_stop" -> {
                            channel.trySend(ModelStreamChunk(
                                id = "",
                                choices = listOf(ModelStreamChoice(
                                    index = 0,
                                    delta = ModelMessage("assistant", ""),
                                    finishReason = "stop"
                                ))
                            ))
                            channel.close()
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Anthropic SSE parse error: ${data.take(100)}")
                }
            }
            override fun onClosed(eventSource: okhttp3.sse.EventSource) { channel.close() }
            override fun onFailure(eventSource: okhttp3.sse.EventSource, t: Throwable?, response: Response?) {
                val error = when {
                    response != null && !response.isSuccessful -> {
                        val bodySnippet = runCatching { response.body?.string()?.take(200) }.getOrNull() ?: ""
                        IOException("HTTP ${response.code}: $bodySnippet")
                    }
                    t != null -> t
                    else -> IOException("SSE failed")
                }
                channel.close(error)
            }
        })
        return channel.consumeAsFlow().onCompletion { eventSource.cancel() }
    }

    override suspend fun testConnection(): Result<Unit> = suspendCancellableCoroutine { cont ->
        // Anthropic 没有 models 列表接口，用一个简单的 messages 请求测试
        val testBody = buildJsonObject {
            put("model", "claude-3-haiku-20240307")
            put("max_tokens", 1)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "hi")
                })
            })
        }.toString()
        val req = Request.Builder()
            .url("$baseUrl/v1/messages")
            .addAnthropicHeaders()
            .post(testBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resume(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.close()
                if (response.isSuccessful || response.code == 400) {
                    // 400 也说明 API Key 有效（只是模型名可能不对）
                    cont.resume(Result.success(Unit))
                } else {
                    cont.resume(Result.failure(IOException("HTTP ${response.code}")))
                }
            }
        })
    }

    override suspend fun listModels(): List<String> = suspendCancellableCoroutine { cont ->
        // Anthropic 没有公开的模型列表 API，返回常用模型
        cont.resume(listOf(
            "claude-sonnet-4-20250514",
            "claude-3-5-haiku-20241022",
            "claude-3-5-sonnet-20241022",
            "claude-3-opus-20240229",
            "claude-3-haiku-20240307"
        ))
    }
}

