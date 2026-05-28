package com.lin.hippyagent.core.model

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LM Studio model client. LM Studio exposes an OpenAI-compatible API
 * (default port 1234), so this client is a thin wrapper with LM Studio
 * specific defaults and model listing.
 */
class LMStudioModelClient(
    private val baseUrl: String = "http://localhost:1234/v1"
) : ModelClient {
    private val client = com.lin.hippyagent.core.model.sharedOkHttpClient
    private val sseFactory = EventSources.createFactory(client)
    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override suspend fun chatCompletion(request: ModelCallRequest): ModelCallResponse =
        suspendCancellableCoroutine { cont ->
            val body = buildJsonObject {
                put("model", request.model)
                put("messages", buildJsonArray {
                    request.messages.forEach { msg ->
                        add(buildJsonObject {
                            put("role", msg.role)
                            put("content", msg.content)
                            msg.name?.let { put("name", it) }
                            msg.toolCallId?.let { put("tool_call_id", it) }
                            msg.toolCalls?.let { tcs ->
                                put("tool_calls", buildJsonArray {
                                    tcs.forEach { tc ->
                                        add(buildJsonObject {
                                            put("id", tc.id)
                                            put("type", tc.type)
                                            put("function", buildJsonObject {
                                                put("name", tc.function.name)
                                                put("arguments", tc.function.arguments)
                                            })
                                        })
                                    }
                                })
                            }
                        })
                    }
                })
                request.temperature?.let { put("temperature", it) }
                request.maxTokens?.let { put("max_tokens", it) }
                request.topP?.let { put("top_p", it) }
                put("stream", false)
                request.tools?.let { ts ->
                    put("tools", buildJsonArray {
                        ts.forEach { t ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", t.name)
                                    put("description", t.description)
                                    put("parameters", t.parameters.toJsonElement())
                                })
                            })
                        }
                    })
                }
            }.toString()

            val req = Request.Builder()
                .url("$baseUrl/chat/completions")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                        ?: return cont.resumeWithException(IOException("Empty response"))
                    if (!response.isSuccessful) return cont.resumeWithException(
                        IOException("HTTP ${response.code}: $responseBody")
                    )
                    try {
                        val obj = json.parseToJsonElement(responseBody).jsonObject
                        val choices = obj["choices"]?.safeJsonArray() ?: emptyList()
                        val firstChoice = choices.firstOrNull()?.safeJsonObject()
                        val message = firstChoice?.get("message")?.safeJsonObject()

                        val toolCalls = message?.get("tool_calls")?.safeJsonArray()?.mapNotNull { el ->
                            val tc = el.safeJsonObject() ?: return@mapNotNull null
                            val fn = tc["function"]?.safeJsonObject() ?: return@mapNotNull null
                            ToolCallInfo(
                                id = tc["id"]?.safeJsonPrimitiveContent() ?: "lmstudio_${System.currentTimeMillis()}",
                                type = "function",
                                function = FunctionInfo(
                                    name = fn["name"]?.safeJsonPrimitiveContent() ?: "",
                                    arguments = fn["arguments"]?.toString() ?: "{}"
                                )
                            )
                        }

                        cont.resume(ModelCallResponse(
                            id = obj["id"]?.safeJsonPrimitiveContent() ?: "",
                            choices = listOf(ModelChoice(
                                index = 0,
                                message = ModelMessage(
                                    role = message?.get("role")?.safeJsonPrimitiveContent() ?: "assistant",
                                    content = message?.get("content")?.safeJsonPrimitiveContent() ?: "",
                                    toolCalls = toolCalls?.ifEmpty { null }
                                ),
                                finishReason = firstChoice?.get("finish_reason")?.safeJsonPrimitiveContent()
                            )),
                            usage = obj["usage"]?.safeJsonObject()?.let { u ->
                                val details = u["prompt_tokens_details"]?.safeJsonObject()
                                ModelUsage(
                                    promptTokens = u["prompt_tokens"]?.safeJsonPrimitive()?.int ?: 0,
                                    completionTokens = u["completion_tokens"]?.safeJsonPrimitive()?.int ?: 0,
                                    totalTokens = u["total_tokens"]?.safeJsonPrimitive()?.int ?: 0,
                                    cacheReadTokens = details?.get("cached_tokens")?.safeJsonPrimitive()?.int ?: 0
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
        val body = buildJsonObject {
            put("model", request.model)
            put("messages", buildJsonArray {
                request.messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            })
            request.temperature?.let { put("temperature", it) }
            put("stream", true)
        }.toString()

        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val eventSource = sseFactory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: okhttp3.sse.EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    channel.close()
                    return
                }
                try {
                    val obj = json.parseToJsonElement(data).jsonObject
                    val choices = obj["choices"]?.safeJsonArray() ?: emptyList()
                    val firstChoice = choices.firstOrNull()?.safeJsonObject()
                    val delta = firstChoice?.get("delta")?.safeJsonObject()

                    channel.trySend(ModelStreamChunk(
                        id = obj["id"]?.safeJsonPrimitiveContent() ?: "",
                        choices = listOf(ModelStreamChoice(
                            index = 0,
                            delta = ModelMessage(
                                role = delta?.get("role")?.safeJsonPrimitiveContent() ?: "",
                                content = delta?.get("content")?.safeJsonPrimitiveContent() ?: ""
                            ),
                            finishReason = firstChoice?.get("finish_reason")?.safeJsonPrimitiveContent()
                        ))
                    ))
                } catch (_: Exception) {}
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
        val req = Request.Builder()
            .url("$baseUrl/models")
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
            .url("$baseUrl/models")
            .get()
            .build()
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resume(emptyList())
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) { cont.resume(emptyList()); return }
                try {
                    val body = response.body?.string() ?: ""
                    val obj = json.parseToJsonElement(body).jsonObject
                    val models = obj["data"]?.safeJsonArray()?.mapNotNull { el ->
                        el.safeJsonObject()?.get("id")?.safeJsonPrimitiveContent()
                    } ?: emptyList()
                    cont.resume(models)
                } catch (e: Exception) {
                    cont.resume(emptyList())
                }
            }
        })
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
}

