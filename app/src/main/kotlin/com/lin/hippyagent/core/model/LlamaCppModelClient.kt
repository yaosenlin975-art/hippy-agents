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
import timber.log.Timber
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * llama.cpp 模型客户端
 * llama.cpp 提供 OpenAI 兼容的 API（默认端口 8080）
 */
class LlamaCppModelClient(
    private val baseUrl: String = "http://localhost:8080/v1"
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
                                    put("parameters", t.parameters.toJsonObject())
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

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: "Unknown error"
                            if (cont.isActive) cont.resumeWithException(
                                RuntimeException("HTTP ${response.code}: $errorBody")
                            )
                            return
                        }

                        val responseBody = response.body?.string() ?: "{}"
                        val jsonResp = json.parseToJsonElement(responseBody).jsonObject

                        val choices = jsonResp["choices"]?.jsonArray ?: emptyList()
                        val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject

                        val content = message?.get("content")?.jsonPrimitive?.content ?: ""
                        val toolCalls = message?.get("tool_calls")?.jsonArray?.map { tcJson ->
                            val tc = tcJson.jsonObject
                            val func = tc["function"]?.jsonObject
                            ToolCallInfo(
                                id = tc["id"]?.jsonPrimitive?.content ?: "",
                                type = tc["type"]?.jsonPrimitive?.content ?: "function",
                                function = FunctionInfo(
                                    name = func?.get("name")?.jsonPrimitive?.content ?: "",
                                    arguments = func?.get("arguments")?.jsonPrimitive?.content ?: ""
                                )
                            )
                        }

                        val usage = jsonResp["usage"]?.jsonObject
                        val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0
                        val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.int ?: 0

                        if (cont.isActive) cont.resume(
                            ModelCallResponse(
                                id = jsonResp["id"]?.jsonPrimitive?.content ?: "",
                                choices = listOf(
                                    ModelChoice(
                                        index = 0,
                                        message = ModelMessage(
                                            role = "assistant",
                                            content = content,
                                            toolCalls = toolCalls
                                        ),
                                        finishReason = choices.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.content
                                    )
                                ),
                                usage = ModelUsage(
                                    promptTokens = promptTokens,
                                    completionTokens = completionTokens,
                                    totalTokens = promptTokens + completionTokens
                                )
                            )
                        )
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
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
            put("stream", true)
            request.tools?.let { ts ->
                put("tools", buildJsonArray {
                    ts.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.parameters.toJsonObject())
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

        val eventSource = sseFactory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: okhttp3.sse.EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    channel.close()
                    return
                }

                try {
                    val obj = json.parseToJsonElement(data).jsonObject
                    val choices = obj["choices"]?.jsonArray ?: emptyList()
                    val firstChoice = choices.firstOrNull()?.jsonObject
                    val delta = firstChoice?.get("delta")?.jsonObject

                    channel.trySend(ModelStreamChunk(
                        id = obj["id"]?.jsonPrimitive?.content ?: "",
                        choices = listOf(ModelStreamChoice(
                            index = 0,
                            delta = ModelMessage(
                                role = delta?.get("role")?.jsonPrimitive?.content ?: "",
                                content = delta?.get("content")?.jsonPrimitive?.content ?: ""
                            ),
                            finishReason = firstChoice?.get("finish_reason")?.jsonPrimitive?.content
                        ))
                    ))
                } catch (_: Exception) {}
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
                    else -> IOException("SSE connection failed")
                }
                channel.close(error)
            }
        })

        return channel.consumeAsFlow().onCompletion {
            eventSource.cancel()
        }
    }

    override suspend fun testConnection(): Result<Unit> {
        return try {
            listModels()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun listModels(): List<String> = suspendCancellableCoroutine { cont ->
        val req = Request.Builder()
            .url("$baseUrl/models")
            .get()
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        if (cont.isActive) cont.resumeWithException(
                            RuntimeException("HTTP ${response.code}")
                        )
                        return
                    }

                    val body = response.body?.string() ?: "{}"
                    val jsonResp = json.parseToJsonElement(body).jsonObject
                    val data = jsonResp["data"]?.jsonArray ?: emptyList()
                    val models = data.map { it.jsonObject["id"]?.jsonPrimitive?.content ?: "" }

                    if (cont.isActive) cont.resume(models)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        })
    }
}

private fun Map<String, Any>.toJsonObject(): JsonElement {
    return buildJsonObject {
        this@toJsonObject.forEach { (key, value) ->
            when (value) {
                is String -> put(key, JsonPrimitive(value))
                is Number -> put(key, JsonPrimitive(value))
                is Boolean -> put(key, JsonPrimitive(value))
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") put(key, (value as Map<String, Any>).toJsonObject())
                is List<*> -> put(key, buildJsonArray {
                    value.forEach { item ->
                        when (item) {
                            is String -> add(JsonPrimitive(item))
                            is Number -> add(JsonPrimitive(item))
                            is Boolean -> add(JsonPrimitive(item))
                            is Map<*, *> -> @Suppress("UNCHECKED_CAST") add((item as Map<String, Any>).toJsonObject())
                            else -> add(JsonPrimitive(item.toString()))
                        }
                    }
                })
                else -> put(key, JsonPrimitive(value.toString()))
            }
        }
    }
}

